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
import org.gradle.internal.component.local.model.OpaqueComponentArtifactIdentifier
import java.io.File

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
    private val taskGraphGroup: Map<Project, Set<String>> = rootProject.gradle.taskGraph.allTasks
        .groupBy { it.project }
        .mapValues { (_, task) ->
            task.map { it.name }.toSet()
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
                    ).filterIsInstance<LibraryDependency>()
                    val androidTestModuleInfo = buildAndroidTestModuleInfo(
                        appModuleInfo = moduleInfo,
                        sourceDirs = sourceDirs.filter { it.exists() },
                        libraryDependencies = atDependencies,
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


                val kotlinExtensions: List<File>? = project.configurations.findByName("kotlin-extension")?.files?.toList()

                @Suppress("UNCHECKED_CAST")
                moduleInfo = moduleInfo.copy(
                    compileVersion = compileSdkVersion?.substringAfter("android-"),
                    buildToolsVersion = buildToolsVersion,
                    minSdkVersion = defaultConfig["minSdkVersion"]["apiLevel"]?.valueString,
                    kotlinJvmTarget = kotlinJvmTarget,
                    kotlinFreeCompilerArgs = kotlinFreeCompilerArgs,
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
                )
            }
        }
        TraceLogger.end("getVar")

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

            // won't actually use this for now to save time
            val runtimeDependencies = emptyList<Dependency>()

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
                runtimeLibraryDependencies = runtimeDependencies.filterIsInstance<LibraryDependency>(),
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

        moduleInfo = moduleInfo.copy(composeResourceInfo = getComposeResourceInfo(project, moduleInfo))

        TraceLogger.end("getModule:${project.standardModuleName}")
        return moduleInfo
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
                    variant["name"]?.valueString ?: return@mapNotNull null,
                    variant["signingConfig"]["name"]?.valueString,
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
                        variant["name"]?.valueString ?: return@mapNotNull null,
                        variant["signingConfig"]["name"]?.valueString,
                    )
                )
            }
        } else {
            // com.android.build.gradle.api.LibraryVariant
            (androidExt["libraryVariants"]?.value as? Collection<*>)?.forEach { obj ->
                val variant = reflector(obj)
                variants.add(Variant(variant["name"]?.valueString ?: return@forEach,  null))
            }
        }

        if (variants.isEmpty()) {
            variants.addAll(getCollectedAndroidVariants(rootProject, project))
        }

        val buildVariant = guessBuildVariant(project, variants) ?: "debug"

        return moduleInfo.copy(
            buildVariant = buildVariant,
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

    private fun guessBuildVariant(project: Project, variants: List<Variant>): String? {
        val taskNames = taskGraphGroup[project] ?: run {
            println("Jugg: ${project.standardModuleName} task graph not found, build variant may not correct. " +
                    "Most likely the module is not in compilation")
            emptySet()
        }
        val startTaskNames: List<String>? = project.gradle.startParameter.taskRequests.getOrNull(0)?.args
        return guessBuildVariant(project.standardModuleName, variants, taskNames, startTaskNames)
    }

    private fun getDependenciesByConfig(project: Project, filterName: String, isAndroidDepend: Boolean, isNeedResolve: Boolean = true, isGetByNewWay: Boolean = false): List<Dependency> {
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
                    doGetDependencies(configuration, isAndroidDepend)
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
        return args?.map { it.toString() }
            ?: (legacyOptions["freeCompilerArgs"]?.value as? Collection<*>)?.map { it.toString() }
            ?: emptyList()
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

    private fun doGetDependencies(resolvedConfiguration: Configuration, isAndroidDepend: Boolean): List<Dependency> {
        val result = mutableSetOf<Dependency>()
        // resolve project dependency here, because project dependency won't return by artifactView
        // if it's build directory is deleted
        getProjectDependencies(result, resolvedConfiguration.resolvedConfiguration.firstLevelModuleDependencies)

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

        if (isAndroidDepend) {
            val resView = resolvedConfiguration.incoming.artifactView(SimpleArtifactFilter("android-res"))
            resolvedArtifacts.addAll(resView.artifacts.artifacts)
            val manifestView = resolvedConfiguration.incoming.artifactView(SimpleArtifactFilter("android-manifest"))
            resolvedArtifacts.addAll(manifestView.artifacts.artifacts)
            putJarArtifacts()
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
            val cache = dependenciesCrcCache[it.file.absolutePath]
            if (cache != null) {
                if (cache.lastModifiedTime == it.file.lastModified()) {
                    result.add(cache)
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
                    val libraryDependency = LibraryDependency(dependencyName, file)
                    result.add(libraryDependency)
                } else {
                    // aar file, use extract files in .gradle
                    val libraryDependency = LibraryDependency(dependencyName, it.file)
                    dependenciesCrcCache[file.absolutePath] = libraryDependency
                    result.add(libraryDependency)
                }
            } else {
                val libraryName = identifier.displayName.standardLibraryName
                val libraryDependency = LibraryDependency(libraryName, it.file)
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
                moduleDependencies = listOf(ModuleDependency(appModuleInfo.name)),
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
