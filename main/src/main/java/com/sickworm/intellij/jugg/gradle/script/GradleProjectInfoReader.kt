package com.sickworm.intellij.jugg.gradle.script

import com.sickworm.intellij.jugg.project.info.*
import com.sickworm.intellij.jugg.project.info.Dependency
import com.sickworm.intellij.jugg.project.info.ModuleDependency
import org.gradle.api.*
import org.gradle.api.artifacts.*
import org.gradle.api.artifacts.component.ProjectComponentIdentifier
import org.gradle.api.artifacts.result.ResolvedArtifactResult
import org.gradle.api.attributes.*
import org.gradle.api.file.FileCollection
import org.gradle.api.tasks.bundling.Jar
import org.gradle.internal.component.local.model.OpaqueComponentArtifactIdentifier
import java.io.File

/** C/C++ header extensions accepted by a native configuration root or include root. */
private val nativeHeaderExtensions = setOf("h", "hh", "hpp", "hxx", "inc", "inl", "ipp", "tpp")

/**
 * GradleProjectInfoReader reads gradle project data.
 */
class GradleProjectInfoReader(
    private val rootProject: Project,
    private val lastProjectInfo: JuggProjectInfoSerialize?,
    /** IDE project dir, may differ from rootProject.projectDir when Gradle root is a subdirectory */
    private val ideProjectDir: File,
) {

    private var dependenciesCache: MutableMap<String, List<Dependency>> = mutableMapOf()
    private var dependenciesCrcCache: MutableMap<String, LibraryDependency> = mutableMapOf()
    private var totalReadArtifacts = 0
    private var resolveArtifacts = 0
    private var printResolveDetail = false
    private val taskGraphGroup: Map<Project, Set<String>> = try {
        rootProject.gradle.taskGraph.allTasks
            .groupBy { it.project }
            .mapValues { (_, task) -> task.map { it.name }.toSet() }
    } catch (_: IllegalStateException) {
        emptyMap()
    }

    private var modulesNames = setOf<String>()

    private var isEnableJetifier: Boolean = false

    fun getProjectInfo(includeAndroidTestSourceSet: Boolean): JuggProjectInfo {
        TraceLogger.clear()
        var jetifierReadError: String? = null
        val isEnableJetifierValue = try {
            rootProject.findProperty("android.enableJetifier")
        } catch (e: Exception) {
            jetifierReadError = e.message
            null
        }
        isEnableJetifier = isEnableJetifierValue == "true"
        println("Jugg: getProjectInfo ideProjectDir: ${ideProjectDir}, rootPath: ${rootProject.projectDir}, isEnableJetifierValue: $isEnableJetifierValue")
        if (jetifierReadError != null) {
            println("Jugg: got jetifierReadError : $jetifierReadError")
        }

        // load dependenciesCache
        // we can not use lastProjectInfo for cache because it misses the info of transitive dependencies
        TraceLogger.start("loadDependencyCrcCache")
        dependenciesCrcCache = mutableMapOf()
        lastProjectInfo?.dependencyList?.forEach {
            dependenciesCrcCache[it.file.absolutePath] = it
        }
        TraceLogger.end("loadDependencyCrcCache")

        modulesNames = rootProject.subprojects.flatMap { it.path.split(":") }.toSet()
        val modules = mutableMapOf<String, ModuleInfo>()
        rootProject.subprojects.forEach { project: Project ->
            val moduleInfo = getModuleInfo(project)
            modules[moduleInfo.name] = moduleInfo

            // Generate androidTest ModuleInfo only when the active build target includes androidTest sources.
            if (includeAndroidTestSourceSet &&
                moduleInfo.moduleType in listOf(ModuleInfo.Type.Application, ModuleInfo.Type.Library, ModuleInfo.Type.DynamicFeature)
            ) {
                try {
                    val androidExt = reflector(project.extensions.getByName("android"))
                    val sourceDirs = mutableListOf<File>()
                    androidExt["sourceSets"]?.invoke("findByName", "androidTest")?.let { atSourceSet ->
                        (atSourceSet.invoke("getJavaDirectories")?.value as? Collection<File>)
                            ?.let { sourceDirs.addAll(it) }
                        (atSourceSet.invoke("getKotlinDirectories")?.value as? Collection<File>)
                            ?.let { sourceDirs.addAll(it) }
                    }
                    val testAppId = androidExt["defaultConfig"]["testApplicationId"]?.valueString
                    val atDependencies = getDependenciesByConfig(
                        project,
                        "${moduleInfo.buildVariant}AndroidTestCompileClasspath",
                        isAndroidDepend = true,
                    )
                    val androidTestModuleInfo = buildAndroidTestModuleInfo(
                        appModuleInfo = moduleInfo,
                        sourceDirs = sourceDirs.filter { it.exists() },
                        libraryDependencies = atDependencies.filterIsInstance<LibraryDependency>(),
                        moduleDependencies = atDependencies.filterIsInstance<ModuleDependency>(),
                        testApplicationId = testAppId,
                    )
                    if (androidTestModuleInfo != null) {
                        modules[androidTestModuleInfo.name] = androidTestModuleInfo
                        println("Jugg: generated androidTest ModuleInfo for ${moduleInfo.name}: ${androidTestModuleInfo.name}")
                    }
                } catch (e: Throwable) {
                    println("Jugg: get androidTest info for ${moduleInfo.name} failed: $e")
                    printException(e)
                }
            }
        }

        println("totalReadArtifacts $totalReadArtifacts, resolveArtifacts: $resolveArtifacts")
        TraceLogger.printAllCost()

        return JuggProjectInfo(
            modules = modules,
            agpR8Classpath = null,
        )
    }

    private fun getModuleInfo(project: Project): ModuleInfo {
        TraceLogger.start("getModule:${project.standardModuleName}")
        TraceLogger.start("getVar")
        val moduleType = when {
            project.plugins.hasPlugin("com.android.application") -> ModuleInfo.Type.Application
            project.plugins.hasPlugin("com.android.library") -> ModuleInfo.Type.Library
            project.plugins.hasPlugin("java-library") -> ModuleInfo.Type.JavaLibrary
            project.plugins.hasPlugin("com.android.dynamic-feature") -> ModuleInfo.Type.DynamicFeature
            else -> ModuleInfo.Type.Unknown
        }

        var moduleInfo = ModuleInfo.virtualModule.copy(
            name = project.standardModuleName,
            moduleType = moduleType,
            moduleRootDir = project.projectDir,
            projectRootDir = ideProjectDir,
            // set defaults to non-android modules, will update later in updateVariantAndSignConfigs for android modules
            buildPathInfo = ModuleBuildPathInfo(
                ideProjectDir,
                project.projectDir,
                "debug",
                buildDirRelativePath = project.layout.buildDirectory.get().asFile.relativeTo(ideProjectDir).path
            ),
            gradleModuleName = project.name,
        )

        if (moduleType.isAndroidModule) {
            try {
                // com.android.build.gradle.AppExtension
                // com.android.build.gradle.LibraryExtension
                val androidExt = reflector(project.extensions.getByName("android"))
                val compileSdkVersion = androidExt["compileSdkVersion"]?.valueString
                val buildToolsVersion = androidExt["buildToolsVersion"]?.valueString
                // can not get it in init.gradle.kts
                // com.android.build.gradle.internal.CompileOptions
                val compileOptions = androidExt["compileOptions"]
                // com.android.build.gradle.internal.dsl.DefaultConfig
                val defaultConfig = androidExt["defaultConfig"]
                val extensions = androidExt["extensions"]
                val hasKotlinPlugin = project.hasKotlinPlugin()
                // org.jetbrains.kotlin.gradle.plugin.KaptExtension
                val kapt = reflector(project.extensions.findByName("kapt"))

                val isDynamicFeatureInstance = androidExt.value != null &&
                        androidExt.value::class.java.name.startsWith("com.android.build.gradle.internal.dsl.DynamicFeatureExtension")
                if (isDynamicFeatureInstance) {
                    // correct moduleType for dynamic feature, it happened when we change dynamic feature plugin runtime
                    moduleInfo = moduleInfo.copy(
                        moduleType = ModuleInfo.Type.DynamicFeature,
                    )
                }

                var manifestPlaceholders: Map<String, String>? = null
                val manifestValue = defaultConfig["manifestPlaceholders"]?.value as? Map<*, *>
                if (!manifestValue.isNullOrEmpty()) {
                    manifestPlaceholders = mutableMapOf()
                    manifestValue.forEach { (key, value) ->
                        manifestPlaceholders.put(key.toString(), value.toString())
                    }
                }

                moduleInfo = updateVariantAndSignConfigs(moduleInfo, project, androidExt)

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
                    val variantSourceSet = sourceSets.invoke("findByName", moduleInfo.buildVariant)
                    if (variantSourceSet != null) {
                        sourceSetsList.add(variantSourceSet)
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
                        if (it.exists()) {
                            // Simply filter non-exist variant manifest.
                            // It may have multiple manifests, and we just get one for now.
                            manifestFile = it
                        }
                    }
                }

                var kotlinPlugins: List<File>? = null
                val buildVariantCapital = moduleInfo.buildVariant.camelCompat
                val kotlinTaskName = "compile${buildVariantCapital}Kotlin"
                val kotlinTask = findKotlinTask(project, buildVariantCapital)
                if (kotlinTask != null) {
                    // before 2.0, kotlin classpath is in pluginClasspath
                    kotlinPlugins = (reflector(kotlinTask)["pluginClasspath"]?.value as? FileCollection)?.toList()
                    // compat for Kotlin 2.0 which kotlin classpath is in not pluginClasspath
                    val kotlinClasspath20 = (reflector(kotlinTask)["defaultCompilerClasspath\$kotlin_gradle_plugin_common"]?.value as? FileCollection)?.toList()
                    if (kotlinClasspath20 != null) {
                        kotlinPlugins = ((kotlinPlugins ?: emptyList()) + kotlinClasspath20).distinct()
                    }
                    val kotlinCommonSourceDirs = readKotlinCommonSourceDirs(kotlinTask)
                    val kotlinFragments = readKotlinFragments(kotlinTask)
                    sourceDirs.addAll(kotlinCommonSourceDirs)
                    sourceDirs.addAll(kotlinFragments.first.values.flatten())
                    moduleInfo = moduleInfo.copy(
                        kotlinCommonSourceDirs = kotlinCommonSourceDirs,
                        kotlinFragmentSourceDirs = kotlinFragments.first,
                        kotlinFragmentRefines = kotlinFragments.second,
                        kotlinDefaultFragmentName = kotlinFragments.third,
                    )
                } else if (hasKotlinPlugin) {
                    println("Jugg: can not find kotlin compile task for ${moduleInfo.name} by $kotlinTaskName, skip it.")
                }

                // org.jetbrains.kotlin.gradle.dsl.KotlinJvmOptions
                val kotlinJvmOptions = kotlinTask?.let { reflector(it)["kotlinOptions"] }
                    ?: if (hasKotlinPlugin) extensions?.invoke("findByName", "kotlinOptions") else null
                val kotlinJvmTarget = readKotlinJvmTarget(kotlinTask, kotlinJvmOptions)
                val kotlinFreeCompilerArgs = readKotlinFreeCompilerArgs(kotlinTask, kotlinJvmOptions)
                val kotlinPluginOptions = readKotlinPluginOptions(kotlinTask)


                val kotlinExtensions: List<File>? = project.configurations.findByName("kotlin-extension")?.files?.toList()

                @Suppress("UNCHECKED_CAST")
                moduleInfo = moduleInfo.copy(
                    compileVersion = compileSdkVersion?.substringAfter("android-"),
                    buildToolsVersion = buildToolsVersion,
                    kotlinJvmTarget = kotlinJvmTarget,
                    kotlinFreeCompilerArgs = kotlinFreeCompilerArgs,
                    kotlinPluginOptions = kotlinPluginOptions,
                    javaSourceCompatibility = compileOptions["sourceCompatibility"]?.valueString,
                    javaTargetCompatibility = compileOptions["targetCompatibility"]?.valueString,
                    manifestPlaceHolders = manifestPlaceholders,
                    sourceDirs = sourceDirs.toList(),
                    resourceDirs = resDirs.toList(),
                    assetsDirs = assetDirs.toList(),
                    manifestFile = manifestFile,
                    kaptArguments = kapt.invoke("getAdditionalArguments",
                        Reflector.Value(Project::class.java, project),
                        Reflector.Value(Any::class.java, null),
                        Reflector.Value(Any::class.java, androidExt.value)
                    )?.value as? Map<String, String>,
                    // (project.extensions.getByName("android") as com.android.build.gradle.AppExtension).defaultConfig.javaCompileOptions.annotationProcessorOptions.arguments
                    javaAnnotationProcessorOptions = defaultConfig["javaCompileOptions"]["annotationProcessorOptions"]["arguments"]?.value as? Map<String, String>,
                    applicationId = if (moduleType == ModuleInfo.Type.Application || moduleType == ModuleInfo.Type.DynamicFeature) defaultConfig["applicationId"]?.valueString else null,
                    namespace = androidExt["namespace"]?.valueString,
                    kotlinPlugins = kotlinPlugins,
                    kotlinExtensions = kotlinExtensions,
                    isUseCompose = androidExt["buildFeatures"]["compose"]?.value == true,
                    isUseViewBinding = androidExt["buildFeatures"]["viewBinding"]?.value == true,
                    isUseDataBinding = androidExt["buildFeatures"]["dataBinding"]?.value == true,
                )
            } catch (e: Throwable) {
                println("Jugg: get other info for ${project.standardModuleName} failed: $e")
                printException(e)
            }
        }
        if (!moduleType.isAndroidModule && project.plugins.hasPlugin("com.android.kotlin.multiplatform.library")) {
            val kotlinTask = findTaskByNameWithRetry(project, "compileAndroidMain")
            if (kotlinTask != null) {
                val kotlinJvmOptions = reflector(kotlinTask)["kotlinOptions"]
                moduleInfo = moduleInfo.copy(
                    kotlinJvmTarget = readKotlinJvmTarget(kotlinTask, kotlinJvmOptions),
                    kotlinFreeCompilerArgs = readKotlinFreeCompilerArgs(kotlinTask, kotlinJvmOptions),
                    kotlinPluginOptions = readKotlinPluginOptions(kotlinTask),
                )
            }
        }
        TraceLogger.end("getVar")

        val runtimeLibraryDependencies = getRuntimeLibraryDependencies(project, moduleInfo)

        TraceLogger.start("getDep")
        try {
            TraceLogger.start("getCompile")
            var dependFilterName = if (moduleType.isAndroidModule) "${moduleInfo.buildVariant}CompileClasspath" else "compileClasspath"
            if (moduleType.isAndroidModule) {
                val isValidFilterName = project.configurations.names.any { filterConfigs(it, dependFilterName) }
                if (!isValidFilterName) {
                    println("Jugg: ${project.standardModuleName} filter name($dependFilterName) is invalid, use CompileClasspath as fallback.")
                    dependFilterName = "CompileClasspath"
                }
            }
            val dependencies = getDependenciesByConfig(project, dependFilterName, isAndroidDepend = moduleType.isAndroidModule)
            TraceLogger.end("getCompile")

            val runtimeModuleDependencies = getRuntimeModuleDependencies(project, moduleInfo)

            TraceLogger.start("getAnnotation")
            val annotationProcessorDependencies = getDependenciesByConfig(project, "annotationProcessor", isAndroidDepend = false)
            TraceLogger.end("getAnnotation")

            TraceLogger.start("getKapt")
            val kaptDependencies = getDependenciesByConfig(project, "kapt", isAndroidDepend = false)
            TraceLogger.end("getKapt")

            val coreLibraryDesugaring = getDependenciesByConfig(project, "coreLibraryDesugaring", isAndroidDepend = false, isGetByNewWay = true)

            TraceLogger.start("getKsp")
            val kspDependencies = getDependenciesByConfig(project, "ksp", isAndroidDepend = false)
            TraceLogger.end("getKsp")

            moduleInfo = moduleInfo.copy(
                moduleDependencies = dependencies.filterIsInstance<ModuleDependency>(),
                runtimeModuleDependencies = runtimeModuleDependencies,
                libraryDependencies = dependencies.filterIsInstance<LibraryDependency>(),
                runtimeLibraryDependencies = runtimeLibraryDependencies,
                annotationProcessorDependencies = annotationProcessorDependencies.filterIsInstance<LibraryDependency>(),
                kaptDependencies = kaptDependencies.filterIsInstance<LibraryDependency>(),
                coreLibraryDesugaring = coreLibraryDesugaring.filterIsInstance<LibraryDependency>(),
                kspDependencies = kspDependencies.filterIsInstance<LibraryDependency>(),
            )
        } catch (e: Throwable) {
            println("Jugg: get dependency info for ${project.standardModuleName} failed: $e")
            printException(e)
        }
        TraceLogger.end("getDep")

        moduleInfo = moduleInfo.copy(
            composeResourceInfo = getComposeResourceInfo(project, moduleInfo),
            externalBuildInfos = getExternalBuildInfos(project, moduleInfo),
        )

        TraceLogger.end("getModule:${project.standardModuleName}")
        return moduleInfo
    }

    /** Reads resolved runtime library artifacts only for modules that contribute final APK contents. */
    private fun getRuntimeLibraryDependencies(project: Project, moduleInfo: ModuleInfo): List<LibraryDependency> {
        if (moduleInfo.moduleType !in listOf(ModuleInfo.Type.Application, ModuleInfo.Type.DynamicFeature)) {
            return emptyList()
        }

        var filterName = "${moduleInfo.buildVariant}RuntimeClasspath"
        if (project.configurations.names.none { filterConfigs(it, filterName) }) {
            filterName = "RuntimeClasspath"
        }
        if (project.configurations.names.none { filterConfigs(it, filterName) }) {
            throw GradleException("Jugg: runtime classpath is unavailable for ${project.standardModuleName}")
        }

        TraceLogger.start("getRuntime")
        return try {
            // Only library dependencies are consumed here, and the discarded module dependencies are
            // what forces the legacy ResolvedConfiguration walk. That walk resolves the runtime
            // artifact graph of the whole APK root and costs seconds per APK owner module.
            getDependenciesByConfig(project, filterName, isAndroidDepend = true, isNeedProjectDependencies = false)
                .filterIsInstance<LibraryDependency>()
        } finally {
            TraceLogger.end("getRuntime")
        }
    }

    /** Reads resolved runtime project components for APK ownership without rebuilding Gradle's dependency semantics. */
    private fun getRuntimeModuleDependencies(project: Project, moduleInfo: ModuleInfo): List<ModuleDependency>? {
        if (moduleInfo.moduleType !in listOf(ModuleInfo.Type.Application, ModuleInfo.Type.DynamicFeature)) {
            return null
        }

        var filterName = "${moduleInfo.buildVariant}RuntimeClasspath"
        if (project.configurations.names.none { filterConfigs(it, filterName) }) {
            filterName = "RuntimeClasspath"
        }
        val configurations = project.configurations.names
            .filter { filterConfigs(it, filterName) }
            .mapNotNull(project.configurations::findByName)
            .filter { it.isCanBeResolved }
        if (configurations.isEmpty()) {
            return null
        }

        return try {
            val result = linkedMapOf<String, ModuleDependency>()
            configurations.forEach { configuration ->
                if (configuration.allDependencies.isEmpty()) return@forEach
                val resolutionResult = configuration.incoming.resolutionResult
                resolutionResult.allComponents.forEach componentForEach@{ component ->
                    val identifier = component.id as? ProjectComponentIdentifier ?: return@componentForEach
                    if (identifier == resolutionResult.root.id) return@componentForEach
                    val moduleName = identifier.projectPath.standardModuleNameForProjectPath.ifEmpty {
                        identifier.projectName
                    }
                    if (moduleName.isEmpty()) return@componentForEach
                    result[moduleName] = ModuleDependency(moduleName)
                }
            }
            result.values.toList()
        } catch (e: Throwable) {
            println("Jugg: get runtime module dependencies for ${project.standardModuleName} failed: $e")
            printException(e)
            null
        }
    }

    /** Reads Compose resource task configuration without executing tasks. */
    private fun getComposeResourceInfo(project: Project, moduleInfo: ModuleInfo): ComposeResourceInfo? {
        if (!project.plugins.hasPlugin("org.jetbrains.compose")) return null

        var resourceDirectories = emptyList<ComposeResourceDirectory>()
        return try {
            val tasks = findComposeResourceTasks(project)
            resourceDirectories = readComposeResourceDirectories(tasks)
            if (tasks.map { it.second }.toSet() == setOf("GenerateResClassTask")) {
                return readLegacyComposeResourceInfo(tasks.single(), moduleInfo)
            }
            val requiredTaskNames = setOf(
                "XmlValuesConverterTask",
                "GenerateResClassTask",
                "GenerateResourceAccessorsTask",
                "GenerateExpectResourceCollectorsTask",
                "GenerateActualResourceCollectorsTask"
            )
            if (tasks.map { it.second }.toSet() != requiredTaskNames) {
                return unsupportedComposeResourceInfo(resourceDirectories, "Compose resource task metadata is incomplete.")
            }
            val taskSourceFiles = tasks.map { (_, _, taskClass) ->
                taskClass.protectionDomain.codeSource?.location?.toURI()?.let(::File)
            }
            if (taskSourceFiles.any { it == null }) {
                return unsupportedComposeResourceInfo(resourceDirectories, "Compose resource task code source is missing.")
            }
            val pluginJars = taskSourceFiles.filterNotNull().map(File::getCanonicalFile).distinct()
            if (pluginJars.size != 1 || !pluginJars.single().name.startsWith("compose-gradle-plugin-")) {
                return unsupportedComposeResourceInfo(resourceDirectories, "Compose resource generator metadata is inconsistent.")
            }
            val pluginJar = pluginJars.single()
            if (resourceDirectories.size != tasks.count { it.second == "XmlValuesConverterTask" }) {
                return unsupportedComposeResourceInfo(
                    resourceDirectories,
                    "Compose resource directory metadata is incomplete."
                )
            }

            val resClassTask = tasks.single { it.second == "GenerateResClassTask" }.first
            val packageName = composePropertyValue(resClassTask, "packageName") as? String
                ?: return unsupportedComposeResourceInfo(resourceDirectories, "Compose resource package metadata is missing.")
            val packagingDir = composeFileValue(resClassTask, "packagingDir")
                ?: return unsupportedComposeResourceInfo(resourceDirectories, "Compose resource packaging metadata is missing.")
            val publicResClass = composePropertyValue(resClassTask, "makeAccessorsPublic") as? Boolean
                ?: return unsupportedComposeResourceInfo(resourceDirectories, "Compose resource visibility metadata is missing.")
            val resClassName = composePropertyValue(resClassTask, "resClassName") as? String ?: "Res"
            if (composeFileValue(resClassTask, "codeDir") == null) {
                return unsupportedComposeResourceInfo(resourceDirectories, "Compose generated source metadata is missing.")
            }
            val contentHashOptions = tasks.filter { it.second == "GenerateResourceAccessorsTask" }
                .mapNotNull { (task, _, _) -> composePropertyValue(task, "disableResourceContentHashGeneration") as? Boolean }
                .distinct()
            if (contentHashOptions.size > 1) {
                return unsupportedComposeResourceInfo(resourceDirectories, "Compose content hash metadata is inconsistent.")
            }
            val generateResourceContentHash = contentHashOptions.singleOrNull()?.not() ?: false
            val accessorSourceSets = validateComposeAccessorTasks(
                tasks,
                packageName,
                packagingDir,
                publicResClass,
                resClassName,
            )
                ?: return unsupportedComposeResourceInfo(resourceDirectories, "Compose accessor task metadata is incomplete.")
            if (resourceDirectories.any { it.sourceSetName !in accessorSourceSets } ||
                !validateComposeCollectorTasks(tasks, packageName, publicResClass, resClassName)
            ) return unsupportedComposeResourceInfo(resourceDirectories, "Compose collector task metadata is incomplete.")

            val kotlinStdlib = (moduleInfo.libraryDependencies.map { it.file } + moduleInfo.kotlinPlugins.orEmpty())
                .firstOrNull { it.name.matches(Regex("kotlin-stdlib-\\d.+\\.jar")) }
                ?: return unsupportedComposeResourceInfo(
                    resourceDirectories,
                    "Kotlin standard library metadata is missing for Compose resources."
                )
            ComposeResourceInfo(
                generatorClasspath = listOf(pluginJar, kotlinStdlib),
                packageName = packageName,
                publicResClass = publicResClass,
                resourceDirectories = resourceDirectories,
                assetRelativePath = packagingDir.path,
                resClassName = resClassName,
                generateResourceContentHash = generateResourceContentHash,
            )
        } catch (e: Throwable) {
            println("Jugg: get Compose resource info for ${project.standardModuleName} failed: $e")
            unsupportedComposeResourceInfo(resourceDirectories, "Compose resource metadata could not be read: ${e.message}")
        }
    }

    private fun unsupportedComposeResourceInfo(
        resourceDirectories: List<ComposeResourceDirectory>,
        reason: String
    ) = ComposeResourceInfo(
        generatorClasspath = emptyList(),
        packageName = "",
        publicResClass = false,
        resourceDirectories = resourceDirectories,
        assetRelativePath = "",
        supportStatus = ComposeResourceSupportStatus.Unsupported,
        unsupportedReason = reason
    )

    private fun readLegacyComposeResourceInfo(
        taskInfo: Triple<Task, String, Class<*>>,
        moduleInfo: ModuleInfo,
    ): ComposeResourceInfo {
        val task = taskInfo.first
        val resourceDirectory = composeFileValue(task, "resDir")
            ?: return unsupportedComposeResourceInfo(emptyList(), "Legacy Compose resource directory metadata is missing.")
        val directories = listOf(ComposeResourceDirectory("commonMain", resourceDirectory))
        val packageName = composePropertyValue(task, "packageName") as? String
            ?: return unsupportedComposeResourceInfo(directories, "Compose resource package metadata is missing.")
        if (composePropertyValue(task, "shouldGenerateResClass") as? Boolean != true ||
            composeFileValue(task, "codeDir") == null
        ) return unsupportedComposeResourceInfo(directories, "Legacy Compose generator metadata is incomplete.")
        val pluginJar = taskInfo.third.protectionDomain.codeSource?.location?.toURI()?.let(::File)
            ?.takeIf { it.name.startsWith("compose-gradle-plugin-") }
            ?: return unsupportedComposeResourceInfo(directories, "Compose resource task code source is missing.")
        val kotlinStdlib = (moduleInfo.libraryDependencies.map { it.file } + moduleInfo.kotlinPlugins.orEmpty())
            .firstOrNull { it.name.matches(Regex("kotlin-stdlib-\\d.+\\.jar")) }
            ?: return unsupportedComposeResourceInfo(directories, "Kotlin standard library metadata is missing for Compose resources.")
        return ComposeResourceInfo(
            generatorClasspath = listOf(pluginJar, kotlinStdlib),
            packageName = packageName,
            publicResClass = false,
            resourceDirectories = directories,
            assetRelativePath = "",
            usesLegacyGenerator = true,
        )
    }

    private fun findComposeResourceTasks(project: Project): List<Triple<Task, String, Class<*>>> {
        val taskNames = setOf(
            "XmlValuesConverterTask",
            "GenerateResClassTask",
            "GenerateResourceAccessorsTask",
            "GenerateExpectResourceCollectorsTask",
            "GenerateActualResourceCollectorsTask",
        )
        val tasks = project.tasks.mapNotNull { task ->
            var taskClass: Class<*>? = task.javaClass
            while (taskClass != null && taskClass.simpleName !in taskNames) {
                taskClass = taskClass.superclass
            }
            taskClass?.let { Triple(task, it.simpleName, it) }
        }
        return tasks
    }

    private fun readComposeResourceDirectories(
        tasks: List<Triple<Task, String, Class<*>>>
    ): List<ComposeResourceDirectory> {
        return tasks.filter { it.second == "XmlValuesConverterTask" }.mapNotNull { (task, _, _) ->
            ComposeResourceDirectory(
                sourceSetName = composePropertyValue(task, "fileSuffix") as? String ?: return@mapNotNull null,
                directory = composeFileValue(task, "originalResourcesDir") ?: return@mapNotNull null
            )
        }
    }

    private fun validateComposeAccessorTasks(
        tasks: List<Triple<Task, String, Class<*>>>,
        packageName: String,
        packagingDir: File,
        publicResClass: Boolean,
        resClassName: String,
    ): Set<String>? {
        return tasks.filter { it.second == "GenerateResourceAccessorsTask" }.map { (task, _, _) ->
            if (composePropertyValue(task, "packageName") as? String != packageName ||
                composeFileValue(task, "packagingDir")?.path != packagingDir.path ||
                composePropertyValue(task, "makeAccessorsPublic") as? Boolean != publicResClass ||
                (composePropertyValue(task, "resClassName") as? String ?: "Res") != resClassName ||
                composeFileValue(task, "codeDir") == null
            ) return null
            composePropertyValue(task, "sourceSetName") as? String ?: return null
        }.toSet()
    }

    private fun validateComposeCollectorTasks(
        tasks: List<Triple<Task, String, Class<*>>>,
        packageName: String,
        publicResClass: Boolean,
        resClassName: String,
    ): Boolean {
        return tasks.filter { it.second.endsWith("ResourceCollectorsTask") }.all { (task, _, _) ->
            composePropertyValue(task, "packageName") as? String == packageName &&
                composePropertyValue(task, "makeAccessorsPublic") as? Boolean == publicResClass &&
                (composePropertyValue(task, "resClassName") as? String ?: "Res") == resClassName &&
                composeFileValue(task, "codeDir") != null
        }
    }

    private fun composePropertyValue(task: Task, name: String): Any? {
        return reflector(task)[name]?.invoke("getOrNull")?.value
    }

    private fun composeFileValue(task: Task, name: String): File? {
        val value = composePropertyValue(task, name) ?: return null
        return value as? File ?: reflector(value)["asFile"]?.value as? File
    }

    private fun updateVariantAndSignConfigs(moduleInfo: ModuleInfo, project: Project, androidExt: Reflector): ModuleInfo {
        val variants = mutableListOf<Variant>()
        var signingConfigs: List<SigningConfig>? = null
        val isApplication = moduleInfo.moduleType == ModuleInfo.Type.Application
        val isDynamicFeature = moduleInfo.moduleType == ModuleInfo.Type.DynamicFeature
        if (isApplication) {
            signingConfigs = mutableListOf()
            // com.android.build.gradle.internal.dsl.BaseAppModuleExtension -> AppExtension ->
            // com.android.build.gradle.AbstractAppExtension.applicationVariants
            (androidExt["applicationVariants"]?.value as? Collection<*>)?.mapNotNull { obj ->
                // com.android.build.gradle.api.ApplicationVariant
                val variant = reflector(obj)
                variants.add(Variant(
                    name = variant["name"]?.valueString ?: return@mapNotNull null,
                    signingConfigName = variant["signingConfig"]["name"]?.valueString,
                    minSdkVersion = variant["mergedFlavor"]["minSdkVersion"]["apiLevel"]?.valueString,
                    minifyEnabled = readVariantMinifyEnabled(variant),
                ))
            }

            // com.android.build.gradle.internal.dsl.BaseAppModuleExtension.signingConfigs
            (androidExt["signingConfigs"]?.value as? Collection<*>)?.mapNotNull { obj ->
                // com.android.builder.model.SigningConfig
                // com.android.build.gradle.internal.api.ReadOnlySigningConfig
                val signingConfig = reflector(obj)
                signingConfigs.add(SigningConfig(
                    signingConfig["name"]?.valueString ?: return@mapNotNull null,
                    signingConfig["storeFile"]?.value as? File,
                    signingConfig["storePassword"]?.valueString,
                    signingConfig["keyAlias"]?.valueString,
                    signingConfig["keyPassword"]?.valueString,
                    signingConfig["storeType"]?.valueString,
                    (signingConfig["isV1SigningEnabled"]?.value == true) || (signingConfig["enableV1Signing"]?.value == true),
                    (signingConfig["isV2SigningEnabled"]?.value == true) || (signingConfig["enableV2Signing"]?.value == true),
                    signingConfig["enableV3Signing"]?.value == true,
                    signingConfig["enableV4Signing"]?.value == true,
                    signingConfig["isSigningReady"]?.value == true,
                ))
            }
        } else if (isDynamicFeature) {
            // com.android.build.gradle.internal.dsl.DynamicFeatureExtension -> AppExtension ->
            // com.android.build.gradle.AbstractAppExtension.applicationVariants
            (androidExt["applicationVariants"]?.value as? Collection<*>)?.mapNotNull { obj ->
                // com.android.build.gradle.api.ApplicationVariant
                val variant = reflector(obj)
                variants.add(
                    Variant(
                        name = variant["name"]?.valueString ?: return@mapNotNull null,
                        signingConfigName = variant["signingConfig"]["name"]?.valueString,
                        minSdkVersion = variant["mergedFlavor"]["minSdkVersion"]["apiLevel"]?.valueString,
                        minifyEnabled = readVariantMinifyEnabled(variant),
                    )
                )
            }
        } else {
            // com.android.build.gradle.api.LibraryVariant
            (androidExt["libraryVariants"]?.value as? Collection<*>)?.forEach { obj ->
                val variant = reflector(obj)
                variants.add(Variant(
                    name = variant["name"]?.valueString ?: return@forEach,
                    signingConfigName = null,
                    minSdkVersion = variant["mergedFlavor"]["minSdkVersion"]["apiLevel"]?.valueString,
                    minifyEnabled = readVariantMinifyEnabled(variant),
                ))
            }
        }

        if (variants.isEmpty()) {
            variants.addAll(getCollectedAndroidVariants(rootProject, project))
        }

        val buildVariant = guessBuildVariant(project, variants) ?: "debug"

        return moduleInfo.copy(
            buildVariant = buildVariant,
            minSdkVersion = variants.firstOrNull { it.name == buildVariant }?.minSdkVersion
                ?: androidExt["defaultConfig"]["minSdkVersion"]["apiLevel"]?.valueString,
            variants = variants,
            signingConfigs = signingConfigs,
            buildPathInfo = ModuleBuildPathInfo(
                ideProjectDir,
                project.projectDir,
                buildVariant,
                buildDirRelativePath = project.layout.buildDirectory.get().asFile.relativeTo(ideProjectDir).path
            ),
        )
    }

    /**
     * Reads the resolved minify flag of one Android variant. The legacy variant API does not expose
     * `isMinifyEnabled`, so the variant build type model is the fallback source. Both reads are
     * best-effort and return null when unavailable, which keeps old snapshots at "unknown".
     */
    private fun readVariantMinifyEnabled(variant: Reflector): Boolean? {
        (variant["isMinifyEnabled"]?.value as? Boolean)?.let { return it }
        return variant["buildType"]["isMinifyEnabled"]?.value as? Boolean
    }

    private fun guessBuildVariant(project: Project, variants: List<Variant>): String? {
        val taskNames = taskGraphGroup[project] ?: run {
            println("Jugg: ${project.standardModuleName} task graph not found, build variant may not correct. " +
                    "Most likely the module is not in compilation")
            emptySet()
        }
        val startTaskNames: List<String>? = project.gradle.startParameter.taskRequests.getOrNull(0)?.args
        return guessBuildVariant(project.standardModuleName, variants, taskNames, startTaskNames)
    }

    private fun getDependenciesByConfig(project: Project, filterName: String, isAndroidDepend: Boolean, isNeedResolve: Boolean = true, isGetByNewWay: Boolean = false,
                                        isNeedProjectDependencies: Boolean = true): List<Dependency> {
        val result = mutableMapOf<String, Dependency>()
        val allNames = project.configurations.names
        val names = allNames.filter { filterConfigs(it, filterName) }

        names.forEach nameForEach@{ name ->
            val configuration = project.configurations.findByName(name) ?: return@nameForEach
            val allDependencies = configuration.allDependencies
            if (allDependencies.isEmpty()) {
                return@nameForEach
            }
            if (configuration.isCanBeResolved) {
                val subResult = if (isGetByNewWay) {
                    doGetDependenciesNew(configuration)
                } else {
                    doGetDependencies(configuration, isAndroidDepend, isNeedProjectDependencies)
                }
                totalReadArtifacts += subResult.size
                resolveArtifacts += configuration.allDependencies.size
                result.addToResult(subResult)
            } else {
                allDependencies.forEach { dependencyDeclaration: org.gradle.api.artifacts.Dependency ->
                    val dependencies: List<Dependency> = getDependenciesWithoutResolved(project, dependencyDeclaration, isAndroidDepend, isNeedResolve)
                    totalReadArtifacts += dependencies.size
                    result.addToResult(dependencies)
                }
            }
        }

        return result.values.toMutableList()
    }

    private fun findKotlinTask(project: Project, buildVariantCapital: String): Any? {
        val kotlinTaskName = "compile${buildVariantCapital}Kotlin"
        val kotlinTaskNameKmm = "compile${buildVariantCapital}KotlinAndroid"
        return findTaskByNameWithRetry(project, kotlinTaskName)
            ?: findTaskByNameWithRetry(project, kotlinTaskNameKmm)
    }

    /**
     * Reads the external build scope and outputs for one module without resolving full project data.
     */
    fun getExternalBuildInfos(project: Project, moduleInfo: ModuleInfo): List<ExternalBuildInfo> {
        val variantCapital = moduleInfo.buildVariant.camelCompat
        val result = mutableListOf<ExternalBuildInfo>()
        val flutterTask = findTaskByNameWithRetry(project, "compileFlutterBuild$variantCapital") as? Task
        val flutterSourceDir = readConfiguredSourceRoots(readProperty(flutterTask, "sourceDir")).firstOrNull()
            ?: readProjectFile(project, readProperty(project.extensions.findByName("flutter"), "source"))
        if (flutterSourceDir != null) {
            val assetsOutputDir = readConfiguredSourceRoots(readProperty(flutterTask, "outputDirectory")).firstOrNull()
                ?: readConfiguredSourceRoots(readProperty(flutterTask, "intermediateDir")).firstOrNull()
            val nativeOutput = readFlutterNativeOutput(project, variantCapital, flutterTask)
            val reason = when {
                flutterTask == null -> "Flutter compile task for $variantCapital was not found"
                assetsOutputDir == null -> "Flutter task ${flutterTask.path} output directory was not found"
                else -> nativeOutput.reason
            }
            val flutterInputs = readFlutterInputs(project, moduleInfo, flutterTask, flutterSourceDir)
            logExternalBuildInputNotes(project, "Flutter", moduleInfo, flutterInputs.notes)
            result.add(ExternalBuildInfo(
                type = ExternalBuildType.Flutter,
                inputDirs = compactInputDirs(flutterInputs.inputDirs),
                taskPath = nativeOutput.task?.path,
                assetsOutputDir = assetsOutputDir?.absoluteFile?.normalize(),
                nativeOutput = nativeOutput.output?.absoluteFile?.normalize(),
                unsupportedReason = reason,
                configFiles = flutterInputs.configFiles,
                excludedDirs = flutterInputs.excludedDirs,
            ))
        }

        val nativeTask = findTaskByNameWithRetry(project, "merge${variantCapital}NativeLibs") as? Task
        val cppConfig = readCppBuildConfig(project)
        if (cppConfig.sourceDirs.isNotEmpty()) {
            val nativeOutput = readConfiguredSourceRoots(readProperty(nativeTask, "outputDir")).firstOrNull()
                ?: readConfiguredSourceRoots(readProperty(nativeTask, "outputDirectory")).firstOrNull()
            val reason = when {
                nativeTask == null -> "Native merge task for $variantCapital was not found"
                nativeOutput == null -> "Native task ${nativeTask.path} output directory was not found"
                else -> null
            }
            val nativeInputs = readNativeInputs(project, cppConfig, moduleInfo)
            logExternalBuildInputNotes(project, "C++", moduleInfo, nativeInputs.notes)
            result.add(ExternalBuildInfo(
                type = ExternalBuildType.Cpp,
                inputDirs = compactInputDirs(nativeInputs.inputDirs),
                taskPath = nativeTask?.path,
                assetsOutputDir = null,
                nativeOutput = nativeOutput?.absoluteFile?.normalize(),
                unsupportedReason = reason,
                configFiles = cppConfig.configFiles,
                excludedDirs = nativeInputs.excludedDirs,
            ))
        }
        return result
    }

    /**
     * Reads the Flutter package roots and resource directories. A package root only accepts Dart
     * sources, and its `pubspec.yaml`/`l10n.yaml` may declare additional resource directories.
     * Resource files are accepted only through those declarations or through a Flutter task input
     * strictly below a package root, so a package root never widens to arbitrary files. The Flutter
     * root itself is always watched, so new assets and source directories do not depend on a
     * refreshed exact file list.
     */
    private fun readFlutterInputs(
        project: Project,
        moduleInfo: ModuleInfo,
        flutterTask: Task?,
        flutterSourceDir: File,
    ): FlutterBuildInputs {
        val excludedDirs = readFlutterExcludedDirs(project, moduleInfo, flutterSourceDir)
        val configFiles = linkedSetOf<File>()
        val inputDirs = mutableListOf<ExternalBuildInputDir>()
        val notes = ExternalBuildInputNotes()
        val taskInputs = runCatching {
            readInputFiles(readProperty(flutterTask, "sourceFiles"))
        }.getOrDefault(emptyList())
            .map { it.absoluteFile.normalize() }
            .filter { file -> excludedDirs.none { file.isUnderPath(it) } }

        val flutterRoot = flutterSourceDir.absoluteFile.normalize()
        val packageRoots = linkedSetOf<File>()
        packageRoots.add(flutterRoot)
        taskInputs.filter { it.isFile && it.isDartFile() }.forEach { file ->
            // A local path package is only located from the Dart files the Flutter task exposes.
            file.parentFile?.let { addInputDir(inputDirs, it, ExternalBuildInputFilterRule.Dart) }
            findPubPackageRoot(file, excludedDirs)?.let(packageRoots::add)
        }

        taskInputs.forEach { file ->
            when {
                file.isDirectory -> addTaskInputDirectory(inputDirs, file, packageRoots, notes)
                file.name == "pubspec.yaml" || file.name == "pubspec.lock" || file.name == "l10n.yaml" ->
                    configFiles.add(file)
                file.isDartFile() -> Unit
                else -> addTaskInputFile(inputDirs, file, packageRoots, notes)
            }
        }

        packageRoots.sortedBy { it.path }.forEach { packageRoot ->
            addInputDir(inputDirs, packageRoot, ExternalBuildInputFilterRule.Dart)
            readFlutterPackageConfig(packageRoot, excludedDirs, configFiles, inputDirs, notes)
        }
        if (taskInputs.isEmpty()) {
            println("Jugg: Flutter task inputs are unavailable for $flutterSourceDir, " +
                    "only the source root is watched")
        }
        return FlutterBuildInputs(configFiles.toList(), inputDirs.toList(), excludedDirs, notes)
    }

    /**
     * Maps one Flutter task input directory to a rule. Only the package root itself is a Dart root:
     * a directory strictly below it may hold resources, which the task exposes as a whole.
     */
    private fun addTaskInputDirectory(
        inputs: MutableList<ExternalBuildInputDir>,
        directory: File,
        packageRoots: Set<File>,
        notes: ExternalBuildInputNotes,
    ) {
        when {
            packageRoots.any { it.path == directory.path } ->
                addInputDir(inputs, directory, ExternalBuildInputFilterRule.Dart)
            packageRoots.any { directory.isUnderPath(it) } ->
                addInputDir(inputs, directory, ExternalBuildInputFilterRule.FlutterAsset)
            else -> notes.ignoredInputs.add(directory)
        }
    }

    /**
     * Maps one non Dart Flutter task input file to a rule. A resource in the package root itself
     * would widen the whole root to arbitrary files, so only resources below a package root are
     * accepted; the rest is ignored instead of degrading the external build.
     */
    private fun addTaskInputFile(
        inputs: MutableList<ExternalBuildInputDir>,
        file: File,
        packageRoots: Set<File>,
        notes: ExternalBuildInputNotes,
    ) {
        val packageRoot = packageRoots.firstOrNull { file.isUnderPath(it) }
        if (packageRoot == null || packageRoot.path == file.path) {
            notes.ignoredInputs.add(file)
            return
        }
        file.parentFile?.let { addInputDir(inputs, it, ExternalBuildInputFilterRule.FlutterAsset) }
    }

    /**
     * Reads the declared resource directories of one Flutter package. Only directories of
     * `flutter.assets` and the `l10n.yaml` `arb-dir` are used; single file assets, fonts and shaders
     * keep relying on the Flutter task inputs, and `pubspec.yaml` itself triggers the build.
     */
    private fun readFlutterPackageConfig(
        packageRoot: File,
        excludedDirs: List<File>,
        configFiles: MutableSet<File>,
        inputDirs: MutableList<ExternalBuildInputDir>,
        notes: ExternalBuildInputNotes,
    ) {
        val pubspec = File(packageRoot, "pubspec.yaml")
        if (pubspec.isFile) {
            configFiles.add(pubspec.absoluteFile.normalize())
            readPubspecAssetDirectories(pubspec, notes)?.forEach { declaration ->
                resolveFlutterResourceDirectory(declaration, packageRoot, excludedDirs)
                    ?.let { addInputDir(inputDirs, it, ExternalBuildInputFilterRule.FlutterAsset) }
            }
        }
        val lockFile = File(packageRoot, "pubspec.lock")
        if (lockFile.isFile) {
            configFiles.add(lockFile.absoluteFile.normalize())
        }
        val l10n = File(packageRoot, "l10n.yaml")
        if (l10n.isFile) {
            configFiles.add(l10n.absoluteFile.normalize())
            // `arb-dir` always names a directory, so it does not need a trailing separator.
            readL10nArbDirectory(l10n, notes)?.let { declaration ->
                resolveFlutterResourceDirectory(declaration, packageRoot, excludedDirs, isDirectoryDeclaration = true)
                    ?.let { addInputDir(inputDirs, it, ExternalBuildInputFilterRule.FlutterAsset) }
            }
        }
    }

    /**
     * Reads the asset list of `pubspec.yaml`. A minimal indentation scan replaces a YAML parser:
     * only `flutter.assets` list entries and their map `path` form are recognized, and unreadable
     * content yields null so the Flutter task inputs stay the authoritative resource source.
     */
    private fun readPubspecAssetDirectories(pubspec: File, notes: ExternalBuildInputNotes): List<String>? {
        val lines = readYamlLines(pubspec, notes) ?: return null
        val flutterIndex = lines.indexOfFirst { it.indent == 0 && it.content == "flutter:" }
        if (flutterIndex < 0) return emptyList()
        val entries = mutableListOf<String>()
        var assetsIndent = -1
        var index = flutterIndex + 1
        while (index < lines.size) {
            val line = lines[index]
            if (line.indent == 0) break
            if (assetsIndent < 0) {
                if (line.content == "assets:") assetsIndent = line.indent
                index++
                continue
            }
            if (line.indent <= assetsIndent) break
            if (line.content.startsWith("- ")) {
                readYamlScalar(line.content.removePrefix("- ").removePrefix("path:"))?.let(entries::add)
            }
            index++
        }
        return entries
    }

    /** Reads the `arb-dir` declaration of one `l10n.yaml`, or null when it is not declared. */
    private fun readL10nArbDirectory(l10n: File, notes: ExternalBuildInputNotes): String? {
        val lines = readYamlLines(l10n, notes) ?: return null
        val line = lines.firstOrNull { it.indent == 0 && it.content.startsWith("arb-dir:") } ?: return null
        return readYamlScalar(line.content.removePrefix("arb-dir:"))
    }

    /**
     * Resolves one declared resource directory below its package root. The declaration must stay
     * inside the package root, must not name an excluded directory and must not reach the directory
     * through a symbolic link. A directory is declared either by a trailing separator, which also
     * covers a directory that does not exist yet, or by its current state on disk.
     */
    private fun resolveFlutterResourceDirectory(
        declaration: String,
        packageRoot: File,
        excludedDirs: List<File>,
        isDirectoryDeclaration: Boolean = false,
    ): File? {
        if (declaration.isEmpty()) return null
        if (File(declaration).isAbsolute) return null
        val isDeclaredDirectory = isDirectoryDeclaration ||
                declaration.endsWith("/") || declaration.endsWith(File.separator)
        val directory = File(packageRoot, declaration).absoluteFile.normalize()
        val isInsidePackage = directory.path != packageRoot.path && directory.isUnderPath(packageRoot)
        if (!isInsidePackage || (!isDeclaredDirectory && !directory.isDirectory)) return null
        if (excludedDirs.any { directory.isUnderPath(it) }) return null
        if (hasSymbolicLinkDirectory(packageRoot, directory)) return null
        return directory
    }

    /** Whether any directory between [root] and [directory] is a symbolic link. */
    private fun hasSymbolicLinkDirectory(root: File, directory: File): Boolean {
        var current: File? = directory
        while (current != null && current.path != root.path) {
            if (java.nio.file.Files.isSymbolicLink(current.toPath())) return true
            current = current.parentFile
        }
        return false
    }

    /** One non-empty YAML line with its indentation and its content without inline comments. */
    private class YamlLine(val indent: Int, val content: String)

    private fun readYamlLines(file: File, notes: ExternalBuildInputNotes): List<YamlLine>? {
        return try {
            file.readLines().mapNotNull { raw ->
                val content = removeYamlComment(raw).trimEnd()
                if (content.isBlank()) return@mapNotNull null
                YamlLine(content.length - content.trimStart().length, content.trimStart())
            }
        } catch (e: Throwable) {
            notes.unreadableConfigs.add(file)
            println("Jugg: read $file failed: $e")
            null
        }
    }

    /** Reads one YAML scalar, dropping an optional inline comment and optional quotes. */
    private fun readYamlScalar(value: String): String? {
        val scalar = removeYamlComment(value).trim()
        if (scalar.length >= 2 && scalar.first() == scalar.last() &&
                (scalar.first() == '"' || scalar.first() == '\'')) {
            return scalar.substring(1, scalar.length - 1)
        }
        return scalar.ifEmpty { null }
    }

    /** Removes a trailing `#` comment that is outside quotes. */
    private fun removeYamlComment(value: String): String {
        var quote: Char? = null
        value.forEachIndexed { index, char ->
            when {
                quote != null -> if (char == quote) quote = null
                char == '"' || char == '\'' -> quote = char
                char == '#' -> return value.substring(0, index)
            }
        }
        return value
    }

    private fun File.isDartFile(): Boolean = extension.equals("dart", ignoreCase = true)

    /** Generated output and cache roots of one Flutter build; the Flutter SDK and pub cache included. */
    private fun readFlutterExcludedDirs(
        project: Project,
        moduleInfo: ModuleInfo,
        flutterSourceDir: File,
    ): List<File> {
        val result = linkedSetOf<File>()
        result.add(File(flutterSourceDir, ".dart_tool"))
        result.add(File(moduleInfo.moduleRootDir, "build"))
        result.add(moduleInfo.buildPathInfo.buildDir)
        readFlutterSdkRoot(project)?.let { result.add(it) }
        readPubCacheRoot()?.let { result.add(it) }
        return result.map { it.absoluteFile.normalize() }.toList()
    }

    private fun readFlutterSdkRoot(project: Project): File? {
        val flutterExtension = project.extensions.findByName("flutter")
        val values = listOf(
            readProperty(flutterExtension, "sdk"),
            readProperty(flutterExtension, "flutterRoot"),
            project.findProperty("flutter.sdk"),
            readLocalProperty(project, "flutter.sdk"),
            System.getenv("FLUTTER_ROOT"),
        )
        return values.mapNotNull { readProjectFile(project, it) }
            .firstOrNull { it.isDirectory }
    }

    private fun readPubCacheRoot(): File? {
        val fromEnv = System.getenv("PUB_CACHE")
        if (fromEnv != null && fromEnv.isNotEmpty()) {
            return File(fromEnv)
        }
        val home = System.getProperty("user.home") ?: return null
        return File(home, ".pub-cache")
    }

    private fun readLocalProperty(project: Project, key: String): String? {
        val candidates = listOf(project.rootProject.file("local.properties"), project.file("local.properties"))
        candidates.forEach { file ->
            if (!file.isFile) return@forEach
            try {
                val properties = java.util.Properties()
                file.inputStream().use { properties.load(it) }
                val value = properties.getProperty(key)
                if (value != null && value.isNotEmpty()) return value
            } catch (e: Throwable) {
                println("Jugg: read $file failed: $e")
            }
        }
        return null
    }

    /** Walks up to the pub package owning one out-of-project Dart file, or null when there is none. */
    private fun findPubPackageRoot(file: File, excludedDirs: List<File>): File? {
        var directory = file.parentFile
        var depth = 0
        while (directory != null && depth < 10) {
            if (excludedDirs.any { directory.isUnderPath(it) }) return null
            if (File(directory, "pubspec.yaml").isFile) return directory.absoluteFile.normalize()
            directory = directory.parentFile
            depth++
        }
        return null
    }

    /** Native configuration inputs and structured native metadata inputs of one module. */
    private fun readCppBuildConfig(project: Project): CppBuildConfig {
        val empty = CppBuildConfig(emptyList(), emptyList(), emptyList())
        val androidExt = try {
            reflector(project.extensions.getByName("android"))
        } catch (_: Throwable) {
            return empty
        }
        val externalNativeBuild = androidExt["externalNativeBuild"] ?: return empty
        val sourceDirs = linkedSetOf<File>()
        val configFiles = linkedSetOf<File>()
        val stagingDirs = linkedSetOf<File>()
        listOf("cmake", "ndkBuild").forEach { builder ->
            val options = externalNativeBuild[builder] ?: return@forEach
            val buildFile = readProjectFile(project, options["path"]?.value) ?: return@forEach
            val buildDir = buildFile.parentFile ?: return@forEach
            sourceDirs.add(buildDir.absoluteFile.normalize())
            configFiles.add(buildFile.absoluteFile.normalize())
            if (builder == "cmake") {
                configFiles.addAll(readCmakeIncludes(buildDir))
            } else {
                val applicationMk = File(buildDir, "Application.mk")
                if (applicationMk.isFile) configFiles.add(applicationMk.absoluteFile.normalize())
            }
            readProjectFile(project, options["buildStagingDirectory"]?.value)
                ?.let { stagingDirs.add(it.absoluteFile.normalize()) }
        }
        return CppBuildConfig(sourceDirs.toList(), configFiles.toList(), stagingDirs.toList())
    }

    /** Project CMake modules included from the CMakeLists directory, never parsed as a language. */
    private fun readCmakeIncludes(cmakeDir: File): List<File> {
        val skippedDirectoryNames = setOf(".git", ".cxx", ".externalNativeBuild", ".dart_tool", "build", "node_modules")
        val result = mutableListOf<File>()
        fun visit(directory: File, depth: Int) {
            if (depth > 4) return
            directory.listFiles()?.forEach { child ->
                when {
                    child.isDirectory && child.name !in skippedDirectoryNames -> visit(child, depth + 1)
                    child.isFile && child.name.endsWith(".cmake") -> result.add(child.absoluteFile.normalize())
                }
            }
        }
        visit(cmakeDir, 0)
        return result
    }

    /**
     * Reads the native input directories of one module. A configuration root accepts plain C/C++
     * sources and headers, so it also finds files the current metadata does not list yet. A concrete
     * source directory confirmed by the metadata accepts arbitrary non-hidden files, which covers
     * co-located headers and non-standard generated inputs. Include roots and metadata header
     * sources stay on header-only matching, so a wide shared include root can not widen to
     * arbitrary files. Metadata inputs a configuration root already covers are ignored without
     * degrading the external build.
     */
    private fun readNativeInputs(project: Project, cppConfig: CppBuildConfig, moduleInfo: ModuleInfo): NativeInputs {
        val searchRoots = linkedSetOf<File>()
        searchRoots.addAll(cppConfig.stagingDirs)
        // Fixed toolchain staging directory names; variant and ABI directories stay discovered, not hardcoded.
        searchRoots.add(File(moduleInfo.moduleRootDir, ".cxx"))
        searchRoots.add(File(moduleInfo.moduleRootDir, ".externalNativeBuild"))
        searchRoots.add(File(moduleInfo.buildPathInfo.buildDir, "intermediates/cxx"))
        val excludedDirs = linkedSetOf<File>()
        excludedDirs.add(File(moduleInfo.moduleRootDir, "build"))
        excludedDirs.add(moduleInfo.buildPathInfo.buildDir)
        excludedDirs.add(File(moduleInfo.moduleRootDir, ".cxx"))
        excludedDirs.add(File(moduleInfo.moduleRootDir, ".externalNativeBuild"))
        cppConfig.stagingDirs.forEach { excludedDirs.add(it) }
        excludedDirs.addAll(readNativeToolchainDirs(project))
        val excluded = excludedDirs.map { it.absoluteFile.normalize() }

        val metadata = NativeBuildMetadataReader.read(moduleInfo.buildVariant, searchRoots.toList())
        val configRoots = cppConfig.sourceDirs.map { it.absoluteFile.normalize() }
        val inputDirs = mutableListOf<ExternalBuildInputDir>()
        val notes = ExternalBuildInputNotes()
        configRoots.forEach { root ->
            addInputDir(inputDirs, root, ExternalBuildInputFilterRule.CppSource, ExternalBuildInputFilterRule.CppHeader)
        }
        metadata.sourceFiles.map { it.absoluteFile.normalize() }
            .filter { source -> excluded.none { source.isUnderPath(it) } }
            .forEach { source ->
                val parent = source.parentFile ?: return@forEach
                if (configRoots.any { it.path == parent.path }) {
                    // The configuration root already covers this source; metadata can not widen it.
                    notes.ignoredInputs.add(source)
                    return@forEach
                }
                val rule = if (source.isNativeHeaderFile()) {
                    ExternalBuildInputFilterRule.CppHeader
                } else {
                    ExternalBuildInputFilterRule.NativeDirectory
                }
                addInputDir(inputDirs, parent, rule)
            }
        metadata.includeDirs.map { it.absoluteFile.normalize() }
            .filter { include -> excluded.none { include.isUnderPath(it) } }
            .forEach { include -> addInputDir(inputDirs, include, ExternalBuildInputFilterRule.CppHeader) }
        return NativeInputs(inputDirs, excluded, notes)
    }

    /**
     * Reads the toolchain roots that never hold user C/C++ sources: the Gradle cache, the Android
     * SDK, its CMake toolchain and the NDK. Path resolution is best-effort, because a missing
     * exclusion only widens matching inside an include root that stays limited to C++ headers.
     */
    private fun readNativeToolchainDirs(project: Project): List<File> {
        val result = linkedSetOf<File>()
        result.add(project.gradle.gradleUserHomeDir)
        val androidExt = try {
            reflector(project.extensions.getByName("android"))
        } catch (_: Throwable) {
            null
        }
        val sdkDir = readProjectFile(project, androidExt?.get("sdkDirectory")?.value)
            ?: readLocalProperty(project, "sdk.dir")?.let(::File)
            ?: System.getenv("ANDROID_HOME")?.takeIf { it.isNotEmpty() }?.let(::File)
            ?: System.getenv("ANDROID_SDK_ROOT")?.takeIf { it.isNotEmpty() }?.let(::File)
        sdkDir?.let { sdk ->
            result.add(sdk)
            result.add(File(sdk, "cmake"))
            result.add(File(sdk, "ndk"))
        }
        val ndkDir = readProjectFile(project, androidExt?.get("ndkDirectory")?.value)
            ?: readLocalProperty(project, "ndk.dir")?.let(::File)
        ndkDir?.let(result::add)
        return result.map { it.absoluteFile.normalize() }
    }

    /**
     * Reports the inputs that this collection round did not monitor and the configuration files
     * whose resource declarations could not be read. Both are auxiliary information: they never
     * degrade the external build, never reach the user output and are never stored in project info.
     */
    private fun logExternalBuildInputNotes(
        project: Project,
        type: String,
        moduleInfo: ModuleInfo,
        notes: ExternalBuildInputNotes,
    ) {
        val messages = mutableListOf<String>()
        if (notes.ignoredInputs.isNotEmpty()) {
            messages.add("ignored inputs: " +
                    notes.ignoredInputs.joinToString(", ") { it.absoluteFile.normalize().path })
        }
        if (notes.unreadableConfigs.isNotEmpty()) {
            messages.add("unreadable resource declarations: " +
                    notes.unreadableConfigs.joinToString(", ") { it.absoluteFile.normalize().path })
        }
        if (messages.isEmpty()) return
        project.logger.debug("Jugg: $type external build of ${moduleInfo.name} " + messages.joinToString("; "))
    }

    /** Adds one input root with the given rules; normalization and ordering happen in compaction. */
    private fun addInputDir(
        inputs: MutableList<ExternalBuildInputDir>,
        directory: File,
        vararg rules: ExternalBuildInputFilterRule,
    ) {
        inputs.add(ExternalBuildInputDir(directory.absoluteFile.normalize(), rules.toCollection(linkedSetOf())))
    }

    /** Whether the C/C++ file carries a header extension; hidden and extensionless files never do. */
    private fun File.isNativeHeaderFile(): Boolean {
        if (name.startsWith(".")) return false
        // Case-insensitive comparison instead of case conversion keeps the init script loadable on
        // every supported Gradle Kotlin DSL version and locale independent.
        return nativeHeaderExtensions.any { it.equals(extension, ignoreCase = true) }
    }

    private fun readInputFiles(value: Any?, depth: Int = 0): List<File> {
        if (value == null || depth >= 5) return emptyList()
        if (value is org.gradle.api.file.FileCollection) return value.files.toList()
        if (value is File) return if (value.isDirectory) value.listFiles()?.toList().orEmpty() else listOf(value)
        if (value is org.gradle.api.provider.Provider<*>) return readInputFiles(value.orNull, depth + 1)
        if (value is java.util.concurrent.Callable<*>) return readInputFiles(value.call(), depth + 1)
        if (value is Collection<*>) return value.flatMap { readInputFiles(it, depth + 1) }
        return emptyList()
    }

    private fun File.isUnderPath(directory: File): Boolean {
        val dirPath = directory.absoluteFile.normalize().path
        val filePath = absoluteFile.normalize().path
        if (filePath == dirPath) return true
        return if (dirPath.endsWith(File.separator)) {
            filePath.startsWith(dirPath)
        } else {
            filePath.startsWith(dirPath + File.separator)
        }
    }

    /**
     * Normalizes every root and keeps one entry per identical directory and rule set. Parent and
     * child directories stay separate on purpose: their rules describe different input sources and
     * matching is the union of all of them, so no rule can narrow another one. Directories and
     * rules are sorted by path and by enum declaration order, keeping snapshots and diffs stable.
     */
    private fun compactInputDirs(inputs: List<ExternalBuildInputDir>): List<ExternalBuildInputDir> {
        return inputs
            .map { input ->
                ExternalBuildInputDir(
                    input.directory.absoluteFile.normalize(),
                    ExternalBuildInputFilterRule.values().filter { it in input.filterRules }
                        .toCollection(linkedSetOf()),
                )
            }
            .filter { it.filterRules.isNotEmpty() }
            .distinctBy { it.directory.path to it.filterRules }
            // Single-selector comparators only: the multi-selector compareBy overload is ambiguous
            // in the Kotlin stdlib shipped with the oldest supported Gradle versions.
            .sortedWith(
                compareBy<ExternalBuildInputDir> { it.directory.path }
                    .thenBy { it.filterRules.joinToString(",") { rule -> rule.name } },
            )
    }

    /** Flutter inputs confirmed by the task model plus the roots and exclusions they imply. */
    private class FlutterBuildInputs(
        val configFiles: List<File>,
        val inputDirs: List<ExternalBuildInputDir>,
        val excludedDirs: List<File>,
        val notes: ExternalBuildInputNotes,
    )

    /** Native inputs of one module: the roots to watch, the roots to exclude and ignored metadata. */
    private class NativeInputs(
        val inputDirs: List<ExternalBuildInputDir>,
        val excludedDirs: List<File>,
        val notes: ExternalBuildInputNotes,
    )

    /**
     * Inputs one collection round did not monitor, kept only for the aggregated debug report and
     * never written into project info.
     */
    private class ExternalBuildInputNotes(
        val ignoredInputs: MutableList<File> = mutableListOf(),
        val unreadableConfigs: MutableList<File> = mutableListOf(),
    )

    /** Native build configuration read from the Android extension. */
    private class CppBuildConfig(
        val sourceDirs: List<File>,
        val configFiles: List<File>,
        val stagingDirs: List<File>,
    )

    /**
     * Reads the native artifacts of the current Flutter variant from its real Gradle task.
     * Legacy Flutter packages them into a Jar archive, Flutter 3.x stages them into a jniLibs directory.
     */
    private fun readFlutterNativeOutput(project: Project, variantCapital: String, flutterTask: Task?): FlutterNativeOutput {
        if (flutterTask == null) {
            return FlutterNativeOutput(null, null, "Flutter native task for $variantCapital was not found")
        }
        val packTasks = listOf(
            "packJniLibsflutterBuild$variantCapital",
            "packLibsflutterBuild$variantCapital",
        ).mapNotNull { findTaskByNameWithRetry(project, it) as? Task }
            .filter { it is Jar && it.dependsOnTask(flutterTask) }
        if (packTasks.size > 1) {
            return FlutterNativeOutput(null, null, "Multiple Flutter native tasks were found for $variantCapital")
        }
        val packTask = packTasks.singleOrNull()
        if (packTask != null) {
            val archive = readFileValue(readProperty(packTask, "archiveFile"))
                ?: readFileValue(readProperty(packTask, "archivePath"))
                ?: return FlutterNativeOutput(null, null, "Flutter task ${packTask.path} native output was not found")
            return FlutterNativeOutput(packTask, archive, null)
        }

        val copyTask = findTaskByNameWithRetry(project, "copyJniLibsflutterBuild$variantCapital") as? Task
        if (copyTask == null || !copyTask.dependsOnTask(flutterTask)) {
            return FlutterNativeOutput(null, null, "Flutter native task for $variantCapital was not found")
        }
        val nativeDir = readFileValue(readProperty(copyTask, "destinationDir"))
            ?: return FlutterNativeOutput(null, null, "Flutter task ${copyTask.path} native output was not found")
        return FlutterNativeOutput(copyTask, nativeDir, null)
    }

    /** Reads the task dependency without failing when the graph is not resolvable yet. */
    private fun Task.dependsOnTask(other: Task): Boolean {
        return runCatching { taskDependencies.getDependencies(this).contains(other) }.getOrDefault(false)
    }

    /** Native output of one Flutter variant, expressed as an archive or a directory. */
    private data class FlutterNativeOutput(
        val task: Task?,
        val output: File?,
        val reason: String?,
    )

    private fun readProjectFile(project: Project, value: Any?): File? {
        return if (value is String) project.file(value) else readFileValue(value)
    }

    private fun readFileValue(value: Any?, depth: Int = 0): File? {
        if (value == null || depth >= 5) return null
        if (value is File) return value
        if (value is org.gradle.api.file.FileSystemLocation) return value.asFile
        if (value is org.gradle.api.provider.Provider<*>) return readFileValue(value.orNull, depth + 1)
        val asFile = readProperty(value, "asFile") as? File
        if (asFile != null) return asFile
        return readFileValue(invokeNoArg(value, "getOrNull") ?: invokeNoArg(value, "get"), depth + 1)
    }

    /** Reads configured common roots without relying on source-set directory names. */
    private fun readKotlinCommonSourceDirs(kotlinTask: Any): List<File> {
        return try {
            val commonSourceSet = readProperty(kotlinTask, "commonSourceSet\$kotlin_gradle_plugin_common")
                ?: return emptyList()
            val commonRoots = readConfiguredSourceRoots(commonSourceSet)
            if (commonRoots.isNotEmpty()) return commonRoots

            val commonFileCollection = commonSourceSet as? FileCollection
            val visitedRoots = commonFileCollection?.let(::readFileTreeRoots).orEmpty()
            if (visitedRoots.isNotEmpty()) return visitedRoots

            val commonFiles = commonFileCollection?.files ?: emptySet()
            val taskSources = readProperty(kotlinTask, "sources")
            val taskRoots = readConfiguredSourceRoots(taskSources)
            val roots = commonFiles.mapNotNull { file ->
                taskRoots.filter { root ->
                    file.toPath().normalize().startsWith(root.toPath().normalize())
                }.maxByOrNullForKt14 { it.absolutePath.length }
            }.distinct()
            if (roots.isEmpty() && commonFiles.isNotEmpty()) {
                println("Jugg: Kotlin common source directories are unavailable for $kotlinTask")
            }
            roots
        } catch (e: Throwable) {
            println("Jugg: read Kotlin common source directories failed: $e")
            emptyList()
        }
    }

    /** Reads the authoritative Kotlin fragment graph exposed by K2 Gradle tasks. */
    private fun readKotlinFragments(kotlinTask: Any): Triple<Map<String, List<File>>, Map<String, List<String>>, String?> {
        return try {
            val structure = readProperty(kotlinTask, "multiplatformStructure")
                ?: return Triple(emptyMap(), emptyMap(), null)
            val fragmentProperty = readProperty(structure, "fragments")
                ?: return Triple(emptyMap(), emptyMap(), null)
            val fragments = ((invokeNoArg(fragmentProperty, "getOrNull") ?: invokeNoArg(fragmentProperty, "get"))
                as? Collection<*>).orEmpty()
            val sourceDirs = fragments.mapNotNull { fragment ->
                fragment ?: return@mapNotNull null
                val name = readProperty(fragment, "fragmentName")?.toString() ?: return@mapNotNull null
                val sources = readProperty(fragment, "sources") as? FileCollection
                name to sources?.let(::readFileTreeRoots).orEmpty()
            }.toMap()
            val edgeProperty = readProperty(structure, "refinesEdges")
                ?: return Triple(sourceDirs, emptyMap(), null)
            val edges = ((invokeNoArg(edgeProperty, "getOrNull") ?: invokeNoArg(edgeProperty, "get"))
                as? Collection<*>).orEmpty()
            val refines = edges.mapNotNull { edge ->
                edge ?: return@mapNotNull null
                val from = readProperty(edge, "fromFragmentName")?.toString() ?: return@mapNotNull null
                val to = readProperty(edge, "toFragmentName")?.toString() ?: return@mapNotNull null
                from to to
            }.groupBy({ it.first }, { it.second })
            val defaultProperty = readProperty(structure, "defaultFragmentName")
                ?: return Triple(sourceDirs, refines, null)
            val defaultName = (invokeNoArg(defaultProperty, "getOrNull")
                ?: invokeNoArg(defaultProperty, "get"))?.toString()
            Triple(sourceDirs, refines, defaultName)
        } catch (e: Throwable) {
            println("Jugg: read Kotlin fragments failed: $e")
            Triple(emptyMap(), emptyMap(), null)
        }
    }

    private fun readFileTreeRoots(files: FileCollection): List<File> {
        val roots = linkedSetOf<File>()
        files.asFileTree.visit(object : org.gradle.api.Action<org.gradle.api.file.FileVisitDetails> {
            override fun execute(details: org.gradle.api.file.FileVisitDetails) {
                var root = details.file
                repeat(details.relativePath.segments.size) {
                    root = root.parentFile ?: return
                }
                roots.add(root)
            }
        })
        return roots.toList()
    }

    private fun readConfiguredSourceRoots(value: Any?, depth: Int = 0): List<File> {
        if (value == null || depth >= 5) return emptyList()
        if (value is File) return listOf(value).filter { !it.exists() || it.isDirectory }
        if (value is org.gradle.api.file.Directory) return listOf(value.asFile)
        if (value is org.gradle.api.file.SourceDirectorySet) return value.srcDirs.toList()
        if (value is org.gradle.api.provider.Provider<*>) {
            return readConfiguredSourceRoots(value.orNull, depth + 1)
        }
        if (value is Function0<*>) {
            return readConfiguredSourceRoots(value.invoke(), depth + 1)
        }
        if (value is java.util.concurrent.Callable<*>) {
            return readConfiguredSourceRoots(value.call(), depth + 1)
        }
        if (value is Collection<*>) {
            return value.flatMap { readConfiguredSourceRoots(it, depth + 1) }.distinct()
        }

        val asFile = readProperty(value, "asFile") as? File
        if (asFile != null) return listOf(asFile)
        val srcDirs = readProperty(value, "srcDirs") as? Collection<*>
        if (srcDirs != null) return readConfiguredSourceRoots(srcDirs, depth + 1)
        val from = readProperty(value, "from") as? Collection<*>
        if (from != null) return readConfiguredSourceRoots(from, depth + 1)

        val resolved = invokeNoArg(value, "getOrNull")
            ?: invokeNoArg(value, "invoke")
            ?: invokeNoArg(value, "call")
        return if (resolved === value) emptyList() else readConfiguredSourceRoots(resolved, depth + 1)
    }

    private fun readProperty(value: Any?, propertyName: String): Any? {
        value ?: return null
        val getterName = "get${propertyName.camelCompat}"
        return invokeNoArg(value, getterName)
    }

    private fun invokeNoArg(value: Any, methodName: String): Any? {
        val method = value::class.java.methods.firstOrNull {
            it.name == methodName && it.parameterCount == 0
        } ?: return null
        return try {
            method.isAccessible = true
            method.invoke(value)
        } catch (_: Throwable) {
            null
        }
    }

    /** Reads the effective JVM target from Kotlin 2 compiler options with a legacy fallback. */
    private fun readKotlinJvmTarget(kotlinTask: Any?, legacyOptions: Reflector?): String? {
        val compilerOptions = readProperty(kotlinTask, "compilerOptions")
        val targetValue = readKotlinOptionValue(compilerOptions, "jvmTarget")
        val target = readProperty(targetValue, "target")?.toString() ?: targetValue?.toString()
        return target?.removePrefix("JVM_")?.replace('_', '.')
            ?: legacyOptions["jvmTarget"]?.valueString
    }

    /** Reads free compiler arguments from Kotlin 2 compiler options with a legacy fallback. */
    private fun readKotlinFreeCompilerArgs(kotlinTask: Any?, legacyOptions: Reflector?): List<String> {
        val compilerOptions = readProperty(kotlinTask, "compilerOptions")
        val args = readKotlinOptionValue(compilerOptions, "freeCompilerArgs") as? Collection<*>
        val freeArgs = args?.map { it.toString() }
            ?: (legacyOptions["freeCompilerArgs"]?.value as? Collection<*>)?.map { it.toString() }
            ?: emptyList()
        // Kotlin 2.x keeps opt-in markers in the typed compilerOptions.optIn instead of freeCompilerArgs.
        val optInArgs = (readKotlinOptionValue(compilerOptions, "optIn") as? Collection<*>)
            ?.map { "-opt-in=$it" } ?: emptyList()
        val mergedArgs = freeArgs.toMutableList()
        val existingArgs = freeArgs.toMutableSet()
        optInArgs.forEach { if (existingArgs.add(it)) mergedArgs.add(it) }
        return mergedArgs
    }

    /** Reads resolved subplugin arguments across Kotlin Gradle Plugin getter name changes. */
    private fun readKotlinPluginOptions(kotlinTask: Any?): List<String> {
        kotlinTask ?: return emptyList()
        val propertyNames = listOf(
            "kotlinPluginData\$kotlin_gradle_plugin_common",
            "kotlinPluginData\$kotlin_gradle_plugin",
        )
        var pluginData: Any? = null
        for (propertyName in propertyNames) {
            val provider = readProperty(kotlinTask, propertyName) ?: continue
            pluginData = invokeNoArg(provider, "getOrNull") ?: invokeNoArg(provider, "get")
            if (pluginData != null) break
        }
        val options = readProperty(pluginData, "options") ?: return emptyList()
        val arguments = readProperty(options, "arguments") as? Collection<*> ?: return emptyList()
        return arguments.map { it.toString() }.filter { it.startsWith("plugin:") }
    }

    private fun readKotlinOptionValue(compilerOptions: Any?, name: String): Any? {
        val value = readProperty(compilerOptions, name) ?: return null
        return if (value is org.gradle.api.provider.Provider<*>) value.orNull else invokeNoArg(value, "getOrNull") ?: value
    }

    private fun findTaskByNameWithRetry(project: Project, taskName: String): Any? {
        try {
            return project.tasks.findByName(taskName)
        } catch (e: Throwable) {
            try {
                // occurs on application module with includeBuild
                // retry will be ok
                // see: https://docs.gradle.org/current/samples/sample_composite_builds_declared_substitutions.html
                // see: https://docs.gradle.org/current/userguide/composite_builds.html
                val task = project.tasks.findByName(taskName)
                println("Jugg: ${project.name}.findByName(\"$taskName\") failed and success with retry")
                return task
            } catch (e: Throwable) {
                println("Jugg: ${project.name}.findByName(\"$taskName\") failed with retry")
                return null
            }
        }
    }

    private fun getDependenciesWithoutResolved(project: Project, dependency: org.gradle.api.artifacts.Dependency,
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
                    val dependencies = doGetDependencies(resolvedConfiguration, isAndroidDepend = isAndroidDepend)
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
                val files = dependency.files.toList()
                return files.map {
                    val cache = dependenciesCrcCache[it.absolutePath]
                    if (cache?.lastModifiedTime == it.lastModified()) {
                        return@map cache
                    } else {
                        val fileDependency = LibraryDependency(it.standardFileCollectionLibraryName, it)
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
                    var relativePath = ""
                    if (dependency.isJar && dependency.file.path.contains("jars")) {
                        // handles aar that contains multiple jars
                        relativePath = dependency.file.path.substringAfterLast("jars")
                    }
                    val uniqueName = splits[0] + ":" + splits[1] + "/" + dependency.type + "/" + relativePath
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

    private fun doGetDependencies(resolvedConfiguration: Configuration, isAndroidDepend: Boolean, isNeedProjectDependencies: Boolean = true): List<Dependency> {
        val result = mutableSetOf<Dependency>()
        // resolve project dependency here, because project dependency won't return by artifactView
        // if it's build directory is deleted
        if (isNeedProjectDependencies) {
            getProjectDependencies(result, resolvedConfiguration.resolvedConfiguration.firstLevelModuleDependencies)
        }

        val resolvedArtifacts = mutableSetOf<ResolvedArtifactResult>()

        fun putJarArtifacts() {
            val jarArtifacts = mutableMapOf<String, ResolvedArtifactResult>()
            // "processed-jar" matched the jar get by IDE
            // "processed-jar" returns empty list if jetifier not enabled if gradle/agp > 8.x (x is not confirmed)
            val jarView = resolvedConfiguration.incoming.artifactView(SimpleArtifactFilter("jar"))
            jarView.artifacts.artifacts.forEach {
                val uniqueKey = it.id.componentIdentifier.toString() + "_" + it.file.parentFile.name + "_" + it.file.name
                jarArtifacts[uniqueKey] = it
            }

            // read processed-jar last, to override jar result if exists
            val processedJarView = resolvedConfiguration.incoming.artifactView(SimpleArtifactFilter("processed-jar"))
            val processedResult = processedJarView.artifacts.artifacts
            processedResult.forEach {
                val uniqueKey = it.id.componentIdentifier.toString() + "_" + it.file.parentFile.name + "_" + it.file.name
                jarArtifacts[uniqueKey] = it
            }

            resolvedArtifacts.addAll(jarArtifacts.values)
        }

        // Best-effort R package name per component. The artifact type is an AGP build model capability,
        // so an unsupported AGP only closes this source and keeps the AAR manifest package as fallback.
        val rPackageNames = mutableMapOf<String, String>()
        fun putSymbolPackageNames() {
            val symbolView = resolvedConfiguration.incoming.artifactView(
                SimpleArtifactFilter("android-symbol-with-package-name")
            )
            symbolView.artifacts.artifacts.forEach {
                val identifier = it.id.componentIdentifier
                if (identifier is ProjectComponentIdentifier) {
                    return@forEach
                }
                val rPackageName = it.file.readFirstNonEmptyLine()
                if (!rPackageName.isNullOrEmpty()) {
                    rPackageNames[identifier.toString()] = rPackageName
                }
            }
        }

        if (isAndroidDepend) {
            val resView = resolvedConfiguration.incoming.artifactView(SimpleArtifactFilter("android-res"))
            resolvedArtifacts.addAll(resView.artifacts.artifacts)
            val manifestView = resolvedConfiguration.incoming.artifactView(SimpleArtifactFilter("android-manifest"))
            resolvedArtifacts.addAll(manifestView.artifacts.artifacts)
            putJarArtifacts()
            putSymbolPackageNames()
        } else {
            // "jar" is not correct when dependency using android-support library, e.g. ARouter
            // "processed-jar" returns empty list if jetifier not enabled
            putJarArtifacts()
        }

        resolvedArtifacts.forEach {
            val identifier = it.id.componentIdentifier
            if (identifier is ProjectComponentIdentifier) {
                return@forEach // project dependency already handled at top
            }
            // all artifacts of one component share the identifier, so res/manifest/jar carry the same namespace
            val rPackageName = rPackageNames[identifier.toString()]
            val cache = dependenciesCrcCache[it.file.absolutePath]
            if (cache != null) {
                if (cache.lastModifiedTime == it.file.lastModified()) {
                    result.add(cache.withRPackageName(rPackageName))
                    return@forEach
                }
            }

            if (identifier is OpaqueComponentArtifactIdentifier) {
                // library file in file collection
                val fileGet = reflector(identifier).fieldP("file")
                val file = (fileGet?.value as? File) ?: it.file
                val dependencyName = file.standardFileCollectionLibraryName
                if (identifier.toString().endsWith(".jar")) {
                    // jar file, use origin jar file to match project info from IDE
                    val libraryDependency = LibraryDependency(dependencyName, file).withRPackageName(rPackageName)
                    result.add(libraryDependency)
                } else {
                    // aar file, use extract files in .gradle
                    val libraryDependency = LibraryDependency(dependencyName, it.file).withRPackageName(rPackageName)
                    dependenciesCrcCache[file.absolutePath] = libraryDependency
                    result.add(libraryDependency)
                }
            } else {
                val libraryName = identifier.displayName.standardLibraryName
                val libraryDependency = LibraryDependency(libraryName, it.file).withRPackageName(rPackageName)
                dependenciesCrcCache[it.file.absolutePath] = libraryDependency
                result.add(libraryDependency)
            }
        }
        return result.toList()
    }

    private fun doGetDependenciesNew(resolvedConfiguration: Configuration): List<LibraryDependency> {
        val result = mutableSetOf<LibraryDependency>()
        resolvedConfiguration.resolvedConfiguration.firstLevelModuleDependencies.forEach {
            doGetDependenciesNew(it, result)
        }
        return result.toList()
    }

    private fun doGetDependenciesNew(resolvedDependency: ResolvedDependency, result: MutableSet<LibraryDependency>) {
        resolvedDependency.allModuleArtifacts.forEach {
            result.add(LibraryDependency(resolvedDependency.moduleVersion, it.file))
        }
        resolvedDependency.children.forEach {
            doGetDependenciesNew(it, result)
        }
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

    /**
     * Best-effort read of the first non-empty line, which is the R package name of `package-aware-r.txt`.
     */
    private fun File.readFirstNonEmptyLine(): String? {
        return try {
            useLines { lines -> lines.firstOrNull { it.isNotBlank() } }?.trim()?.takeIf { it.isNotEmpty() }
        } catch (e: Exception) {
            println("Jugg: read R package name from $absolutePath failed, $e")
            null
        }
    }

    /**
     * Keeps the AAR R namespace metadata when a cached dependency file is reused as-is.
     */
    private fun LibraryDependency.withRPackageName(rPackageName: String?): LibraryDependency {
        if (rPackageName == null || rPackageName == this.rPackageName) {
            return this
        }
        return copy(rPackageName = rPackageName)
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

    private val File.standardFileCollectionLibraryName: String get() {
        return ".${File.separator}" + relativeTo(ideProjectDir).path
    }

    private val Project.standardModuleName: String get() {
        // match with module name read from IDE
        // e.g. :libraryGroup:library1 -> libraryGroup.library1
        return path.replace(":", ".").substring(1)
    }

    private fun Project.hasKotlinPlugin(): Boolean {
        return plugins.hasPlugin("org.jetbrains.kotlin.android") ||
                plugins.hasPlugin("kotlin-android") ||
                plugins.hasPlugin("org.jetbrains.kotlin.multiplatform") ||
                plugins.hasPlugin("kotlin-multiplatform")
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

    private val String.standardModuleNameForProjectPath: String get() {
        return removePrefix(":").replace(":", ".")
    }

    private val ResolvedDependency.moduleNameIfIsProject: String? get() {
        val moduleGroup = moduleGroup.removePrefix(rootProject.name + ".") // handle subproject style
        val moduleName = moduleName.removePrefix(rootProject.name + ".")

        if (!modulesNames.contains(moduleName)) {
            return null
        }
        if (moduleVersion != "unspecified") {
            return null
        }
        return if (moduleGroup == rootProject.name) {
            moduleName
        } else {
            "$moduleGroup.$moduleName"
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

    /**
     * SimpleArtifactFilter configures artifact views to resolve one specific Gradle artifact type.
     */
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
                    // see [com.android.build.gradle.internal.publishing.AndroidArtifacts#TYPE_CLASSES_JAR], find it in AGP
                    attributeContainer.attribute(Attribute.of("artifactType", String::class.java), artifactType)
                }
            })
        }
    }

    companion object {

        /**
         * Builds a synthetic ModuleInfo representing the androidTest source set of [appModuleInfo].
         * Returns null if [sourceDirs] is empty (project has no androidTest sources).
         */
        fun buildAndroidTestModuleInfo(
            appModuleInfo: ModuleInfo,
            sourceDirs: List<File>,
            libraryDependencies: List<LibraryDependency>,
            moduleDependencies: List<ModuleDependency> = emptyList(),
            testApplicationId: String?,
        ): ModuleInfo? {
            if (sourceDirs.isEmpty()) return null
            val ownerPackage = appModuleInfo.applicationId ?: appModuleInfo.namespace ?: return null
            val resolvedTestAppId = testApplicationId ?: "$ownerPackage.test"
            val targetPackage = when (appModuleInfo.moduleType) {
                ModuleInfo.Type.Library -> resolvedTestAppId
                else -> ownerPackage
            }
            val androidTestBuildVariant = resolveAndroidTestBuildVariant(appModuleInfo.buildVariant)
            return appModuleInfo.copy(
                name = "${appModuleInfo.name}.androidTest",
                moduleType = ModuleInfo.Type.Library,
                buildVariant = androidTestBuildVariant,
                buildPathInfo = appModuleInfo.buildPathInfo.copy(buildVariant = androidTestBuildVariant),
                applicationId = resolvedTestAppId,
                instrumentationTargetPackage = targetPackage,
                sourceDirs = sourceDirs,
                resourceDirs = emptyList(),
                assetsDirs = emptyList(),
                manifestFile = null,  // androidTest manifest not needed for incremental compile in Phase 2
                manifestPlaceHolders = null,
                libraryDependencies = libraryDependencies,
                runtimeLibraryDependencies = emptyList(),
                annotationProcessorDependencies = emptyList(),
                kaptDependencies = emptyList(),
                moduleDependencies = (listOf(ModuleDependency(appModuleInfo.name)) + moduleDependencies)
                    .distinctBy { it.moduleName },
                variants = emptyList(),
                signingConfigs = null,
            )
        }

        private fun resolveAndroidTestBuildVariant(ownerBuildVariant: String): String {
            return if (ownerBuildVariant.endsWith("AndroidTest")) {
                ownerBuildVariant
            } else {
                "${ownerBuildVariant}AndroidTest"
            }
        }
    }

}
