package com.sickworm.intellij.jugg.gradle.script

import com.sickworm.intellij.jugg.project.data.*
import com.sickworm.intellij.jugg.project.data.Dependency
import com.sickworm.intellij.jugg.project.data.ModuleDependency
import org.gradle.api.*
import org.gradle.api.artifacts.*
import org.gradle.api.artifacts.result.ResolvedArtifactResult
import org.gradle.api.attributes.*
import java.io.File

class GradleProjectInfoReader(
    private val rootProject: Project,
    private val lastProjectInfo: JuggProjectInfoSerialize?,
) {

    private var dependenciesCache: MutableMap<String, List<LibraryDependency>> = mutableMapOf()
    private var totalReadArtifacts = 0
    private var updateArtifacts = 0

    fun getProjectInfo(): JuggProjectInfo {
        // load dependenciesCache
        TraceLogger.start("loadDependencyCache")
        val initCache: MutableMap<String, MutableList<LibraryDependency>> = mutableMapOf()
        lastProjectInfo?.dependencyList?.forEach {
            initCache.getOrPut(it.name) { mutableListOf() }.add(it)
        }
        @Suppress("UNCHECKED_CAST")
        dependenciesCache = initCache as MutableMap<String, List<LibraryDependency>>
        TraceLogger.end("loadDependencyCache")

        val modules = mutableMapOf<String, ModuleInfo>()
        rootProject.subprojects.forEach { project: Project ->
            val moduleInfo = getModuleInfo(project)
            modules[moduleInfo.name] = moduleInfo
        }

        println("totalReadArtifacts $totalReadArtifacts, updateArtifacts: $updateArtifacts")
        TraceLogger.printAllCost()

        return JuggProjectInfo(modules)
    }

    private fun getModuleInfo(project: Project): ModuleInfo {
        TraceLogger.start("getModule:${project.name}")
        TraceLogger.start("getVar")
        val moduleType = when {
            project.plugins.hasPlugin("com.android.application") -> ModuleInfo.Type.Application
            project.plugins.hasPlugin("com.android.library") -> ModuleInfo.Type.Library
            project.plugins.hasPlugin("java-library") -> ModuleInfo.Type.JavaLibrary
            else -> ModuleInfo.Type.Unknown
        }

        var moduleInfo = ModuleInfo.virtualModule.copy(
            name = project.name,
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
                if (kotlinJvmOptions == null) {
                    println("Jugg: ${project.name} has no kotlinOptions")
                }

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
                    sourceDirs = sourceDirs.filter { it.exists() }.toList(),
                    resourceDirs = resDirs.filter { it.exists() }.toList(),
                    assetsDirs = assetDirs.filter { it.exists() }.toList(),
                    manifestFile = manifestFile,
                )
            } catch (e: Throwable) {
                println("Jugg: get other info for ${project.name} failed: $e")
                printException(e)
            }
        }
        TraceLogger.end("getVar")

        TraceLogger.start("getDep")
        try {
            if (buildVariant == null && moduleType.isAndroidModule) {
                println("Jugg: buildVariant not found for ${project.name}, won't parse dependencies")
            } else {
                TraceLogger.start("getCompile")
                val dependFilterName = if (moduleType.isAndroidModule) "${buildVariant}CompileClasspath" else "compileClasspath"
                val dependencies = getDependencies(project, dependFilterName, isAndroidDepend = moduleType.isAndroidModule)
                TraceLogger.end("getCompile")

                TraceLogger.start("getRuntime")
                val runtimeFilterName = if (moduleType.isAndroidModule) "${buildVariant}RuntimeClasspath" else "runtimeClasspath"
                val runtimeDependencies = getDependencies(project, runtimeFilterName, isAndroidDepend = moduleType.isAndroidModule)
                TraceLogger.end("getRuntime")

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
        } catch (e: Throwable) {
            println("Jugg: get dependency info for ${project.name} failed: $e")
            printException(e)
        }
        TraceLogger.end("getDep")

        TraceLogger.end("getModule:${project.name}")
        return moduleInfo
    }

    private val fixedModuleNameMap: Map<String, ModuleInfo> by lazy {
        lastProjectInfo?.modules?.associate {
            it.moduleInfoExceptLibraries.name to it.moduleInfoExceptLibraries
        } ?: emptyMap()
    }

    private fun getBuildVariant(project: Project): String? {
        return fixedModuleNameMap[project.name]?.buildVariant
    }

    private fun getDependencies(project: Project, filterName: String, isAndroidDepend: Boolean): List<Dependency> {
        val result = mutableMapOf<String, Dependency>()
        fun addToResult(dependencies: List<Dependency>) {
            dependencies.forEach {
                when (it) {
                    // use to distinction
                    is ModuleDependency -> {
                        result[it.moduleName] = it
                    }
                    is LibraryDependency -> {
                        result[it.file.absolutePath] = it
                    }
                    else -> {
                        result[it.toString()] = it
                    }
                }
            }
        }

        val allNames = project.configurations.names
        val names = allNames.filter { filterConfigs(it, filterName) }

        val toResolveDependencyMap = mutableMapOf<String, org.gradle.api.artifacts.Dependency>()
        names.forEach nameForEach@{ name ->
            val configuration = project.configurations.findByName(name) ?: return@nameForEach
            val allDependencies = configuration.allDependencies
            totalReadArtifacts += allDependencies.size
            allDependencies.forEach { dependency ->
                when (dependency) {
                    is ExternalModuleDependency -> {
                        val declaration = "${dependency.group}:${dependency.name}:${dependency.version}"
                        val caches = dependenciesCache[declaration]
                        if (caches != null) {
                            addToResult(caches)
                        } else {
                            toResolveDependencyMap[declaration] = dependency
                        }
                    }
                    is ProjectDependency -> {
                        var moduleName = dependency.name
                        if (moduleName.startsWith("project :")) {
                            moduleName = moduleName.substring("project :".length)
                        }
                        moduleName = moduleName.replace(":", ".")
                        result[moduleName] = ModuleDependency(moduleName)
                    }
                    is FileCollectionDependency -> {
                        // FileCollectionDependency may have same name, so can not read in cache
                        TraceLogger.start("getFileCollection")
                        val files = (dependency as SelfResolvingDependency).resolve().toList()
                        files.forEach {
                            result[it.absolutePath] = LibraryDependency(it.relativeTo(rootProject.projectDir).path, it)
                        }
                        TraceLogger.end("getFileCollection")
                    }
                }

            }
        }

        updateArtifacts += toResolveDependencyMap.size
        TraceLogger.start("getResolve")
        toResolveDependencyMap.forEach { (declaration, dependency) ->
            val resolvedConfiguration = project.configurations.detachedConfiguration(dependency)
            val dependencies = getDependencies(resolvedConfiguration, isAndroidDepend = isAndroidDepend)
            addToResult(dependencies)
            dependenciesCache[declaration] = dependencies
        }
        TraceLogger.end("getResolve")

        return result.values.toList()
    }

    private fun getDependencies(resolvedConfiguration: Configuration, isAndroidDepend: Boolean): List<LibraryDependency> {
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

        return resolvedArtifacts.map {
            return@map LibraryDependency(it.id.componentIdentifier.displayName, it.file)
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