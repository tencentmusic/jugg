package com.sickworm.intellij.jugg.project

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.projectRoots.ProjectJdkTable
import com.intellij.openapi.roots.*
import com.intellij.openapi.vfs.VfsUtil
import com.sickworm.intellij.jugg.apk.ApkInfo
import com.sickworm.intellij.jugg.compiler.*
import com.sickworm.intellij.jugg.compiler.custom.CustomCompilerManager
import com.sickworm.intellij.jugg.deploy.CompileContextInfo
import com.sickworm.intellij.jugg.deploy.DeployFileManager
import com.sickworm.intellij.jugg.deploy.IDeployHistoryManager
import com.sickworm.intellij.jugg.deploy.run.AsDeployerCompat
import com.sickworm.intellij.jugg.deploy.run.IdeModuleInfo
import com.sickworm.intellij.jugg.gradle.compile.LocalGradleCompileClient
import com.sickworm.intellij.jugg.gradle.compile.isChild
import com.sickworm.intellij.jugg.ide.logic.TestModeManager
import com.sickworm.intellij.jugg.logger.JuggLogger
import com.sickworm.intellij.jugg.logger.TimeLogger
import com.sickworm.intellij.jugg.project.data.*
import com.sickworm.intellij.jugg.project.merger.IJuggProjectInfoMerger
import com.sickworm.intellij.jugg.project.merger.JuggProjectInfoMerger
import com.sickworm.intellij.jugg.server.protocols.ModuleCustomConfig
import org.jetbrains.android.sdk.AndroidSdkAdditionalData
import org.jetbrains.jps.model.java.JavaResourceRootType
import org.jetbrains.jps.model.java.JavaSourceRootType
import java.io.File

/**
 * Manage [ICompileContext] for JuggCompiler.
 */
class CompileContextManager(
    private val project: Project,
    private val pathManager: JuggPathManager,
    private val deployFileManager: DeployFileManager,
    private val deployHisManager: IDeployHistoryManager,
    private val customCompilerManager: CustomCompilerManager,
    private val moduleManager: ModuleManager = AsDeployerCompat.getModuleManager(project), // mock
    private val logger: Logger = JuggLogger.getInstance(project, "CompileContextManager"),
) {

    private val projectInfoSerializer = ProjectInfoSerializer(pathManager.ideProjectInfoFile, logger)
    private var allGradleProjectInfoSerializerList = emptyList<ProjectInfoSerializer>()
    private val juggProjectInfoMerger: IJuggProjectInfoMerger = JuggProjectInfoMerger(logger)

    private val compileContextInside: BaseCompileContext by lazy { createCompileContext() }

    val compileContext: ICompileContext
        get() = compileContextInside

    private var compileContextInfo: CompileContextInfo? = null

    /**
     * Invoke after full build. CompileContextInfo will provides class path
     */
    fun setCompileContext(compileContextInfo: CompileContextInfo) {
        logger.debug("setCompileContext")
        ensureInitProjectInfo()
        this.compileContextInfo = compileContextInfo
        val projectInfo = getProjectInfo()
        val copyModules = buildEffectiveModules(projectInfo.modules, compileContextInfo)
        compileContextInside.update(
            apkInfos = compileContextInfo.apkInfos,
            modules = copyModules,
            agpR8Classpath = projectInfo.agpR8Classpath,
        )
    }

    /**
     * Invoke after IDE sync and IDE project info is updated.
     */
    fun updateCompileContext(
        isAfterSync: Boolean,
        preferGradleLibraryDependencies: Boolean = false,
        updateGradleAsync: () -> Unit,
    ): Boolean {
        logger.debug("updateCompileContext isAfterSync: $isAfterSync, " +
                "preferGradleLibraryDependencies: $preferGradleLibraryDependencies")

        ensureInitProjectInfo()

        var isNeedReloadProjectInfo = isAfterSync
        if (!isAfterSync && projectInfoSerializer.load()?.checkMissing("ide", logger) == true) {
            logger.debug("updateCompileContext ide checkMissing true, reload project info")
            isNeedReloadProjectInfo = true
        }
        if (isNeedReloadProjectInfo) {
            updateProjectInfoFromIde(isNeedReloadProjectInfo = true)
            juggProjectInfoMerger.afterSync(
                projectInfoSerializer,
                currentBuildTarget(),
                preferGradleLibraryDependencies,
            )
            val projectInfo = getProjectInfo()
            compileContextInside.update(
                apkInfos = compileContextInfo?.apkInfos,
                modules = buildEffectiveModules(projectInfo.modules),
                agpR8Classpath = projectInfo.agpR8Classpath,
            )
        }

        var isFixGradleProjectInfo = false
        allGradleProjectInfoSerializerList.forEach { gradleProjectInfoSerializer ->
            if (gradleProjectInfoSerializer.load()?.checkMissing("gradle", logger) == true) {
                logger.debug("updateCompileContext gradle checkMissing true, reload gradle project info")
                isFixGradleProjectInfo = true
            }
        }
        if (isFixGradleProjectInfo) {
            updateGradleAsync()
        }

        val isFixIdeProjectInfo = !isAfterSync && isNeedReloadProjectInfo
        if (isFixIdeProjectInfo) {
            val isMissing = projectInfoSerializer.load()?.checkMissing("ide", logger)
            logger.debug("updateCompileContext ide double checkMissing $isMissing, (won't do again if still missing)")
        }

        return isNeedReloadProjectInfo
    }

    /**
     * Try to find out missing libraries by merge.
     * @return true if fix some dependencies.
     */
    fun triggerMerge(): Boolean {
        val result = juggProjectInfoMerger.afterSync(projectInfoSerializer, currentBuildTarget())
        return result.isFixMissingOrDelete
    }

    /**
     * Invoke after use confirm incremental compile libraries.
     */
    fun updateTempLibraries(addedTempLibraries: List<LibraryDependency>?, removedTempLibraries: List<LibraryDependency>?) {
        logger.debug("updateTempLibraries addedTempLibraries: $addedTempLibraries, removedTempLibraries: $removedTempLibraries")
        compileContextInside.update(addedTempLibraries = addedTempLibraries, removedTempLibraries = removedTempLibraries)
    }

    fun updateApkInfos(apkInfos: List<ApkInfo>) {
        compileContextInside.update(apkInfos = apkInfos)
    }

    /**
     * Invoke after Gradle project info is updated.
     */
    fun updateCompileContextAfterLocalFetch(
        buildTarget: BuildTarget = currentBuildTarget(),
    ) {
        logger.debug("updateCompileContextAfterLocalFetch")
        ensureInitProjectInfo()

        allGradleProjectInfoSerializerList = getAllGradleProjectInfo()
        juggProjectInfoMerger.afterLocalFetch(allGradleProjectInfoSerializerList, buildTarget)
        val projectInfo = getProjectInfo()
        compileContextInside.update(
            apkInfos = compileContextInfo?.apkInfos,
            modules = buildEffectiveModules(projectInfo.modules),
            agpR8Classpath = projectInfo.agpR8Classpath,
        )
    }

    private fun getAllGradleProjectInfo(): List<ProjectInfoSerializer> {
        val newGradleInfos = mutableListOf<ProjectInfoSerializer>()
        val gradleProjectInfoSerializer = ProjectInfoSerializer(pathManager.gradleProjectInfoFile, logger)
        newGradleInfos.add(gradleProjectInfoSerializer)
        if (pathManager.gradleIncludeBuildsFile.exists()) {
            val newIncludeGradleInfos = pathManager.gradleIncludeBuildsFile.readLines().map {
                ProjectInfoSerializer(File(it), logger)
            }
            newGradleInfos.addAll(newIncludeGradleInfos)
        }
        return newGradleInfos
    }

    fun getProjectInfo(): JuggProjectInfo {
        var juggProjectInfo = juggProjectInfoMerger.juggProjectInfo ?: initProjectInfo()
        if (moduleCustomConfigs.isNotEmpty()) {
            val modules = juggProjectInfo.modules.mapValues { (_, module) ->
                val config = moduleCustomConfigs.find { it.moduleStdPath == module.moduleStdPath }
                if (config == null) {
                    return@mapValues module
                }
                return@mapValues module.copy(buildPathInfo = module.buildPathInfo.copy(
                    customClasspath = config.customClasspath,
                    customSyncFilePath = config.customSyncFilePath,
                ))
            }
            juggProjectInfo = juggProjectInfo.copy(modules = modules)
        }
        return juggProjectInfo
    }

    private var moduleCustomConfigs: List<ModuleCustomConfig> = emptyList()

    fun updateCustomClasspath(moduleCustomConfigs: List<ModuleCustomConfig>) {
        if (this.moduleCustomConfigs == moduleCustomConfigs) {
            return
        }
        logger.debug("updateCustomClasspath: $moduleCustomConfigs")
        this.moduleCustomConfigs = moduleCustomConfigs

        val projectInfo = getProjectInfo()
        compileContextInside.update(
            modules = buildEffectiveModules(projectInfo.modules),
            agpR8Classpath = projectInfo.agpR8Classpath,
        )
    }

    fun ensureInitProjectInfo() {
        if (juggProjectInfoMerger.juggProjectInfo == null) {
            logger.info("Initializing project info...")
            val startTime = System.currentTimeMillis()
            getProjectInfo()
            val costTime = (System.currentTimeMillis() - startTime) / 1000
            logger.info("Initializing project info done, cost ${costTime}s.")
        }
    }

    private fun initProjectInfo(): JuggProjectInfo {
        val ideJuggProjectInfo = updateProjectInfoFromIde(isNeedReloadProjectInfo = false)
        val buildTarget = currentBuildTarget()
        juggProjectInfoMerger.afterSync(projectInfoSerializer, buildTarget)
        allGradleProjectInfoSerializerList = getAllGradleProjectInfo()
        juggProjectInfoMerger.afterLocalFetch(allGradleProjectInfoSerializerList, buildTarget)
        return juggProjectInfoMerger.juggProjectInfo ?: run {
            logger.warn("JuggProjectInfoMerger returns null, which should not happened.")
            return@run ideJuggProjectInfo
        }
    }

    private fun currentBuildTarget(): BuildTarget {
        return deployHisManager.getFullBuildInfo()?.buildTarget ?: BuildTarget.APP
    }

    private fun buildEffectiveModules(
        baseModules: Map<String, ModuleInfo>,
        compileContextInfo: CompileContextInfo? = this.compileContextInfo,
    ): Map<String, ModuleInfo> {
        if (compileContextInfo == null) {
            return baseModules
        }

        val guessBuildPathBaseDir: File? = baseModules.firstNotNullOfOrNull { (name, module) ->
            val newBuildPathInfo = compileContextInfo.moduleBuildPathInfos[name] ?: return@firstNotNullOfOrNull null
            val relativePath = module.buildPathInfo.buildDir.relativeTo(module.buildPathInfo.projectRootDir)
            if (newBuildPathInfo.buildDir.endsWith(relativePath)) {
                return@firstNotNullOfOrNull File(newBuildPathInfo.buildDir.absolutePath.substringBefore(relativePath.absolutePath))
            } else {
                return@firstNotNullOfOrNull null
            }
        }

        return baseModules.map { (name, module) ->
            val newBuildPathInfo = compileContextInfo.moduleBuildPathInfos[name]
            if (newBuildPathInfo != null) {
                return@map name to module.copy(buildPathInfo = newBuildPathInfo.copy(
                    customClasspath = module.buildPathInfo.customClasspath,
                    customSyncFilePath = module.buildPathInfo.customSyncFilePath,
                ))
            }

            logger.info("build path of module($name) is missing, maybe module is synced after full build. " +
                    "Try to guess build path by guessBuildPathBaseDir=$guessBuildPathBaseDir")
            if (guessBuildPathBaseDir == null) {
                logger.warn("guess build path guessBuildPathBaseDir not valid, use old build path: ${module.buildPathInfo}")
                return@map name to module
            }

            val guessedBuildPathInfo = ModuleBuildPathInfo(
                module.projectRootDir,
                module.moduleRootDir.changeBaseDir(module.projectRootDir, guessBuildPathBaseDir),
                module.buildVariant,
                customClasspath = module.buildPathInfo.customClasspath,
                customSyncFilePath = module.buildPathInfo.customSyncFilePath,
                buildDirRelativePath = module.buildPathInfo.buildDirRelativePath,
            )
            if (guessedBuildPathInfo.buildDir.exists()) {
                logger.debug("guess build path success: ${guessedBuildPathInfo.buildDir}")
                return@map name to module.copy(buildPathInfo = guessedBuildPathInfo)
            } else {
                logger.debug("guess build path can't find build path for module $name, " +
                        "tried: ${guessedBuildPathInfo.buildDir}, " +
                        "use old build path: ${module.buildPathInfo}")
                return@map name to module
            }
        }.toMap()
    }

    private fun createCompileContext(): BaseCompileContext {
        TimeLogger.start("createCompileContext")
        val androidHome = getAndroidSdkRootDir(logger)
        logger.debug("Use android sdk home: $androidHome")
        if (androidHome == null) {
            throw JuggException.androidHomeNotFound()
        }

        val projectInfo = getProjectInfo()
        val context = BaseCompileContext(
            logger = JuggLogger.getInstance(project, "BaseCompileContext"),
            androidHome = androidHome,
            tempCompileDir = File(pathManager.compileRootDir, "compiled"),
            tempModuleDir = File(pathManager.compileRootDir, "temp_module"),
            modules = buildEffectiveModules(projectInfo.modules),
            projectDir = pathManager.projectDir,
            agpR8Classpath = projectInfo.agpR8Classpath,
            deployFileManager = deployFileManager,
            deployHistoryManager = deployHisManager,
            customCompilerManager = customCompilerManager,
            incrementalDataDir = File(pathManager.compileRootDir, "incremental"),
            cmdCompileEnv = LocalGradleCompileClient.buildCompileEnv(project, logger),
            scene = ICompileContext.Scene.IDE,
        )
        TimeLogger.end("createCompileContext", logger)
        return context
    }

    private fun updateProjectInfoFromIde(isNeedReloadProjectInfo: Boolean): JuggProjectInfo {
        logger.debug("getAllModulesByModuleManager isNeedReloadProjectInfo: $isNeedReloadProjectInfo")
        if (!isNeedReloadProjectInfo) {
            val cache = projectInfoSerializer.load()
            logger.debug("Try to load project info from cache, is success: ${cache != null}")
            if (cache != null) {
                return cache
            }
        }

        val juggProjectInfo = doGetAllModulesByModuleManager()
        projectInfoSerializer.save(juggProjectInfo)
        return juggProjectInfo
    }

    private fun doGetAllModulesByModuleManager(): JuggProjectInfo {
        TimeLogger.start("initModuleRoots")
        logger.debug("Start init module roots")

        val currentBuildTarget: BuildTarget = deployHisManager.getFullBuildInfo()?.buildTarget ?: BuildTarget.APP
        val dependencyCache = loadLibraryDependencyCache()
        val dependencyStats = LibraryDependencyStats()
        val scanResult = ModuleScanResult()
        val modules = mutableMapOf<String, ModuleInfo>()
        val knownGradleAndroidTestModuleNames = loadKnownGradleAndroidTestModuleNames()
        logger.debug("known Gradle androidTest modules: ${knownGradleAndroidTestModuleNames?.joinToString(", ")}")
        moduleManager.modules.forEach { module ->
            val moduleInfo = readModuleInfo(
                module,
                currentBuildTarget,
                knownGradleAndroidTestModuleNames,
                dependencyCache,
                dependencyStats,
                scanResult,
            ) ?: return@forEach
            modules[moduleInfo.name] = moduleInfo
        }

        addUniqueNoSourceModules(modules, scanResult)
        logModuleScanResult(modules.size, dependencyStats, scanResult)
        TimeLogger.end("initModuleRoots", logger)
        return JuggProjectInfo(
            modules = modules,
            agpR8Classpath = null,
        )
    }

    private fun loadLibraryDependencyCache(): MutableMap<String, LibraryDependency> {
        val result = mutableMapOf<String, LibraryDependency>()
        projectInfoSerializer.load()?.modules?.values?.forEach { moduleInfo ->
            moduleInfo.libraryDependencies.forEach { dependency ->
                val key = "${dependency.file.absolutePath}:${dependency.lastModifiedTime}"
                result.putIfAbsent(key, dependency)
            }
        }
        return result
    }

    private fun readModuleInfo(
        module: Module,
        currentBuildTarget: BuildTarget,
        knownGradleAndroidTestModuleNames: Set<String>?,
        dependencyCache: MutableMap<String, LibraryDependency>,
        dependencyStats: LibraryDependencyStats,
        scanResult: ModuleScanResult,
    ): ModuleInfo? {
        val candidate = createModuleCandidate(module, currentBuildTarget, scanResult) ?: return null
        val moduleRootManager = ModuleRootManager.getInstance(module)
        val sourceDirs = collectSourceDirs(moduleRootManager, candidate)
        if (shouldFilterAndroidTestModule(candidate, sourceDirs, knownGradleAndroidTestModuleNames, scanResult)) {
            return null
        }
        val resources = collectResourceDirs(module, moduleRootManager, candidate.buildPathInfo)
        val dependencies = collectDependencies(moduleRootManager, dependencyCache, dependencyStats)
        val moduleInfo = createModuleInfo(candidate, sourceDirs, resources, dependencies)
        if (moduleInfo.sourceDirs.isEmpty() && resources.resourceDirs.isEmpty() &&
            resources.assetDirs.isEmpty() && dependencies.moduleDependencies.isEmpty()
        ) {
            scanResult.noSourceModules[module.name] = moduleInfo
            return null
        }
        scanResult.addedModules.add("add ${moduleInfo.name}(origin: ${module.name}) -> $moduleInfo, " +
                "brokenFields: ${candidate.ideModuleInfo.brokenFields}")
        return moduleInfo
    }

    private fun createModuleCandidate(
        module: Module,
        currentBuildTarget: BuildTarget,
        scanResult: ModuleScanResult,
    ): IdeModuleCandidate? {
        val ideModuleInfo = readIdeModuleInfo(module) ?: run {
            scanResult.notGradleModules.add(module.name)
            return null
        }
        val baseDir = ideModuleInfo.baseDir ?: run {
            scanResult.directoryNotFoundModules.add(module.name)
            return null
        }
        val normalizedBaseDir = if (baseDir.isAbsolute) baseDir else File(pathManager.projectDir, baseDir.path)
        val relativePath = if (baseDir.isAbsolute) baseDir.relativeTo(pathManager.projectDir) else baseDir
        if (relativePath.startsWith(".idea")) {
            scanResult.ideaFolderModules.add(module.name)
            return null
        }

        val stdModuleName = module.name.replace(Regex("~\\d+$"), "")
        if (ModulePathMergePolicy.shouldSkipIdeModule(stdModuleName, currentBuildTarget)) {
            scanResult.testModules.add(module.name)
            return null
        }
        if (baseDir.name.moduleSimpleName == "buildSrc" && relativePath.path.startsWith("buildSrc")) {
            return null
        }

        logMinifyEnabled(module, ideModuleInfo)
        val buildVariant = ModulePathMergePolicy.selectIdeBuildVariant(stdModuleName, ideModuleInfo.buildVariant)
        return IdeModuleCandidate(
            originName = module.name,
            moduleName = module.name.moduleSimpleName,
            normalizedBaseDir = normalizedBaseDir,
            manifestFile = ideModuleInfo.manifestRelativePath?.let { File(normalizedBaseDir, it) },
            buildPathInfo = createModuleBuildPathInfo(normalizedBaseDir, buildVariant, ideModuleInfo.buildDir),
            buildVariant = buildVariant,
            isAndroidTest = ModulePathMergePolicy.classifyByName(stdModuleName) ==
                    ModulePathMergePolicy.ModuleSourceKind.AndroidTest,
            ideModuleInfo = ideModuleInfo,
        )
    }

    private fun readIdeModuleInfo(module: Module): IdeModuleInfo? {
        return try {
            AsDeployerCompat.getIdeModuleInfo(project, module, logger, false)
        } catch (e: Throwable) {
            if (TestModeManager.isTestMode) {
                throw e
            }
            AsDeployerCompat.getIdeModuleInfo(project, module, logger, true)
        }
    }

    private fun logMinifyEnabled(module: Module, ideModuleInfo: IdeModuleInfo) {
        if (ideModuleInfo.minifyEnabled != null && ideModuleInfo.toString() != "null") {
            logger.debug("module ${module.name} find minifyEnabled: " +
                    "${ideModuleInfo.buildVariant} -> ${ideModuleInfo.minifyEnabled}")
        }
    }

    private fun createModuleBuildPathInfo(
        normalizedBaseDir: File,
        buildVariant: String,
        buildDir: File?,
    ): ModuleBuildPathInfo {
        val buildDirRelativePath = buildDir?.let {
            val absoluteBuildDir = if (it.isAbsolute) it else File(pathManager.projectDir, it.path)
            runCatching { absoluteBuildDir.relativeTo(pathManager.projectDir).path }.getOrNull()
        }
        return ModuleBuildPathInfo(
            pathManager.projectDir,
            normalizedBaseDir,
            buildVariant,
            buildDirRelativePath = buildDirRelativePath
                ?: File(normalizedBaseDir, "build").relativeTo(pathManager.projectDir).path,
        )
    }

    private fun collectSourceDirs(
        moduleRootManager: ModuleRootManager,
        candidate: IdeModuleCandidate,
    ): Set<File> {
        val sourceRootTypes = mutableSetOf(
            JavaSourceRootType.SOURCE,
            org.jetbrains.kotlin.config.SourceKotlinRootType,
        )
        if (candidate.isAndroidTest) {
            sourceRootTypes.add(JavaSourceRootType.TEST_SOURCE)
            sourceRootTypes.add(org.jetbrains.kotlin.config.TestSourceKotlinRootType)
        }
        return moduleRootManager.getSourceRoots(sourceRootTypes)
            .filter { file -> moduleRootManager.excludeRoots.all { !file.path.startsWith(it.path) } }
            .map(VfsUtil::virtualToIoFile)
            .filterNot { it.isChild(candidate.buildPathInfo.buildDir) }
            .toSet()
    }

    private fun shouldFilterAndroidTestModule(
        candidate: IdeModuleCandidate,
        sourceDirs: Set<File>,
        knownGradleAndroidTestModuleNames: Set<String>?,
        scanResult: ModuleScanResult,
    ): Boolean {
        if (!candidate.isAndroidTest) {
            return false
        }
        val info = candidate.ideModuleInfo
        val hasSourceFiles = sourceDirs.hasJavaOrKotlinSourceFile()
        val filterReason = ModulePathMergePolicy.getIdeAndroidTestCandidateFilterReason(
            applicationId = info.androidTestApplicationId,
            instrumentationTargetPackage = info.androidTestInstrumentationTargetPackage,
            hasSourceFiles = hasSourceFiles,
        )
        logger.trace("IDE androidTest candidate: module=${candidate.originName}, simpleName=${candidate.moduleName}, " +
                "applicationId=${info.androidTestApplicationId}, " +
                "instrumentationTargetPackage=${info.androidTestInstrumentationTargetPackage}, " +
                "hasSourceFiles=$hasSourceFiles, " +
                "knownGradleAndroidTestModules=${knownGradleAndroidTestModuleNames?.size ?: "null"}, " +
                "knownGradleContains=${knownGradleAndroidTestModuleNames?.contains(candidate.moduleName)}, " +
                "include=${filterReason == null}, reason=${filterReason ?: "included"}")
        if (filterReason == null) {
            return false
        }
        scanResult.filteredAndroidTestModules.add(candidate.originName)
        scanResult.filteredAndroidTestReasons[filterReason] =
            (scanResult.filteredAndroidTestReasons[filterReason] ?: 0) + 1
        return true
    }

    private fun collectResourceDirs(
        module: Module,
        moduleRootManager: ModuleRootManager,
        buildPathInfo: ModuleBuildPathInfo,
    ): ModuleResources {
        val resourceDirs = mutableSetOf<File>()
        val assetDirs = mutableSetOf<File>()
        moduleRootManager.getSourceRoots(
            setOf(JavaResourceRootType.RESOURCE, org.jetbrains.kotlin.config.ResourceKotlinRootType)
        ).map(VfsUtil::virtualToIoFile)
            .filterNot { it.isChild(buildPathInfo.buildDir) }
            .forEach { file -> addResourceDir(module, file, resourceDirs, assetDirs) }
        return ModuleResources(resourceDirs, assetDirs)
    }

    private fun addResourceDir(
        module: Module,
        file: File,
        resourceDirs: MutableSet<File>,
        assetDirs: MutableSet<File>,
    ) {
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

    private fun collectDependencies(
        moduleRootManager: ModuleRootManager,
        dependencyCache: MutableMap<String, LibraryDependency>,
        dependencyStats: LibraryDependencyStats,
    ): ModuleDependencies {
        val moduleDependencies = mutableListOf<ModuleDependency>()
        val libraryDependencies = mutableListOf<LibraryDependency>()
        var compileVersion: String? = null
        moduleRootManager.orderEntries.forEach { entry ->
            when (entry) {
                is ModuleOrderEntry -> moduleDependencies.add(ModuleDependency(entry.moduleName.moduleSimpleName))
                is LibraryOrderEntry -> collectLibraryDependencies(
                    entry,
                    dependencyCache,
                    dependencyStats,
                    libraryDependencies,
                )
                is ModuleJdkOrderEntry -> compileVersion = readAndroidCompileVersion(entry) ?: compileVersion
            }
        }
        return ModuleDependencies(moduleDependencies, libraryDependencies, compileVersion)
    }

    private fun collectLibraryDependencies(
        entry: LibraryOrderEntry,
        dependencyCache: MutableMap<String, LibraryDependency>,
        dependencyStats: LibraryDependencyStats,
        result: MutableList<LibraryDependency>,
    ) {
        entry.getRootFiles(OrderRootType.CLASSES).forEach { file ->
            val ioFile = VfsUtil.virtualToIoFile(file)
            if (ioFile.name == "kaptGeneratedClasses" && (!ioFile.exists() || ioFile.isDirectory)) {
                return@forEach
            }
            val key = "${ioFile.absolutePath}:${ioFile.lastModified()}"
            val cachedDependency = dependencyCache[key]
            val dependency = cachedDependency ?: createLibraryDependency(entry, ioFile).also {
                dependencyCache[key] = it
            }
            if (cachedDependency != null) {
                dependencyStats.hitCacheCount++
            }
            dependencyStats.totalCount++
            result.add(dependency)
        }
    }

    private fun createLibraryDependency(entry: LibraryOrderEntry, file: File): LibraryDependency {
        var name = entry.libraryName ?: file.name
        if (name.startsWith("Gradle: ")) {
            name = name.substring("Gradle: ".length)
        }
        if (name.endsWith("@aar")) {
            name = name.substring(0, name.length - "@aar".length)
        }
        return LibraryDependency(name, file)
    }

    private fun readAndroidCompileVersion(entry: ModuleJdkOrderEntry): String? {
        if (entry.jdkTypeName != "Android SDK") {
            return null
        }
        val additionalData = entry.jdk?.sdkAdditionalData as? AndroidSdkAdditionalData
        val buildTarget = additionalData?.buildTargetHashString ?: return null
        if (!buildTarget.startsWith("android-")) {
            return null
        }
        return buildTarget.substringAfter("android-").substringBefore("-ext")
    }

    private fun createModuleInfo(
        candidate: IdeModuleCandidate,
        sourceDirs: Set<File>,
        resources: ModuleResources,
        dependencies: ModuleDependencies,
    ): ModuleInfo {
        val info = candidate.ideModuleInfo
        val hasAndroidTestMetadata = candidate.isAndroidTest && ModulePathMergePolicy.hasValidAndroidTestMetadata(
            info.androidTestApplicationId,
            info.androidTestInstrumentationTargetPackage,
        )
        return ModuleInfo(
            name = candidate.moduleName,
            moduleType = if (hasAndroidTestMetadata) ModuleInfo.Type.Library else ModuleInfo.Type.Unknown,
            moduleRootDir = candidate.normalizedBaseDir,
            projectRootDir = pathManager.projectDir,
            sourceDirs = sourceDirs.toList(),
            resourceDirs = resources.resourceDirs.toList(),
            assetsDirs = resources.assetDirs.toList(),
            manifestFile = candidate.manifestFile,
            manifestPlaceHolders = null,
            buildVariant = candidate.buildVariant,
            compileVersion = dependencies.compileVersion ?: info.compileVersion,
            minSdkVersion = info.minSdkVersion,
            buildToolsVersion = info.buildToolsVersion,
            kotlinJvmTarget = info.kotlinJvmTarget,
            kotlinFreeCompilerArgs = info.kotlinFreeCompilerArgs ?: emptyList(),
            javaSourceCompatibility = info.javaSourceCompatibility,
            javaTargetCompatibility = info.javaTargetCompatibility,
            buildPathInfo = candidate.buildPathInfo,
            moduleDependencies = dependencies.moduleDependencies,
            libraryDependencies = dependencies.libraryDependencies,
            runtimeLibraryDependencies = emptyList(),
            annotationProcessorDependencies = emptyList(),
            kaptDependencies = emptyList(),
            applicationId = info.androidTestApplicationId.takeIf { hasAndroidTestMetadata },
            instrumentationTargetPackage = info.androidTestInstrumentationTargetPackage.takeIf { hasAndroidTestMetadata },
        )
    }

    private fun addUniqueNoSourceModules(
        modules: MutableMap<String, ModuleInfo>,
        scanResult: ModuleScanResult,
    ) {
        if (scanResult.noSourceModules.isEmpty()) {
            return
        }
        val ignoredModules = mutableSetOf<String>()
        val addedModules = mutableSetOf<String>()
        scanResult.noSourceModules.forEach { (originName, moduleInfo) ->
            if (modules.putIfAbsent(moduleInfo.name, moduleInfo) == null) {
                addedModules.add(originName)
                scanResult.addedModules.add("add ${moduleInfo.name}(origin: $originName) -> $moduleInfo")
            } else {
                ignoredModules.add(originName)
            }
        }
        logger.debug("add ignore modules (no source module): ${addedModules.joinToString(", ")}")
        logger.debug("ignore modules (no source module): ${ignoredModules.joinToString(", ")}")
    }

    private fun logModuleScanResult(
        moduleCount: Int,
        dependencyStats: LibraryDependencyStats,
        scanResult: ModuleScanResult,
    ) {
        logIgnoredModules("module directory not found", scanResult.directoryNotFoundModules)
        logIgnoredModules("in .idea folder", scanResult.ideaFolderModules)
        logIgnoredModules("not gradle module", scanResult.notGradleModules)
        logIgnoredModules("test module", scanResult.testModules)
        if (scanResult.filteredAndroidTestModules.isNotEmpty()) {
            logger.debug("ignore modules (invalid IDE androidTest module): " +
                    "${scanResult.filteredAndroidTestModules.joinToString(", ")}, " +
                    "reasons=${scanResult.filteredAndroidTestReasons}")
        }
        logger.debug(scanResult.addedModules.joinToString("\n"))
        logger.debug("getLibraryDependencies total ${dependencyStats.totalCount}, " +
                "hitCacheCount ${dependencyStats.hitCacheCount}, " +
                "unHitCacheCount ${dependencyStats.totalCount - dependencyStats.hitCacheCount}")
        logger.debug("total $moduleCount modules loaded")
    }

    private fun logIgnoredModules(reason: String, modules: Set<String>) {
        if (modules.isNotEmpty()) {
            logger.debug("ignore modules ($reason): ${modules.joinToString(", ")}")
        }
    }

    private data class IdeModuleCandidate(
        val originName: String,
        val moduleName: String,
        val normalizedBaseDir: File,
        val manifestFile: File?,
        val buildPathInfo: ModuleBuildPathInfo,
        val buildVariant: String,
        val isAndroidTest: Boolean,
        val ideModuleInfo: IdeModuleInfo,
    )

    private data class ModuleResources(
        val resourceDirs: Set<File>,
        val assetDirs: Set<File>,
    )

    private data class ModuleDependencies(
        val moduleDependencies: List<ModuleDependency>,
        val libraryDependencies: List<LibraryDependency>,
        val compileVersion: String?,
    )

    private data class LibraryDependencyStats(
        var totalCount: Int = 0,
        var hitCacheCount: Int = 0,
    )

    private data class ModuleScanResult(
        val addedModules: MutableSet<String> = mutableSetOf(),
        val directoryNotFoundModules: MutableSet<String> = mutableSetOf(),
        val ideaFolderModules: MutableSet<String> = mutableSetOf(),
        val notGradleModules: MutableSet<String> = mutableSetOf(),
        val testModules: MutableSet<String> = mutableSetOf(),
        val filteredAndroidTestModules: MutableSet<String> = mutableSetOf(),
        val filteredAndroidTestReasons: MutableMap<String, Int> = mutableMapOf(),
        val noSourceModules: MutableMap<String, ModuleInfo> = mutableMapOf(),
    )

    private fun File.guessIsResDir(): Boolean {
        val files = listFiles() ?: return false
        return files.any {
            it.name.startsWith("drawable") ||
                    it.name.startsWith("layout") ||
                    it.name.startsWith("values") ||
                    it.name.startsWith("mipmap")
        }
    }

    private fun loadKnownGradleAndroidTestModuleNames(): Set<String>? {
        val result = mutableSetOf<String>()
        var hasGradleProjectInfo = false
        getAllGradleProjectInfo().forEach { projectInfoSerializer ->
            val gradleProjectInfo = projectInfoSerializer.load() ?: return@forEach
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

    companion object {

        fun getAndroidSdkRootDir(logger: Logger): File? {
            val allJdks = ProjectJdkTable.getInstance().allJdks
            val allJdkString = allJdks.map {
                it.name + (": ${it.versionString}") + " (" + it.homePath + ")"
            }
            logger.debug("All available jdks: $allJdkString")
            @Suppress("RedundantIf")
            val androidJdks = allJdks.filter { sdk ->
                val homeDirectory = sdk.homeDirectory ?: return@filter false
                if (!homeDirectory.exists()) return@filter false
                val subDirs = VfsUtil.virtualToIoFile(homeDirectory).listFiles() ?: return@filter false
                val platformsDir = subDirs.firstOrNull { it.name == "platforms" } ?: return@filter false
                if (platformsDir.listFiles().isNullOrEmpty()) return@filter false
                val buildToolsDir = subDirs.firstOrNull { it.name == "build-tools" } ?: return@filter false
                if (buildToolsDir.listFiles().isNullOrEmpty()) return@filter false
                return@filter true
            }
            logger.debug("All available android jdks: $androidJdks")

            val homeDirectory = androidJdks.firstOrNull()?.homeDirectory ?: return null
            return VfsUtil.virtualToIoFile(homeDirectory)
        }

        // e.g. name = example.lib_common
        // simpleName = lib_common
        // e.g. name = example.lib_common.main
        // simpleName = lib_common
        // e.g. name = example.lib_common.lib2.main
        // simpleName = lib_common.lib2
        // e.g. name = example.lib_common.lib2.main~1
        // simpleName = lib_common.lib2
        val String.moduleSimpleName: String get() {
            var name = this.replace(Regex("~\\d+$"), "")
            if (name.endsWith(".main")) {
                name = name.substring(0, name.length - ".main".length)
            }
            if (name.endsWith(".test")) {
                name = name.substring(0, name.length - ".test".length)
            }
            if (name.endsWith(".debug")) {
                name = name.substring(0, name.length - ".debug".length)
            }

            val splits = name.split('.')
            return when (splits.size) {
                0 -> name
                1 -> name
                else -> splits.subList(1, splits.size).joinToString(".")
            }
        }
    }
}

private fun JuggProjectInfo.checkMissing(name: String, logger: Logger): Boolean {
    val isMissingMainJarMap = mutableMapOf<String, Boolean>()
    var isMissing = false
    val transformsPath = ".gradle${File.separator}caches${File.separator}transforms"
    val mainJarPath = "${File.separator}jars${File.separator}classes.jar"
    val jarsInAarPath = "${File.separator}jars${File.separator}"
    modules.values.forEach modules@{ module ->
        module.libraryDependencies.forEach {
            if (!it.file.exists()) {
                isMissing = true
                logger.debug("Missing library dependency $it, path: ${it.file.path}")
            }
            val isInTransforms = it.file.path.contains(transformsPath)
            if (isInTransforms) {
                val isMainJar = it.file.path.contains(mainJarPath)
                if (isMainJar) {
                    isMissingMainJarMap[it.name] = false
                } else {
                    // it's in aar, not a single jar file
                    // single jar e.g. .gradle/caches/transforms-3/17e312c0844272be122cda16e44e6281/transformed/jetified-kotlin-android-extensions-runtime-1.7.20.jar
                    // aar e.g. .gradle/caches/transforms-3/52bab67b7bd54999d3274c1962b69133/transformed/jetified-sdk-for-jugg/jars/classes.jar
                    val isInAar = !it.isJar || it.file.path.contains(jarsInAarPath)
                    if (isInAar) {
                        isMissingMainJarMap.getOrPut(it.name) { true } // mark as maybe missing
                    }
                }
            }
        }
    }

    isMissingMainJarMap.forEach { (name, isMissingJar) ->
        if (isMissingJar) {
            logger.debug("Missing classes.jar $name")
            isMissing = true
        }
    }

    logger.debug("checkMissing for $name, isMissing: $isMissing")
    return isMissing
}
