package com.alibaba.android.arouter.ksp

import com.alibaba.android.arouter.facade.enums.RouteType
import com.alibaba.android.arouter.facade.enums.TypeKind
import com.google.devtools.ksp.getAllSuperTypes
import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.symbol.KSAnnotation
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSFile
import com.google.devtools.ksp.symbol.KSPropertyDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.MemberName
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.TypeSpec
import com.squareup.kotlinpoet.WildcardTypeName
import com.squareup.kotlinpoet.asClassName
import com.squareup.kotlinpoet.ksp.writeTo

/**
 * Collects @Route annotated classes and generates IRouteGroup, IProviderGroup, IRouteRoot implementations.
 */
class RouteCollector(
    private val codeGenerator: CodeGenerator,
    private val logger: KSPLogger
) {
    // group name -> list of route info
    private val groupMap = mutableMapOf<String, MutableSet<RouteInfo>>()
    // group name -> generated group class name
    private val rootMap = sortedMapOf<String, String>()
    private val sourceFiles = mutableSetOf<KSFile>()

    // Provider info: interface qualified name -> RouteInfo
    private val providerEntries = mutableListOf<ProviderEntry>()

    fun collect(routes: List<KSClassDeclaration>) {
        logger.info(">>> Found routes, size is ${routes.size} <<<")

        for (classDecl in routes) {
            classDecl.containingFile?.let { sourceFiles.add(it) }

            val routeAnnotation = classDecl.annotations.first {
                it.annotationType.resolve().declaration.qualifiedName?.asString() == Consts.ANNOTATION_TYPE_ROUTE
            }

            val path = routeAnnotation.getArgString("path") ?: continue
            val app = routeAnnotation.getArgString("app") ?: ""
            val group = routeAnnotation.getArgString("group") ?: ""
            val name = routeAnnotation.getArgString("name") ?: ""
            val priority = routeAnnotation.getArgInt("priority") ?: -1
            val extras = routeAnnotation.getArgInt("extras") ?: Int.MIN_VALUE

            // Determine route type
            val supertypeNames = classDecl.getAllSuperTypes()
                .map { it.declaration.qualifiedName?.asString() ?: "" }
                .toSet()

            val routeType = when {
                Consts.ACTIVITY in supertypeNames -> RouteType.ACTIVITY
                Consts.FRAGMENT in supertypeNames -> RouteType.FRAGMENT
                Consts.IPROVIDER in supertypeNames -> RouteType.PROVIDER
                Consts.SERVICE in supertypeNames -> RouteType.SERVICE
                else -> RouteType.FRAGMENT // default fallback, same as original
            }

            // Collect @Autowired params for Activity/Fragment
            val paramsType = mutableMapOf<String, Int>()
            if (routeType == RouteType.ACTIVITY || routeType == RouteType.FRAGMENT) {
                collectAutowiredParams(classDecl, paramsType, supertypeNames)
            }

            val qualifiedName = classDecl.qualifiedName?.asString() ?: continue
            val packageName = classDecl.packageName.asString()

            val routeInfo = RouteInfo(
                path = path,
                app = app,
                group = group,
                name = name,
                priority = priority,
                extras = extras,
                routeType = routeType,
                className = qualifiedName,
                packageName = packageName,
                simpleName = classDecl.simpleName.asString(),
                paramsType = paramsType
            )

            // Verify and categorize
            if (!routeVerify(routeInfo)) {
                logger.warn(">>> Route meta verify error, group is ${routeInfo.group} <<<")
                continue
            }

            // Collect provider interfaces
            if (routeType == RouteType.PROVIDER) {
                collectProviderInterfaces(classDecl, routeInfo)
            }

            logger.info(">>> Found ${routeType.name.lowercase()} route: $qualifiedName, path: $path <<<")

            groupMap.getOrPut(routeInfo.group) { sortedSetOf(compareBy { it.path }) }.add(routeInfo)
        }
    }

    fun generate(moduleName: String) {
        val deps = if (sourceFiles.isNotEmpty()) {
            Dependencies(true, *sourceFiles.toTypedArray())
        } else {
            Dependencies(false)
        }

        // Generate group files
        for ((groupName, routeSet) in groupMap) {
            val groupClassName = "${Consts.NAME_OF_GROUP}$groupName"
            generateGroupFile(groupClassName, routeSet, deps)
            rootMap[groupName] = groupClassName
        }

        // Generate provider file
        val providerClassName = "${Consts.NAME_OF_PROVIDER}${Consts.SEPARATOR}$moduleName"
        generateProviderFile(providerClassName, deps)

        // Generate root file
        val rootClassName = "${Consts.NAME_OF_ROOT}${Consts.SEPARATOR}$moduleName"
        generateRootFile(rootClassName, deps)
    }

    private fun generateGroupFile(
        className: String,
        routes: Set<RouteInfo>,
        deps: Dependencies
    ) {
        val routeMetaCn = ClassName("com.alibaba.android.arouter.facade.model", "RouteMeta")
        val routeTypeCn = ClassName("com.alibaba.android.arouter.facade.enums", "RouteType")
        val iRouteGroupCn = ClassName.bestGuess(Consts.IROUTE_GROUP)

        val loadIntoFun = FunSpec.builder(Consts.METHOD_LOAD_INTO)
            .addModifiers(KModifier.OVERRIDE)
            .addParameter(
                "atlas",
                MUTABLE_MAP.parameterizedBy(String::class.asClassName(), routeMetaCn)
            )
            .apply {
                for (route in routes) {
                    val destCn = ClassName.bestGuess(route.className)
                    val paramsExpr = if (route.paramsType.isNotEmpty()) {
                        val entries = route.paramsType.entries.joinToString(", ") { (k, v) ->
                            "\"$k\" to $v"
                        }
                        "hashMapOf<String, Int>($entries)"
                    } else {
                        "null"
                    }
                    addStatement(
                        "atlas[%S] = %T.build(%T.%L, %T::class.java, %S, %S, %S, $paramsExpr, %L, %L)",
                        route.path,
                        routeMetaCn,
                        routeTypeCn,
                        route.routeType.name,
                        destCn,
                        route.path,
                        route.app,
                        route.group.lowercase(),
                        route.priority,
                        route.extras
                    )
                }
            }
            .build()

        val typeSpec = TypeSpec.classBuilder(className)
            .addKdoc(Consts.WARNING_TIPS)
            .addSuperinterface(iRouteGroupCn)
            .addFunction(loadIntoFun)
            .build()

        FileSpec.builder(Consts.PACKAGE_OF_GENERATE_FILE, className)
            .addType(typeSpec)
            .build()
            .writeTo(codeGenerator, deps)

        logger.info(">>> Generated group: $className <<<")
    }

    private fun generateProviderFile(className: String, deps: Dependencies) {
        val routeMetaCn = ClassName("com.alibaba.android.arouter.facade.model", "RouteMeta")
        val routeTypeCn = ClassName("com.alibaba.android.arouter.facade.enums", "RouteType")
        val iProviderGroupCn = ClassName.bestGuess(Consts.IPROVIDER_GROUP)

        val loadIntoFun = FunSpec.builder(Consts.METHOD_LOAD_INTO)
            .addModifiers(KModifier.OVERRIDE)
            .addParameter(
                "providers",
                MUTABLE_MAP.parameterizedBy(String::class.asClassName(), routeMetaCn)
            )
            .apply {
                for (entry in providerEntries) {
                    val destCn = ClassName.bestGuess(entry.routeInfo.className)
                    addStatement(
                        "providers[%S] = %T.build(%T.%L, %T::class.java, %S, %S, null, %L, %L)",
                        entry.interfaceName,
                        routeMetaCn,
                        routeTypeCn,
                        RouteType.PROVIDER.name,
                        destCn,
                        entry.routeInfo.path,
                        entry.routeInfo.group.lowercase(),
                        entry.routeInfo.priority,
                        entry.routeInfo.extras
                    )
                }
            }
            .build()

        val typeSpec = TypeSpec.classBuilder(className)
            .addKdoc(Consts.WARNING_TIPS)
            .addSuperinterface(iProviderGroupCn)
            .addFunction(loadIntoFun)
            .build()

        FileSpec.builder(Consts.PACKAGE_OF_GENERATE_FILE, className)
            .addType(typeSpec)
            .build()
            .writeTo(codeGenerator, deps)

        logger.info(">>> Generated provider map: $className <<<")
    }

    private fun generateRootFile(className: String, deps: Dependencies) {
        val iRouteGroupCn = ClassName.bestGuess(Consts.IROUTE_GROUP)
        val iRouteRootCn = ClassName.bestGuess(Consts.IROUTE_ROOT)

        val mapType = MUTABLE_MAP.parameterizedBy(
            String::class.asClassName(),
            Class::class.asClassName().parameterizedBy(
                WildcardTypeName.producerOf(iRouteGroupCn)
            )
        )

        val loadIntoFun = FunSpec.builder(Consts.METHOD_LOAD_INTO)
            .addModifiers(KModifier.OVERRIDE)
            .addParameter("routes", mapType)
            .apply {
                for ((groupName, groupClassName) in rootMap) {
                    val groupCn = ClassName(Consts.PACKAGE_OF_GENERATE_FILE, groupClassName)
                    addStatement("routes[%S] = %T::class.java", groupName, groupCn)
                }
            }
            .build()

        val typeSpec = TypeSpec.classBuilder(className)
            .addKdoc(Consts.WARNING_TIPS)
            .addSuperinterface(iRouteRootCn)
            .addFunction(loadIntoFun)
            .build()

        FileSpec.builder(Consts.PACKAGE_OF_GENERATE_FILE, className)
            .addType(typeSpec)
            .build()
            .writeTo(codeGenerator, deps)

        logger.info(">>> Generated root: $className <<<")
    }

    /**
     * Collect @Autowired parameters from a class (recursive up parent chain).
     */
    private fun collectAutowiredParams(
        classDecl: KSClassDeclaration,
        paramsType: MutableMap<String, Int>,
        routeSupertypeNames: Set<String>
    ) {
        for (prop in classDecl.declarations.filterIsInstance<KSPropertyDeclaration>()) {
            val autowiredAnn = prop.annotations.firstOrNull {
                it.annotationType.resolve().declaration.qualifiedName?.asString() == Consts.ANNOTATION_TYPE_AUTOWIRED
            } ?: continue

            // Skip provider fields (same as original)
            val propType = prop.type.resolve()
            val propSupertypes = TypeExchange.collectSupertypeNames(propType)
            if (Consts.IPROVIDER in propSupertypes) continue

            val paramName = autowiredAnn.getArgString("name")
                .let { if (it.isNullOrEmpty()) prop.simpleName.asString() else it }

            paramsType[paramName] = TypeExchange.exchange(propType, propSupertypes)
        }

        // Check parent class
        classDecl.superTypes.forEach { ref ->
            val resolved = ref.resolve()
            val parentDecl = resolved.declaration as? KSClassDeclaration ?: return@forEach
            val parentName = parentDecl.qualifiedName?.asString() ?: return@forEach
            if (!parentName.startsWith("android") && !parentName.startsWith("java") && !parentName.startsWith("kotlin")) {
                collectAutowiredParams(parentDecl, paramsType, routeSupertypeNames)
            }
        }
    }

    /**
     * Collect provider interface names for a @Route(PROVIDER) class.
     *
     * Mirrors the original KAPT RouteProcessor logic:
     * - If the interface IS IProvider itself → key = the implementing class's qualified name
     * - If the interface EXTENDS IProvider → key = the interface's full type string (with generics)
     */
    private fun collectProviderInterfaces(classDecl: KSClassDeclaration, routeInfo: RouteInfo) {
        for (superTypeRef in classDecl.superTypes) {
            val superType = superTypeRef.resolve()
            val superDecl = superType.declaration as? KSClassDeclaration ?: continue
            val interfaceName = superDecl.qualifiedName?.asString() ?: continue

            if (interfaceName == Consts.IPROVIDER) {
                // Directly implements IProvider → key is the implementing class name (same as KAPT)
                providerEntries.add(ProviderEntry(routeInfo.className, routeInfo))
            } else {
                val supertypeNames = superDecl.getAllSuperTypes()
                    .map { it.declaration.qualifiedName?.asString() ?: "" }
                    .toSet()

                if (Consts.IPROVIDER in supertypeNames) {
                    // Extends IProvider → key is the interface type string with generic arguments
                    val typeKey = buildTypeString(superType)
                    providerEntries.add(ProviderEntry(typeKey, routeInfo))
                }
            }
        }
    }

    /**
     * Build a type string that includes generic type arguments, mirroring Java's TypeMirror.toString().
     * e.g. "com.example.IService<java.lang.String>" instead of just "com.example.IService"
     */
    private fun buildTypeString(type: KSType): String {
        val baseName = type.declaration.qualifiedName?.asString() ?: return "Any"
        val args = type.arguments
        if (args.isEmpty()) return baseName

        val argStrings = args.joinToString(", ") { arg ->
            arg.type?.resolve()?.let { resolvedType ->
                buildTypeString(resolvedType)
            } ?: "?"
        }
        return "$baseName<$argStrings>"
    }

    private fun routeVerify(meta: RouteInfo): Boolean {
        val path = meta.path
        if (path.isEmpty() || !path.startsWith("/")) return false

        if (meta.group.isEmpty()) {
            val secondSlash = path.indexOf("/", 1)
            if (secondSlash < 0) return false
            val defaultGroup = path.substring(1, secondSlash)
            if (defaultGroup.isEmpty()) return false
            meta.group = defaultGroup
        }
        return true
    }

    companion object {
        private val MUTABLE_MAP = ClassName("kotlin.collections", "MutableMap")
    }
}

data class RouteInfo(
    val path: String,
    val app: String,
    var group: String,
    val name: String,
    val priority: Int,
    val extras: Int,
    val routeType: RouteType,
    val className: String,
    val packageName: String,
    val simpleName: String,
    val paramsType: Map<String, Int>
)

data class ProviderEntry(
    val interfaceName: String,
    val routeInfo: RouteInfo
)

// Extension helpers for KSAnnotation argument extraction
internal fun KSAnnotation.getArgString(name: String): String? {
    return arguments.firstOrNull { it.name?.asString() == name }?.value as? String
}

internal fun KSAnnotation.getArgInt(name: String): Int? {
    return arguments.firstOrNull { it.name?.asString() == name }?.value as? Int
}
