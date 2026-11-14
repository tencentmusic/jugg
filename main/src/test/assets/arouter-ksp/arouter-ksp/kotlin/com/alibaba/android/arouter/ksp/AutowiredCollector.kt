package com.alibaba.android.arouter.ksp

import com.alibaba.android.arouter.facade.enums.TypeKind
import com.google.devtools.ksp.getAllSuperTypes
import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSFile
import com.google.devtools.ksp.symbol.KSPropertyDeclaration
import com.google.devtools.ksp.symbol.Modifier
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.ANY
import com.squareup.kotlinpoet.TypeSpec
import com.squareup.kotlinpoet.ksp.writeTo

/**
 * Collects @Autowired annotated fields and generates ISyringe implementations.
 * Each class with @Autowired fields gets a corresponding $$ARouter$$Autowired helper.
 */
class AutowiredCollector(
    private val codeGenerator: CodeGenerator,
    private val logger: KSPLogger
) {
    // Parent class -> list of @Autowired properties
    private val parentAndChild = mutableMapOf<KSClassDeclaration, MutableList<FieldInfo>>()
    private val sourceFiles = mutableSetOf<KSFile>()

    fun collect(fields: List<KSPropertyDeclaration>) {
        logger.info(">>> Found autowired fields, size is ${fields.size} <<<")

        for (prop in fields) {
            val parent = prop.parentDeclaration as? KSClassDeclaration ?: continue
            prop.containingFile?.let { sourceFiles.add(it) }

            if (Modifier.PRIVATE in prop.modifiers) {
                throw IllegalAccessException(
                    "The inject fields CAN NOT BE 'private'!!! please check field " +
                        "[${prop.simpleName.asString()}] in class [${parent.qualifiedName?.asString()}]"
                )
            }

            val autowiredAnn = prop.annotations.first {
                it.annotationType.resolve().declaration.qualifiedName?.asString() == Consts.ANNOTATION_TYPE_AUTOWIRED
            }

            val configName = autowiredAnn.getArgString("name") ?: ""
            val required = autowiredAnn.arguments.firstOrNull { it.name?.asString() == "required" }?.value as? Boolean ?: false
            val desc = autowiredAnn.getArgString("desc") ?: ""

            val propType = prop.type.resolve()
            val propSupertypes = TypeExchange.collectSupertypeNames(propType)
            val isProvider = Consts.IPROVIDER in propSupertypes
            val typeKindOrdinal = TypeExchange.exchange(propType, propSupertypes)

            val fieldInfo = FieldInfo(
                fieldName = prop.simpleName.asString(),
                configName = configName,
                required = required,
                desc = desc,
                isProvider = isProvider,
                typeKindOrdinal = typeKindOrdinal,
                typeName = propType.declaration.qualifiedName?.asString() ?: "Any"
            )

            parentAndChild.getOrPut(parent) { mutableListOf() }.add(fieldInfo)
        }
    }

    fun generate() {
        if (parentAndChild.isEmpty()) return

        val iSyringe = ClassName.bestGuess(Consts.ISYRINGE)
        val aRouter = ClassName("com.alibaba.android.arouter.launcher", "ARouter")
        val androidLog = ClassName("android.util", "Log")
        val serializationService = ClassName.bestGuess(Consts.JSON_SERVICE)
        val typeWrapper = ClassName.bestGuess(Consts.TYPE_WRAPPER)

        for ((parent, children) in parentAndChild) {
            val qualifiedName = parent.qualifiedName?.asString() ?: continue
            val packageName = qualifiedName.substring(0, qualifiedName.lastIndexOf("."))
            val fileName = "${parent.simpleName.asString()}${Consts.NAME_OF_AUTOWIRED}"
            val parentCn = ClassName.bestGuess(qualifiedName)

            val parentSupertypes = parent.getAllSuperTypes()
                .map { it.declaration.qualifiedName?.asString() ?: "" }
                .toSet()
            val isActivity = Consts.ACTIVITY in parentSupertypes
            val isFragment = Consts.FRAGMENT in parentSupertypes

            val deps = parent.containingFile?.let {
                Dependencies(true, it)
            } ?: Dependencies(false)

            val injectFun = FunSpec.builder(Consts.METHOD_INJECT)
                .addModifiers(KModifier.OVERRIDE)
                .addParameter("target", ANY)
                .addStatement("val serializationService = %T.getInstance().navigation(%T::class.java)", aRouter, serializationService)
                .addStatement("val substitute = target as %T", parentCn)
                .apply {
                    for (field in children) {
                        if (field.isProvider) {
                            generateProviderInjection(this, field, aRouter, parentCn)
                        } else {
                            generateIntentInjection(this, field, isActivity, isFragment, androidLog, parentCn, typeWrapper)
                        }
                    }
                }
                .build()

            val typeSpec = TypeSpec.classBuilder(fileName)
                .addKdoc(Consts.WARNING_TIPS)
                .addSuperinterface(iSyringe)
                .addFunction(injectFun)
                .build()

            FileSpec.builder(packageName, fileName)
                .addType(typeSpec)
                .build()
                .writeTo(codeGenerator, deps)

            logger.info(">>> ${parent.simpleName.asString()} has been processed, $fileName has been generated. <<<")
        }
    }

    private fun generateProviderInjection(
        builder: FunSpec.Builder,
        field: FieldInfo,
        aRouter: ClassName,
        parent: ClassName
    ) {
        val fieldTypeCn = ClassName.bestGuess(field.typeName)

        if (field.configName.isEmpty()) {
            // By type
            builder.addStatement(
                "substitute.%L = %T.getInstance().navigation(%T::class.java)",
                field.fieldName, aRouter, fieldTypeCn
            )
        } else {
            // By name
            builder.addStatement(
                "substitute.%L = %T.getInstance().build(%S).navigation() as %T",
                field.fieldName, aRouter, field.configName, fieldTypeCn
            )
        }

        if (field.required) {
            builder.beginControlFlow("if (substitute.%L == null)", field.fieldName)
            builder.addStatement(
                "throw RuntimeException(%S + %T::class.java.name + %S)",
                "The field '${field.fieldName}' is null, in class '",
                parent,
                "'!"
            )
            builder.endControlFlow()
        }
    }

    private fun generateIntentInjection(
        builder: FunSpec.Builder,
        field: FieldInfo,
        isActivity: Boolean,
        isFragment: Boolean,
        log: ClassName,
        parent: ClassName,
        typeWrapper: ClassName
    ) {
        if (!isActivity && !isFragment) {
            logger.error("The field [${field.fieldName}] need autowired from intent, its parent must be activity or fragment!")
            return
        }

        val paramKey = field.configName.ifEmpty { field.fieldName }
        val typeKind = TypeKind.values()[field.typeKindOrdinal]

        when (typeKind) {
            TypeKind.BOOLEAN, TypeKind.BYTE, TypeKind.SHORT, TypeKind.INT,
            TypeKind.LONG, TypeKind.CHAR, TypeKind.FLOAT, TypeKind.DOUBLE -> {
                val getterName = "get${typeKind.name.lowercase().replaceFirstChar { it.uppercase() }}"
                if (isActivity) {
                    builder.addStatement(
                        "substitute.%L = substitute.intent.%L(%S, substitute.%L)",
                        field.fieldName, "${getterName}Extra", paramKey, field.fieldName
                    )
                } else {
                    builder.addStatement(
                        "substitute.%L = substitute.arguments?.%L(%S, substitute.%L) ?: substitute.%L",
                        field.fieldName, getterName, paramKey, field.fieldName, field.fieldName
                    )
                }
            }
            TypeKind.STRING -> {
                if (isActivity) {
                    builder.addStatement(
                        "substitute.%L = substitute.intent.extras?.getString(%S, substitute.%L) ?: substitute.%L",
                        field.fieldName, paramKey, field.fieldName, field.fieldName
                    )
                } else {
                    builder.addStatement(
                        "substitute.%L = substitute.arguments?.getString(%S, substitute.%L) ?: substitute.%L",
                        field.fieldName, paramKey, field.fieldName, field.fieldName
                    )
                }
            }
            TypeKind.SERIALIZABLE -> {
                val fieldTypeCn = ClassName.bestGuess(field.typeName)
                if (isActivity) {
                    builder.addStatement(
                        "substitute.%L = substitute.intent.getSerializableExtra(%S) as? %T ?: substitute.%L",
                        field.fieldName, paramKey, fieldTypeCn, field.fieldName
                    )
                } else {
                    builder.addStatement(
                        "substitute.%L = substitute.arguments?.getSerializable(%S) as? %T ?: substitute.%L",
                        field.fieldName, paramKey, fieldTypeCn, field.fieldName
                    )
                }
            }
            TypeKind.PARCELABLE -> {
                if (isActivity) {
                    builder.addStatement(
                        "substitute.%L = substitute.intent.getParcelableExtra(%S) ?: substitute.%L",
                        field.fieldName, paramKey, field.fieldName
                    )
                } else {
                    builder.addStatement(
                        "substitute.%L = substitute.arguments?.getParcelable(%S) ?: substitute.%L",
                        field.fieldName, paramKey, field.fieldName
                    )
                }
            }
            TypeKind.OBJECT -> {
                addObjectStatement(field, isActivity, builder, paramKey, typeWrapper, log, parent)
            }
        }

        // Required validation for non-primitive types
        if (field.required && typeKind != TypeKind.BOOLEAN && typeKind != TypeKind.BYTE &&
            typeKind != TypeKind.SHORT && typeKind != TypeKind.INT && typeKind != TypeKind.LONG &&
            typeKind != TypeKind.CHAR && typeKind != TypeKind.FLOAT && typeKind != TypeKind.DOUBLE
        ) {
            builder.beginControlFlow("if (substitute.%L == null)", field.fieldName)
            builder.addStatement(
                "%T.e(%S, %S + %T::class.java.name + %S)",
                log, Consts.TAG,
                "The field '${field.fieldName}' is null, in class '",
                parent,
                "'!"
            )
            builder.endControlFlow()
        }
    }

    private fun addObjectStatement(
        field: FieldInfo,
        isActivity: Boolean,
        builder: FunSpec.Builder,
        paramKey: String,
        typeWrapper: ClassName,
        log: ClassName,
        parent: ClassName
    ) {
        val fieldTypeCn = ClassName.bestGuess(field.typeName)
        val sourceExpr = if (isActivity) {
            "substitute.intent.getStringExtra(%S)"
        } else {
            "substitute.arguments?.getString(%S)"
        }
        builder.beginControlFlow("if (serializationService != null)")
        builder.addStatement(
            "substitute.%L = serializationService.parseObject($sourceExpr, object : %T<%T>() {}.type)",
            field.fieldName, paramKey, typeWrapper, fieldTypeCn
        )
        builder.nextControlFlow("else")
        builder.addStatement(
            "%T.e(%S, %S)",
            log,
            Consts.TAG,
            "You want automatic inject the field '${field.fieldName}' in class '${parent}', " +
                    "then you should implement 'SerializationService' to support object auto inject!"
        )
        builder.endControlFlow()
    }
}

data class FieldInfo(
    val fieldName: String,
    val configName: String,
    val required: Boolean,
    val desc: String,
    val isProvider: Boolean,
    val typeKindOrdinal: Int,
    val typeName: String
)
