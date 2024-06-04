package com.sickworm.intellij.jugg.gradle.script

import com.sickworm.intellij.jugg.project.data.*
import com.sickworm.intellij.jugg.project.data.Dependency
import com.sickworm.intellij.jugg.project.data.ModuleDependency
import org.gradle.api.*
import org.gradle.api.artifacts.*
import org.gradle.api.artifacts.component.ProjectComponentIdentifier
import org.gradle.api.artifacts.result.ResolvedArtifactResult
import org.gradle.api.attributes.*
import org.gradle.api.specs.Spec
import java.io.File

class GradleProjectInfoReader(
    private val rootProject: Project,
    private val lastProjectInfo: JuggProjectInfoSerialize?,
) {

    private var dependenciesCache: MutableMap<String, List<Dependency>> = mutableMapOf()
    private var dependenciesCrcCache: MutableMap<String, LibraryDependency> = mutableMapOf()
//    private var dependencyTransitives: MutableMap<String, List<Dependency>> = mutableMapOf()
    private var totalReadArtifacts = 0
    private var resolveArtifacts = 0
    private var printResolveDetail = false

    private var modulesNames = setOf<String>()

    fun getProjectInfo(): JuggProjectInfo {
        TraceLogger.clear()
        // load dependenciesCache
        // we can not use lastProjectInfo for cache because it misses the info of transitive dependencies
        TraceLogger.start("loadDependencyCrcCache")
        dependenciesCrcCache = mutableMapOf()
        lastProjectInfo?.dependencyList?.forEach {
            dependenciesCrcCache[it.file.absolutePath] = it
        }
        TraceLogger.end("loadDependencyCrcCache")

        modulesNames = rootProject.subprojects.map { it.standardModuleName }.toSet()
        val modules = mutableMapOf<String, ModuleInfo>()
        rootProject.subprojects.forEach { project: Project ->
            val moduleInfo = getModuleInfo(project)
            modules[moduleInfo.name] = moduleInfo
        }
//        // handle transitive dependencies
//        TraceLogger.start("transitiveDependencies")
//        modules.values.toList().forEach { module ->
//            var toResolveModuleDependencies = module.moduleDependencies.toList()
//            val libraryResult: MutableMap<String, LibraryDependency> = mutableMapOf()
//            val runtimeResult: MutableMap<String, LibraryDependency> = mutableMapOf()
//            val moduleResult: MutableMap<String, ModuleDependency> = mutableMapOf()
//            libraryResult.addToResult(module.libraryDependencies)
//            runtimeResult.addToResult(module.runtimeLibraryDependencies)
//            moduleResult.addToResult(module.moduleDependencies)
//            while (toResolveModuleDependencies.isNotEmpty()) {
//                val nextToResolveModuleDependencies = mutableListOf<ModuleDependency>()
//                toResolveModuleDependencies.forEach { moduleDependency ->
//                    dependencyTransitives[moduleDependency.moduleName]?.forEach {
//                        if (it is LibraryDependency) {
//                            libraryResult.addToResult(it)
//                            runtimeResult.addToResult(it)
//                        } else if (it is ModuleDependency) {
//                            nextToResolveModuleDependencies.add(it)
//                            moduleResult.addToResult(it)
//                        }
//                    }
//                }
//                toResolveModuleDependencies = nextToResolveModuleDependencies
//            }
//
//            modules[module.name] = module.copy(
//                moduleDependencies = moduleResult.values.toList(),
//                libraryDependencies = libraryResult.values.toList(),
//                runtimeLibraryDependencies = runtimeResult.values.toList(),
//            )
//        }
//        TraceLogger.end("transitiveDependencies")

        println("totalReadArtifacts $totalReadArtifacts, resolveArtifacts: $resolveArtifacts")
        TraceLogger.printAllCost()

        return JuggProjectInfo(modules)
    }

    private fun getModuleInfo(project: Project): ModuleInfo {
        TraceLogger.start("getModule:${project.standardModuleName}")
        TraceLogger.start("getVar")
        val moduleType = when {
            project.plugins.hasPlugin("com.android.application") -> ModuleInfo.Type.Application
            project.plugins.hasPlugin("com.android.library") -> ModuleInfo.Type.Library
            project.plugins.hasPlugin("java-library") -> ModuleInfo.Type.JavaLibrary
            else -> ModuleInfo.Type.Unknown
        }

        var moduleInfo = ModuleInfo.virtualModule.copy(
            name = project.standardModuleName,
            moduleType = moduleType,
            moduleRootDir = project.projectDir,
            projectRootDir = rootProject.projectDir,
        )
        val buildVariant = getBuildVariant(project)
        if (buildVariant != null) {
            moduleInfo = moduleInfo.copy(buildPathInfo = ModuleBuildPathInfo(rootProject.projectDir, project.projectDir, buildVariant))
        }

        if (moduleType.isAndroidModule) {
            try {
                // com.android.build.gradle.AppExtension
                // com.android.build.gradle.LibraryExtension
                val androidExt = Reflector(project.extensions.getByName("android"))
                val compileSdkVersion = androidExt["compileSdkVersion"]?.valueString
                val buildToolsVersion = androidExt["buildToolsVersion"]?.valueString
                // can not get it in init.gradle.kts
                // com.android.build.gradle.internal.CompileOptions
                val compileOptions = androidExt["compileOptions"]
                // com.android.build.gradle.internal.dsl.DefaultConfig
                val defaultConfig = androidExt["defaultConfig"]
                // org.jetbrains.kotlin.gradle.dsl.KotlinJvmOptions
                val extensions = androidExt["extensions"]
                val kotlinJvmOptions = extensions?.invoke("getByName", "kotlinOptions")

                var manifestPlaceholders: Map<String, String>? = null
                val manifestValue = defaultConfig["manifestPlaceholders"]?.value as? Map<*, *>
                if (!manifestValue.isNullOrEmpty()) {
                    manifestPlaceholders = mutableMapOf()
                    manifestValue.forEach { (key, value) ->
                        manifestPlaceholders.put(key.toString(), value.toString())
                    }
                }

                val sourceDirs = mutableSetOf<File>()
                val resDirs = mutableSetOf<File>()
                val assetDirs = mutableSetOf<File>()
                var manifestFile: File? = null

                val sourceSetsList = mutableListOf<Reflector>()
                androidExt["sourceSets"]?.let { sourceSets ->
                    // com.android.build.gradle.api.AndroidSourceSet
                    val mainSourceSet = sourceSets.invoke("findByName", "main")
                    if (mainSourceSet != null) {
                        sourceSetsList.add(mainSourceSet)
                    }
                    if (buildVariant != null) {
                        val variantSourceSet = sourceSets.invoke("findByName", buildVariant)
                        if (variantSourceSet != null) {
                            sourceSetsList.add(variantSourceSet)
                        }
                    }
                }

                @Suppress("UNCHECKED_CAST")
                sourceSetsList.forEach { sourceSets ->
                    // com.android.build.gradle.api.AndroidSourceSet
                    // com.android.build.gradle.internal.api.DefaultAndroidSourceSet
                    (sourceSets.invoke("getJavaDirectories")?.value as? Collection<File>)?.let {
                        sourceDirs.addAll(it)
                    }
                    (sourceSets.invoke("getKotlinDirectories")?.value as? Collection<File>)?.let {
                        sourceDirs.addAll(it)
                    }
                    (sourceSets.invoke("getResDirectories")?.value as? Collection<File>)?.let {
                        resDirs.addAll(it)
                    }
                    (sourceSets.invoke("getAssetsDirectories")?.value as? Collection<File>)?.let {
                        assetDirs.addAll(it)
                    }
                    (sourceSets.invoke("getManifestFile")?.value as? File)?.let {
                        manifestFile = it
                    }
                }

                @Suppress("UNCHECKED_CAST")
                moduleInfo = moduleInfo.copy(
                    compileVersion = compileSdkVersion,
                    buildToolsVersion = buildToolsVersion,
                    minSdkVersion = defaultConfig["minSdkVersion"]["apiLevel"]?.valueString,
                    kotlinJvmTarget = kotlinJvmOptions["jvmTarget"]?.valueString,
                    kotlinFreeCompilerArgs = (kotlinJvmOptions["freeCompilerArgs"]?.value as? List<String>) ?: emptyList(),
                    javaSourceCompatibility = compileOptions["sourceCompatibility"]?.valueString,
                    javaTargetCompatibility = compileOptions["targetCompatibility"]?.valueString,
                    manifestPlaceHolders = manifestPlaceholders,
                    sourceDirs = sourceDirs.toList(),
                    resourceDirs = resDirs.toList(),
                    assetsDirs = assetDirs.toList(),
                    manifestFile = manifestFile,
                )
            } catch (e: Throwable) {
                println("Jugg: get other info for ${project.standardModuleName} failed: $e")
                printException(e)
            }
        }
        TraceLogger.end("getVar")

        TraceLogger.start("getDep")
        try {
            if (buildVariant == null && moduleType.isAndroidModule) {
                println("Jugg: buildVariant not found for ${project.standardModuleName}, won't parse dependencies")
            } else {
                TraceLogger.start("getCompile")
                val dependFilterName = if (moduleType.isAndroidModule) "${buildVariant}CompileClasspath" else "compileClasspath"
                val dependencies = getDependencies(project, dependFilterName, isAndroidDepend = moduleType.isAndroidModule)
                TraceLogger.end("getCompile")

                // won't actually use this for now to save time
                val runtimeDependencies = emptyList<Dependency>()
//                TraceLogger.start("getRuntime")
//                val runtimeFilterName = if (moduleType.isAndroidModule) "${buildVariant}RuntimeClasspath" else "runtimeClasspath"
//                val runtimeDependencies = getDependencies(project, runtimeFilterName, isAndroidDepend = moduleType.isAndroidModule)
//                TraceLogger.end("getRuntime")

                TraceLogger.start("getAnnotation")
                val annotationProcessorDependencies = getDependencies(project, "annotationProcessor", isAndroidDepend = false)
                TraceLogger.start("getAnnotation")

                TraceLogger.start("getKapt")
                val kaptDependencies = getDependencies(project, "kapt", isAndroidDepend = false)
                TraceLogger.start("getKapt")

                moduleInfo = moduleInfo.copy(
                    moduleDependencies = dependencies.filterIsInstance<ModuleDependency>(),
                    libraryDependencies = dependencies.filterIsInstance<LibraryDependency>(),
                    runtimeLibraryDependencies = runtimeDependencies.filterIsInstance<LibraryDependency>(),
                    annotationProcessorDependencies = annotationProcessorDependencies.filterIsInstance<LibraryDependency>(),
                    kaptDependencies = kaptDependencies.filterIsInstance<LibraryDependency>(),
                )
            }

            // read api dependencies
//            TraceLogger.start("getApi")
//            dependencyTransitives[project.name] = getDependencies(project, "api",
//                isAndroidDepend = moduleType.isAndroidModule, isNeedResolve = false)
//            TraceLogger.end("getApi")
        } catch (e: Throwable) {
            println("Jugg: get dependency info for ${project.standardModuleName} failed: $e")
            printException(e)
        }
        TraceLogger.end("getDep")

        TraceLogger.end("getModule:${project.standardModuleName}")
        return moduleInfo
    }

    private val fixedModuleNameMap: Map<String, ModuleInfo> by lazy {
        lastProjectInfo?.modules?.associate {
            it.moduleInfoExceptLibraries.name to it.moduleInfoExceptLibraries
        } ?: emptyMap()
    }

    private fun getBuildVariant(project: Project): String? {
        return fixedModuleNameMap[project.standardModuleName]?.buildVariant
    }

    private var curName = ""

    private fun getDependencies(project: Project, filterName: String, isAndroidDepend: Boolean, isNeedResolve: Boolean = true): List<Dependency> {
        val result = mutableMapOf<String, Dependency>()
        val allNames = project.configurations.names
        val names = allNames.filter { filterConfigs(it, filterName) }

        names.forEach nameForEach@{ name ->
            val configuration = project.configurations.findByName(name) ?: return@nameForEach
            if (configuration.isCanBeResolved) {
                curName = project.name
                val subResult = getDependencies(configuration, isAndroidDepend)
                totalReadArtifacts += subResult.size
                resolveArtifacts += configuration.allDependencies.size
                result.addToResult(subResult)
            } else {
                val allDependencies = configuration.allDependencies
                allDependencies.forEach { dependencyDeclaration: org.gradle.api.artifacts.Dependency ->
                    val dependencies: List<Dependency> = getDependencies(project, dependencyDeclaration, isAndroidDepend, isNeedResolve)
                    totalReadArtifacts += dependencies.size
                    result.addToResult(dependencies)
                }
            }
        }

        return result.values.toMutableList()
    }

    private fun getDependencies(project: Project, dependency: org.gradle.api.artifacts.Dependency,
                                isAndroidDepend: Boolean, isNeedResolve: Boolean,
    ): List<Dependency> {
        when (dependency) {
            is ExternalModuleDependency -> {
                val declaration = "${dependency.group}:${dependency.name}:${dependency.version}"
                val caches = dependenciesCache[declaration]
                if (caches != null) {
                    return caches
                } else {
                    if (!isNeedResolve) {
                        println("Jugg: $declaration not found in cache, this should not happened.")
                    }
                    TraceLogger.start("getResolve")
                    resolveArtifacts++
                    val resolvedConfiguration = project.configurations.detachedConfiguration(dependency)
                    if (printResolveDetail) {
                        println("Jugg: resolve ${dependency.group}:${dependency.name}:${dependency.version}")
                    }
                    val dependencies = getDependencies(resolvedConfiguration, isAndroidDepend = isAndroidDepend)
                    if (printResolveDetail) {
                        println("Jugg: resolve result: $dependencies")
                    }
                    dependenciesCache[declaration] = dependencies
                    TraceLogger.end("getResolve")
                    return dependencies
                }
            }
            is ProjectDependency -> {
                return listOf(ModuleDependency(dependency.name.standardModuleName))
            }
            is FileCollectionDependency -> {
                val files = (dependency as SelfResolvingDependency).resolve().toList()
                return files.map {
                    val cache = dependenciesCrcCache[it.absolutePath]
                    if (cache?.lastModifiedTime == it.lastModified()) {
                        return@map cache
                    } else {
                        val name = ".${File.separator}" + it.relativeTo(rootProject.projectDir).path
                        val fileDependency = LibraryDependency(name, it)
                        dependenciesCrcCache[it.absolutePath] = fileDependency
                        return@map fileDependency
                    }
                }
            }
            else -> {
                println("Jugg: unrecognized dependency type: ${dependency::class.java}")
                return emptyList()
            }
        }
    }

    private fun <T: Dependency> MutableMap<String, T>.addToResult(dependencies: List<T>) {
        dependencies.forEach {
            addToResult(it)
        }
    }

    /**
     * Add dependency to result map, will resolve version first
     */
    @Suppress("NOTHING_TO_INLINE")
    private inline fun <T: Dependency> MutableMap<String, T>.addToResult(dependency: T) {
        val result = this
        when (dependency) {
            is ModuleDependency -> {
                result[dependency.moduleName] = dependency
            }
            is LibraryDependency -> {
                val splits = dependency.name.split(':')
                if (splits.size == 3) {
                    // external module dependency
                    val uniqueName = splits[0] + ":" + splits[1] + "/" + dependency.type
                    val version = splits[2]
                    val existsDependency = result[uniqueName] as? LibraryDependency
                    if (existsDependency != null) {
                        val existsVersion = existsDependency.name.substringAfterLast(':')
                        if (VersionResolver.isNewerVersion(version, existsVersion)) {
                            if (printResolveDetail) {
                                println("Jugg: dependency resolve ${existsDependency.name} -> ${dependency.name}")
                            }
                            result[uniqueName] = dependency
                        }
                    } else {
                        result[uniqueName] = dependency
                    }
                } else {
                    // file dependency
                    result[dependency.file.absolutePath] = dependency
                }
            }
            else -> {
                result[dependency.toString()] = dependency
            }
        }
    }

    private fun getDependencies(resolvedConfiguration: Configuration, isAndroidDepend: Boolean): List<Dependency> {
        val result = mutableSetOf<Dependency>()
        // resolve project dependency here, because project dependency won't return by artifactView
        // if it's build directory is deleted
        getProjectDependencies(result, resolvedConfiguration.resolvedConfiguration.firstLevelModuleDependencies)

        val resolvedArtifacts = mutableSetOf<ResolvedArtifactResult>()
        if (isAndroidDepend) {
            val jarView = resolvedConfiguration.incoming.artifactView(SimpleArtifactFilter("android-classes"))
            resolvedArtifacts.addAll(jarView.artifacts.artifacts)
            val resView = resolvedConfiguration.incoming.artifactView(SimpleArtifactFilter("android-res"))
            resolvedArtifacts.addAll(resView.artifacts.artifacts)
            val manifestView = resolvedConfiguration.incoming.artifactView(SimpleArtifactFilter("android-manifest"))
            resolvedArtifacts.addAll(manifestView.artifacts.artifacts)
        } else {
            val jarView = resolvedConfiguration.incoming.artifactView(SimpleArtifactFilter("jar"))
            resolvedArtifacts.addAll(jarView.artifacts.artifacts)
        }

        resolvedArtifacts.forEach {
            if (it.id.componentIdentifier is ProjectComponentIdentifier) {
                return@forEach // project dependency already handled at top
            }
            val cache = dependenciesCrcCache[it.file.absolutePath]
            if (cache != null) {
                if (cache.lastModifiedTime == it.file.lastModified()) {
                    result.add(cache)
                    return@forEach
                }
            }
            val libraryName = it.id.componentIdentifier.displayName.standardLibraryName
            val libraryDependency = LibraryDependency(libraryName, it.file)
            dependenciesCrcCache[it.file.absolutePath] = libraryDependency
            result.add(libraryDependency)
        }
        return result.toList()
    }

    private fun getProjectDependencies(result: MutableSet<Dependency>, dependencies: Set<ResolvedDependency>) {
        dependencies.forEach { dependency ->
            val moduleName = dependency.moduleNameIfIsProject
            if (moduleName != null) {
                result.add(ModuleDependency(moduleName))
                getProjectDependencies(result, dependency.children)
            }
        }
    }

    private val String.standardLibraryName: String get() {
        var thirdColonIndex = -1
        var count = 0
        this.forEachIndexed { index, c ->
            if (c == ':') {
                count++
                if (count == 3) {
                    thirdColonIndex = index
                }
            }
        }
        if (thirdColonIndex > 0) {
            // e.g. com.example.library:my-library:1.0.10-SNAPSHOT:20211130.123620-1
            return this.substring(0, thirdColonIndex)
        }
        return this
    }

    private val Project.standardModuleName: String get() {
        // match with module name read from IDE
        // e.g. :libraryGroup:library1 -> libraryGroup.library1
        return path.replace(":", ".").substring(1)
    }

    private val String.standardModuleName: String get() {
        // match with module name read from IDE
        // e.g. displayName = "project :libraryGroup:library1", name = "library1"
        // then standardModuleName = "libraryGroup.library1"
        var moduleName = this
        if (moduleName.startsWith("project :")) {
            moduleName = moduleName.substring("project :".length)
        }
        moduleName = moduleName.replace(":", ".")
        return moduleName
    }

    private val ResolvedDependency.moduleNameIfIsProject: String? get() {
        if (!modulesNames.contains(moduleName)) {
            return null
        }
        if (moduleVersion != "unspecified") {
            return null
        }
        if (moduleGroup.contains('.')) {
            val splits = moduleGroup.split('.')
            if (!splits.all { modulesNames.contains(it) }) {
                return null
            }
            return splits.last() + "." + moduleName
        } else {
            if (moduleGroup != rootProject.name) {
                return null
            }
            return moduleName
        }
    }

    /**
     * filterConfigs("implementation", "implementation) = true
     * filterConfigs("debugImplementation", "implementation) = true
     * filterConfigs("testImplementation", "implementation) = false
     */
    @Suppress("NOTHING_TO_INLINE", "RedundantIf", "RedundantSuppression")
    private inline fun filterConfigs(configName: String, suffix: String): Boolean {
        if (configName.length < suffix.length) {
            return false
        }
        if (!configName.endsWith(suffix)) {
            return false
        }

        val isTestConfig = configName.substring(0, configName.length - suffix.length).endsWith("Test")
        if (isTestConfig) {
            return false
        }

        return true
    }

    private class SimpleArtifactFilter(private val artifactType: String) : Action<ArtifactView.ViewConfiguration> {
        @Suppress("ObjectLiteralToLambda")
        @Override
        override fun execute(viewConfiguration: ArtifactView.ViewConfiguration) {
            viewConfiguration.isLenient = true
            viewConfiguration.attributes(object : Action<AttributeContainer> {
                @Override
                override fun execute(attributeContainer: AttributeContainer) {
                    // `java-classes-directory` `jar`
                    // `android-classes-directory`, `android-classes-jar`
                    attributeContainer.attribute(Attribute.of("artifactType", String::class.java), artifactType)
                }
            })
        }
    }

}