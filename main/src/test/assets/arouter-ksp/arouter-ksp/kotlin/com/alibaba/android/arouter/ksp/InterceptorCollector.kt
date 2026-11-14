package com.alibaba.android.arouter.ksp

import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSFile
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.INT
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.WildcardTypeName
import com.squareup.kotlinpoet.asClassName
import com.squareup.kotlinpoet.TypeSpec
import com.squareup.kotlinpoet.ksp.writeTo

/**
 * Collects @Interceptor annotated classes and generates IInterceptorGroup implementation.
 */
class InterceptorCollector(
    private val codeGenerator: CodeGenerator,
    private val logger: KSPLogger
) {
    // priority -> class declaration (sorted by priority)
    private val interceptors = sortedMapOf<Int, KSClassDeclaration>()
    private val sourceFiles = mutableSetOf<KSFile>()

    fun collect(classes: List<KSClassDeclaration>) {
        logger.info(">>> Found interceptors, size is ${classes.size} <<<")

        for (classDecl in classes) {
            classDecl.containingFile?.let { sourceFiles.add(it) }

            // Verify: must implement IInterceptor
            val supertypeNames = classDecl.superTypes.map {
                it.resolve().declaration.qualifiedName?.asString() ?: ""
            }.toSet()

            if (Consts.IINTERCEPTOR !in supertypeNames) {
                logger.error(
                    "A interceptor verify failed, ${classDecl.qualifiedName?.asString()} must implement IInterceptor",
                    classDecl
                )
                continue
            }

            val interceptorAnn = classDecl.annotations.first {
                it.annotationType.resolve().declaration.qualifiedName?.asString() == Consts.ANNOTATION_TYPE_INTERCEPTOR
            }

            val priority = interceptorAnn.getArgInt("priority") ?: 0

            val existing = interceptors[priority]
            if (existing != null) {
                throw IllegalArgumentException(
                    "More than one interceptors use same priority [$priority], " +
                        "They are [${existing.simpleName.asString()}] and [${classDecl.simpleName.asString()}]."
                )
            }

            interceptors[priority] = classDecl
            logger.info(">>> A interceptor verify over, its ${classDecl.qualifiedName?.asString()}, priority: $priority <<<")
        }
    }

    fun generate(moduleName: String) {
        if (interceptors.isEmpty()) return

        val iInterceptorCn = ClassName.bestGuess(Consts.IINTERCEPTOR)
        val iInterceptorGroupCn = ClassName.bestGuess(Consts.IINTERCEPTOR_GROUP)

        val mapType = ClassName("kotlin.collections", "MutableMap").parameterizedBy(
            Int::class.asClassName(),
            Class::class.asClassName().parameterizedBy(
                WildcardTypeName.producerOf(iInterceptorCn)
            )
        )

        val loadIntoFun = FunSpec.builder(Consts.METHOD_LOAD_INTO)
            .addModifiers(KModifier.OVERRIDE)
            .addParameter("interceptors", mapType)
            .apply {
                for ((priority, classDecl) in interceptors) {
                    val classCn = ClassName.bestGuess(classDecl.qualifiedName!!.asString())
                    addStatement("interceptors[%L] = %T::class.java", priority, classCn)
                }
            }
            .build()

        val className = "${Consts.NAME_OF_INTERCEPTOR}${Consts.SEPARATOR}$moduleName"

        val typeSpec = TypeSpec.classBuilder(className)
            .addKdoc(Consts.WARNING_TIPS)
            .addSuperinterface(iInterceptorGroupCn)
            .addFunction(loadIntoFun)
            .build()

        val deps = if (sourceFiles.isNotEmpty()) {
            Dependencies(true, *sourceFiles.toTypedArray())
        } else {
            Dependencies(false)
        }

        FileSpec.builder(Consts.PACKAGE_OF_GENERATE_FILE, className)
            .addType(typeSpec)
            .build()
            .writeTo(codeGenerator, deps)

        logger.info(">>> Interceptor group write over: $className <<<")
    }
}
