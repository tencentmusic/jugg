package com.sickworm.intellij.jugg.compiler.context

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.projectRoots.ProjectJdkTable
import com.intellij.openapi.roots.LibraryOrderEntry
import com.intellij.openapi.roots.ModuleJdkOrderEntry
import com.intellij.openapi.roots.ModuleOrderEntry
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.roots.OrderRootType
import com.intellij.openapi.vfs.VfsUtil
import com.sickworm.intellij.jugg.compiler.BuildTarget
import com.sickworm.intellij.jugg.compiler.changeBaseDir
import com.sickworm.intellij.jugg.deploy.run.AsDeployerCompat
import com.sickworm.intellij.jugg.gradle.compile.isChild
import com.sickworm.intellij.jugg.ide.logic.TestModeManager
import com.sickworm.intellij.jugg.logger.TimeLogger
import com.sickworm.intellij.jugg.project.info.IJuggProjectInfoMerger
import com.sickworm.intellij.jugg.project.info.IProjectModelSource
import com.sickworm.intellij.jugg.project.info.JuggProjectInfo
import com.sickworm.intellij.jugg.project.info.JuggProjectInfoMerger
import com.sickworm.intellij.jugg.project.info.LibraryDependency
import com.sickworm.intellij.jugg.project.info.ModuleBuildPathInfo
import com.sickworm.intellij.jugg.project.info.ModuleDependency
import com.sickworm.intellij.jugg.project.info.ModuleInfo
import com.sickworm.intellij.jugg.project.info.ModulePathMergePolicy
import com.sickworm.intellij.jugg.project.info.ProjectInfoSerializer
import com.sickworm.intellij.jugg.project.info.ProjectModelLoadReason
import com.sickworm.intellij.jugg.project.info.ProjectModelResult
import com.sickworm.intellij.jugg.project.info.createGradleProjectInfoSerializers
import com.sickworm.intellij.jugg.project.info.hasMissingDependencies
import com.sickworm.intellij.jugg.project.runtime.JuggPathManager
import org.jetbrains.android.sdk.AndroidSdkAdditionalData
import org.jetbrains.jps.model.java.JavaResourceRootType
import org.jetbrains.jps.model.java.JavaSourceRootType
import java.io.File

/** Reads the IDEA project model and merges it with persisted Gradle snapshots. */
class IdeaProjectModelSource(
    private val project: Project,
    private val pathManager: JuggPathManager,
    private val moduleManager: ModuleManager = AsDeployerCompat.getModuleManager(project),
    private val logger: Logger,
) : IProjectModelSource {

    private val projectInfoSerializer = ProjectInfoSerializer(pathManager.ideProjectInfoFile, logger)
    private var gradleProjectInfoSerializers = emptyList<ProjectInfoSerializer>()
    private val merger: IJuggProjectInfoMerger = JuggProjectInfoMerger(logger)

    override fun load(reason: ProjectModelLoadReason, buildTarget: BuildTarget): ProjectModelResult {
        return when (reason) {
            ProjectModelLoadReason.INITIALIZE -> initialize(buildTarget)
            ProjectModelLoadReason.HOST_SYNC -> refreshIdeModel(buildTarget, preferGradleLibraryDependencies = false)
            ProjectModelLoadReason.HOST_FULL_BUILD_FALLBACK -> refreshIdeModel(buildTarget, preferGradleLibraryDependencies = true)
            ProjectModelLoadReason.VALIDATE -> validateModels(buildTarget)
            ProjectModelLoadReason.GRADLE_FETCH -> refreshGradleModel(buildTarget)
            ProjectModelLoadReason.MERGE -> mergeCurrentIdeModel(buildTarget)
        }
    }

    private fun initialize(buildTarget: BuildTarget): ProjectModelResult {
        val ideProjectInfo = updateProjectInfoFromIde(forceRefresh = false, buildTarget)
        merger.afterSync(projectInfoSerializer, buildTarget)
        gradleProjectInfoSerializers = createGradleProjectInfoSerializers(pathManager, logger)
        merger.afterLocalFetch(gradleProjectInfoSerializers, buildTarget)
        return ProjectModelResult(
            projectInfo = merger.juggProjectInfo ?: ideProjectInfo,
            isModelReloaded = true,
            needsGradleRefresh = hasMissingGradleModel(),
            includedBuildModuleRoots = loadIncludedBuildModuleRoots(),
        )
    }

    private fun refreshIdeModel(buildTarget: BuildTarget, preferGradleLibraryDependencies: Boolean): ProjectModelResult {
        val ideProjectInfo = updateProjectInfoFromIde(forceRefresh = true, buildTarget = buildTarget)
        merger.afterSync(projectInfoSerializer, buildTarget, preferGradleLibraryDependencies)
        return ProjectModelResult(
            projectInfo = merger.juggProjectInfo ?: ideProjectInfo,
            isModelReloaded = true,
            needsGradleRefresh = hasMissingGradleModel(),
            includedBuildModuleRoots = loadIncludedBuildModuleRoots(),
        )
    }

    private fun validateModels(buildTarget: BuildTarget): ProjectModelResult {
        val shouldReloadIde = projectInfoSerializer.load()?.hasMissingDependencies("ide", logger) == true
        val ideProjectInfo = if (shouldReloadIde) {
            logger.debug("IDE project info has missing dependencies, reload it")
            updateProjectInfoFromIde(forceRefresh = true, buildTarget).also {
                merger.afterSync(projectInfoSerializer, buildTarget)
            }
        } else {
            projectInfoSerializer.load()
        }
        if (shouldReloadIde) {
            val isMissing = projectInfoSerializer.load()?.hasMissingDependencies("ide", logger)
            logger.debug("IDE project info double checkMissing $isMissing, won't reload again if still missing")
        }
        return ProjectModelResult(
            projectInfo = merger.juggProjectInfo ?: ideProjectInfo,
            isModelReloaded = shouldReloadIde,
            needsGradleRefresh = hasMissingGradleModel(),
            includedBuildModuleRoots = loadIncludedBuildModuleRoots(),
        )
    }

    private fun refreshGradleModel(buildTarget: BuildTarget): ProjectModelResult {
        gradleProjectInfoSerializers = createGradleProjectInfoSerializers(pathManager, logger)
        merger.afterLocalFetch(gradleProjectInfoSerializers, buildTarget)
        return ProjectModelResult(
            projectInfo = merger.juggProjectInfo ?: projectInfoSerializer.load(),
            isModelReloaded = true,
            needsGradleRefresh = hasMissingGradleModel(),
            includedBuildModuleRoots = loadIncludedBuildModuleRoots(),
        )
    }

    private fun mergeCurrentIdeModel(buildTarget: BuildTarget): ProjectModelResult {
        val result = merger.afterSync(projectInfoSerializer, buildTarget)
        return ProjectModelResult(
            projectInfo = result.mergedInfo ?: projectInfoSerializer.load(),
            isModelReloaded = result.isFixMissingOrDelete,
            needsGradleRefresh = hasMissingGradleModel(),
            isFixMissingOrDelete = result.isFixMissingOrDelete,
            includedBuildModuleRoots = loadIncludedBuildModuleRoots(),
        )
    }

    private fun loadIncludedBuildModuleRoots(): Set<File> {
        if (gradleProjectInfoSerializers.isEmpty()) {
            gradleProjectInfoSerializers = createGradleProjectInfoSerializers(pathManager, logger)
        }
        return ModulePathMergePolicy.findIncludedBuildModuleRoots(
            gradleProjectInfoSerializers.map { it.load() }
        )
    }

    private fun hasMissingGradleModel(): Boolean {
        if (gradleProjectInfoSerializers.isEmpty()) {
            gradleProjectInfoSerializers = createGradleProjectInfoSerializers(pathManager, logger)
        }
        return gradleProjectInfoSerializers.any { serializer ->
            serializer.load()?.hasMissingDependencies("gradle", logger) == true
        }
    }

    private fun updateProjectInfoFromIde(forceRefresh: Boolean, buildTarget: BuildTarget): JuggProjectInfo {
        logger.debug("getAllModulesByModuleManager forceRefresh: $forceRefresh")
        if (!forceRefresh) {
            val cache = projectInfoSerializer.load()
            logger.debug("Try to load project info from cache, is success: ${cache != null}")
            if (cache != null) {
                return cache
            }
        }
        val projectInfo = readProjectInfoFromIde(buildTarget)
        projectInfoSerializer.save(projectInfo)
        return projectInfo
    }

    /** Builds the persisted IDEA snapshot from module roots, Android model metadata, and IDE dependencies. */
    private fun readProjectInfoFromIde(currentBuildTarget: BuildTarget): JuggProjectInfo {
        TimeLogger.start("initModuleRoots")
        logger.debug("Start init module roots")

        val dependencyCacheMap = mutableMapOf<String, LibraryDependency>()
        projectInfoSerializer.load()?.modules?.values?.forEach { moduleInfo ->
            moduleInfo.libraryDependencies.forEach { dependency ->
                dependencyCacheMap.putIfAbsent(
                    "${dependency.file.absolutePath}:${dependency.lastModifiedTime}",
                    dependency,
                )
            }
        }
        var totalCount = 0
        var hitCacheCount = 0

        val modules = mutableMapOf<String, ModuleInfo>()
        val addedModules = mutableSetOf<String>()
        val directoryNotFoundModules = mutableSetOf<String>()
        val ideaFolderModules = mutableSetOf<String>()
        val notGradleModules = mutableSetOf<String>()
        val testModules = mutableSetOf<String>()
        val filteredAndroidTestModules = mutableSetOf<String>()
        val filteredAndroidTestReasons = mutableMapOf<String, Int>()
        val noSourceModules = mutableMapOf<String, ModuleInfo>()
        val knownGradleAndroidTestModuleNames = loadKnownGradleAndroidTestModuleNames()
        logger.debug("known Gradle androidTest modules: ${knownGradleAndroidTestModuleNames?.joinToString(", ")}")

        moduleManager.modules.forEach { module ->
            var ideModuleInfo = try {
                AsDeployerCompat.getIdeModuleInfo(project, module, logger, false)
            } catch (e: Throwable) {
                if (TestModeManager.isTestMode) {
                    throw e
                }
                AsDeployerCompat.getIdeModuleInfo(project, module, logger, true)
            }
            if (ideModuleInfo == null) {
                notGradleModules.add(module.name)
                return@forEach
            }

            val baseDir = ideModuleInfo.baseDir
            if (baseDir == null) {
                directoryNotFoundModules.add(module.name)
                return@forEach
            }
            val normalizedBaseDir = if (baseDir.isAbsolute) baseDir else File(pathManager.projectDir, baseDir.path)
            val relativePath = if (baseDir.isAbsolute) baseDir.relativeTo(pathManager.projectDir) else baseDir
            if (relativePath.startsWith(".idea")) {
                ideaFolderModules.add(module.name)
                return@forEach
            }

            val stdModuleName = module.name.replace(Regex("~\\d+$"), "")
            if (ModulePathMergePolicy.shouldSkipIdeModule(stdModuleName, currentBuildTarget)) {
                testModules.add(module.name)
                return@forEach
            }
            val moduleSimpleName = module.name.moduleSimpleName
            val isAndroidTestIdeModule =
                ModulePathMergePolicy.classifyByName(stdModuleName) == ModulePathMergePolicy.ModuleSourceKind.AndroidTest

            val isBuildSrc = baseDir.name.moduleSimpleName == "buildSrc" && relativePath.path.startsWith("buildSrc")
            if (isBuildSrc) {
                return@forEach
            }

            if (ideModuleInfo.minifyEnabled != null && ideModuleInfo.toString() != "null") {
                logger.debug("module ${module.name} find minifyEnabled: ${ideModuleInfo.buildVariant} -> ${ideModuleInfo.minifyEnabled}")
            }
            val moduleBuildVariant = ModulePathMergePolicy.selectIdeBuildVariant(stdModuleName, ideModuleInfo.buildVariant)
            val manifestFile = ideModuleInfo.manifestRelativePath?.let { File(normalizedBaseDir, it) }
            val buildDirRelativePath = ideModuleInfo.buildDir?.let { buildDir ->
                val absoluteBuildDir = if (buildDir.isAbsolute) buildDir else File(pathManager.projectDir, buildDir.path)
                runCatching { absoluteBuildDir.relativeTo(pathManager.projectDir).path }.getOrNull()
            }
            val moduleBuildPathInfo = ModuleBuildPathInfo(
                pathManager.projectDir,
                normalizedBaseDir,
                moduleBuildVariant,
                buildDirRelativePath = buildDirRelativePath
                    ?: File(normalizedBaseDir, "build").relativeTo(pathManager.projectDir).path,
            )

            val sourceDirs = mutableSetOf<File>()
            val resourceDirs = mutableSetOf<File>()
            val assetDirs = mutableSetOf<File>()
            val moduleRootManager = ModuleRootManager.getInstance(module)
            val sourceRootTypes = mutableSetOf(
                JavaSourceRootType.SOURCE,
                org.jetbrains.kotlin.config.SourceKotlinRootType,
            )
            if (isAndroidTestIdeModule) {
                sourceRootTypes.add(JavaSourceRootType.TEST_SOURCE)
                sourceRootTypes.add(org.jetbrains.kotlin.config.TestSourceKotlinRootType)
            }
            sourceDirs.addAll(
                moduleRootManager.getSourceRoots(sourceRootTypes)
                    .filter { file -> moduleRootManager.excludeRoots.all { !file.path.startsWith(it.path) } }
                    .map(VfsUtil::virtualToIoFile)
                    .filter { !it.isChild(moduleBuildPathInfo.buildDir) }
            )

            if (isAndroidTestIdeModule) {
                val hasSourceFiles = sourceDirs.hasJavaOrKotlinSourceFile()
                val filterReason = ModulePathMergePolicy.getIdeAndroidTestCandidateFilterReason(
                    applicationId = ideModuleInfo.androidTestApplicationId,
                    instrumentationTargetPackage = ideModuleInfo.androidTestInstrumentationTargetPackage,
                    hasSourceFiles = hasSourceFiles,
                )
                logger.trace(
                    "IDE androidTest candidate: module=${module.name}, simpleName=$moduleSimpleName, " +
                        "applicationId=${ideModuleInfo.androidTestApplicationId}, " +
                        "instrumentationTargetPackage=${ideModuleInfo.androidTestInstrumentationTargetPackage}, " +
                        "hasSourceFiles=$hasSourceFiles, " +
                        "knownGradleAndroidTestModules=${knownGradleAndroidTestModuleNames?.size ?: "null"}, " +
                        "knownGradleContains=${knownGradleAndroidTestModuleNames?.contains(moduleSimpleName)}, " +
                        "include=${filterReason == null}, reason=${filterReason ?: "included"}"
                )
                if (filterReason != null) {
                    filteredAndroidTestModules.add(module.name)
                    filteredAndroidTestReasons[filterReason] = (filteredAndroidTestReasons[filterReason] ?: 0) + 1
                    return@forEach
                }
            }

            moduleRootManager.getSourceRoots(
                setOf(JavaResourceRootType.RESOURCE, org.jetbrains.kotlin.config.ResourceKotlinRootType)
            ).map(VfsUtil::virtualToIoFile)
                .filter { !it.isChild(moduleBuildPathInfo.buildDir) }
                .forEach { file ->
                    when (file.name) {
                        "res" -> resourceDirs.add(file)
                        "assets" -> assetDirs.add(file)
                        else -> {
                            val isResDir = file.guessIsResDir()
                            logger.debug("${module.name} unknown resource dir: $file, guess isResDir: $isResDir")
                            if (isResDir) resourceDirs.add(file) else assetDirs.add(file)
                        }
                    }
                }

            val moduleDependencies = mutableListOf<ModuleDependency>()
            val libraryDependencies = mutableListOf<LibraryDependency>()
            moduleRootManager.orderEntries.forEach { orderEntry ->
                when (orderEntry) {
                    is ModuleOrderEntry -> moduleDependencies.add(ModuleDependency(orderEntry.moduleName.moduleSimpleName))
                    is LibraryOrderEntry -> orderEntry.getRootFiles(OrderRootType.CLASSES).forEach library@{ file ->
                        val ioFile = VfsUtil.virtualToIoFile(file)
                        val key = "${ioFile.absolutePath}:${ioFile.lastModified()}"
                        if (ioFile.name == "kaptGeneratedClasses" && (!ioFile.exists() || ioFile.isDirectory)) {
                            return@library
                        }
                        val cachedDependency = dependencyCacheMap[key]
                        val libraryDependency = cachedDependency ?: run {
                            var name = orderEntry.libraryName ?: ioFile.name
                            if (name.startsWith("Gradle: ")) name = name.substring("Gradle: ".length)
                            if (name.endsWith("@aar")) name = name.substring(0, name.length - "@aar".length)
                            LibraryDependency(name, ioFile).also { dependencyCacheMap[key] = it }
                        }
                        if (cachedDependency != null) {
                            hitCacheCount++
                        }
                        libraryDependencies.add(libraryDependency)
                        totalCount++
                    }
                    is ModuleJdkOrderEntry -> {
                        if (orderEntry.jdkTypeName == "Android SDK") {
                            val additionalData = orderEntry.jdk?.sdkAdditionalData as? AndroidSdkAdditionalData
                            val buildTarget = additionalData?.buildTargetHashString
                            if (buildTarget != null && buildTarget.startsWith("android-")) {
                                ideModuleInfo = ideModuleInfo!!.copy(
                                    compileVersion = buildTarget.substringAfter("android-").substringBefore("-ext")
                                )
                            }
                        }
                    }
                }
            }

            val info = ideModuleInfo!!
            val hasIdeAndroidTestMetadata = isAndroidTestIdeModule &&
                ModulePathMergePolicy.hasValidAndroidTestMetadata(
                    info.androidTestApplicationId,
                    info.androidTestInstrumentationTargetPackage,
                )
            val moduleInfo = ModuleInfo(
                name = moduleSimpleName,
                moduleType = if (hasIdeAndroidTestMetadata) ModuleInfo.Type.Library else ModuleInfo.Type.Unknown,
                moduleRootDir = normalizedBaseDir,
                projectRootDir = pathManager.projectDir,
                sourceDirs = sourceDirs.toList(),
                resourceDirs = resourceDirs.toList(),
                assetsDirs = assetDirs.toList(),
                manifestFile = manifestFile,
                manifestPlaceHolders = null,
                buildVariant = moduleBuildVariant,
                compileVersion = info.compileVersion,
                minSdkVersion = info.minSdkVersion,
                buildToolsVersion = info.buildToolsVersion,
                kotlinJvmTarget = info.kotlinJvmTarget,
                kotlinFreeCompilerArgs = info.kotlinFreeCompilerArgs ?: emptyList(),
                javaSourceCompatibility = info.javaSourceCompatibility,
                javaTargetCompatibility = info.javaTargetCompatibility,
                buildPathInfo = moduleBuildPathInfo,
                moduleDependencies = moduleDependencies,
                libraryDependencies = libraryDependencies,
                runtimeLibraryDependencies = emptyList(),
                annotationProcessorDependencies = emptyList(),
                kaptDependencies = emptyList(),
                applicationId = if (hasIdeAndroidTestMetadata) info.androidTestApplicationId else null,
                instrumentationTargetPackage = if (hasIdeAndroidTestMetadata) {
                    info.androidTestInstrumentationTargetPackage
                } else {
                    null
                },
            )

            if (moduleInfo.sourceDirs.isEmpty() && resourceDirs.isEmpty() && assetDirs.isEmpty() && moduleDependencies.isEmpty()) {
                noSourceModules[module.name] = moduleInfo
                return@forEach
            }
            modules[moduleInfo.name] = moduleInfo
            addedModules.add("add ${moduleInfo.name}(origin: ${module.name}) -> $moduleInfo, brokenFields: ${info.brokenFields}")
        }

        if (noSourceModules.isNotEmpty()) {
            val ignoredModules = mutableSetOf<String>()
            val addedNoSourceModules = mutableSetOf<String>()
            noSourceModules.forEach { (originName, moduleInfo) ->
                if (modules[moduleInfo.name] == null) {
                    addedNoSourceModules.add(originName)
                    modules[moduleInfo.name] = moduleInfo
                    addedModules.add("add ${moduleInfo.name}(origin: $originName) -> $moduleInfo")
                } else {
                    ignoredModules.add(originName)
                }
            }
            logger.debug("add ignore modules (no source module): ${addedNoSourceModules.joinToString(", ")}")
            logger.debug("ignore modules (no source module): ${ignoredModules.joinToString(", ")}")
        }
        logIgnoredModules(
            directoryNotFoundModules,
            ideaFolderModules,
            notGradleModules,
            testModules,
            filteredAndroidTestModules,
            filteredAndroidTestReasons,
        )
        logger.debug(addedModules.joinToString("\n"))
        logger.debug("getLibraryDependencies total $totalCount, hitCacheCount $hitCacheCount, unHitCacheCount ${totalCount - hitCacheCount}")
        logger.debug("total ${modules.size} modules loaded")
        TimeLogger.end("initModuleRoots", logger)
        return JuggProjectInfo(modules, agpR8Classpath = null)
    }

    private fun logIgnoredModules(
        directoryNotFoundModules: Set<String>,
        ideaFolderModules: Set<String>,
        notGradleModules: Set<String>,
        testModules: Set<String>,
        filteredAndroidTestModules: Set<String>,
        filteredAndroidTestReasons: Map<String, Int>,
    ) {
        if (directoryNotFoundModules.isNotEmpty()) {
            logger.debug("ignore modules (module directory not found): ${directoryNotFoundModules.joinToString(", ")}")
        }
        if (ideaFolderModules.isNotEmpty()) {
            logger.debug("ignore modules (in .idea folder): ${ideaFolderModules.joinToString(", ")}")
        }
        if (notGradleModules.isNotEmpty()) {
            logger.debug("ignore modules (not gradle module): ${notGradleModules.joinToString(", ")}")
        }
        if (testModules.isNotEmpty()) {
            logger.debug("ignore modules (test module): ${testModules.joinToString(", ")}")
        }
        if (filteredAndroidTestModules.isNotEmpty()) {
            logger.debug(
                "ignore modules (invalid IDE androidTest module): ${filteredAndroidTestModules.joinToString(", ")}, " +
                    "reasons=$filteredAndroidTestReasons"
            )
        }
    }

    private fun File.guessIsResDir(): Boolean {
        return listFiles()?.any {
            it.name.startsWith("drawable") || it.name.startsWith("layout") ||
                it.name.startsWith("values") || it.name.startsWith("mipmap")
        } == true
    }

    private fun loadKnownGradleAndroidTestModuleNames(): Set<String>? {
        val result = mutableSetOf<String>()
        var hasGradleProjectInfo = false
        createGradleProjectInfoSerializers(pathManager, logger).forEach { serializer ->
            val gradleProjectInfo = serializer.load() ?: return@forEach
            hasGradleProjectInfo = true
            gradleProjectInfo.modules.values.forEach { moduleInfo ->
                if (ModulePathMergePolicy.classify(moduleInfo) == ModulePathMergePolicy.ModuleSourceKind.AndroidTest &&
                    moduleInfo.isAndroidTestModule
                ) {
                    result.add(moduleInfo.name)
                }
            }
        }
        return if (hasGradleProjectInfo) result else null
    }

    private fun Collection<File>.hasJavaOrKotlinSourceFile(): Boolean {
        return any { sourceDir ->
            sourceDir.exists() && sourceDir.walkTopDown().any { file ->
                file.isFile && (file.extension == "java" || file.extension == "kt")
            }
        }
    }

    private val String.moduleSimpleName: String
        get() {
            var name = replace(Regex("~\\d+$"), "")
            listOf(".main", ".test", ".debug").forEach { suffix ->
                if (name.endsWith(suffix)) {
                    name = name.substring(0, name.length - suffix.length)
                }
            }
            val splits = name.split('.')
            return if (splits.size <= 1) name else splits.subList(1, splits.size).joinToString(".")
        }

    companion object {
        fun getAndroidSdkRootDir(logger: Logger): File? {
            val allJdks = ProjectJdkTable.getInstance().allJdks
            logger.debug("All available jdks: ${allJdks.map { "${it.name}: ${it.versionString} (${it.homePath})" }}")
            val androidJdks = allJdks.filter { sdk ->
                val homeDirectory = sdk.homeDirectory ?: return@filter false
                if (!homeDirectory.exists()) return@filter false
                val subDirs = VfsUtil.virtualToIoFile(homeDirectory).listFiles() ?: return@filter false
                val platformsDir = subDirs.firstOrNull { it.name == "platforms" } ?: return@filter false
                if (platformsDir.listFiles().isNullOrEmpty()) return@filter false
                val buildToolsDir = subDirs.firstOrNull { it.name == "build-tools" } ?: return@filter false
                !buildToolsDir.listFiles().isNullOrEmpty()
            }
            logger.debug("All available android jdks: $androidJdks")
            return androidJdks.firstOrNull()?.homeDirectory?.let(VfsUtil::virtualToIoFile)
        }
    }
}
