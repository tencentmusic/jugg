package com.sickworm.intellij.jugg.project

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.projectRoots.ProjectJdkTable
import com.intellij.openapi.roots.*
import com.intellij.openapi.vfs.VfsUtil
import com.sickworm.intellij.jugg.compiler.*
import com.sickworm.intellij.jugg.deploy.CompileContextInfo
import com.sickworm.intellij.jugg.deploy.DeployFileManager
import com.sickworm.intellij.jugg.deploy.IDeployHistoryManager
import com.sickworm.intellij.jugg.deploy.run.AsDeployerCompat
import com.sickworm.intellij.jugg.gradle.compile.isChild
import com.sickworm.intellij.jugg.ide.logic.RuntimeMockUtils
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
    private val moduleManager: ModuleManager = AsDeployerCompat.getModuleManager(project), // mock
    private val logger: Logger = JuggLogger.getInstance(project, "CompileContextManager"),
) {

    private val projectInfoSerializer = ProjectInfoSerializer(pathManager.ideProjectInfoFile, logger)
    private var allGradleProjectInfoSerializerList = listOf<ProjectInfoSerializer>()
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
        updateCompileContextByFullBuildInfo(compileContextInfo)
    }

    /**
     * Invoke after IDE sync and IDE project info is updated.
     */
    fun updateCompileContext(isAfterSync: Boolean, updateGradleAsync: () -> Unit): Boolean {
        logger.debug("updateCompileContext isAfterSync: $isAfterSync")

        ensureInitProjectInfo()

        var isNeedReloadProjectInfo = isAfterSync
        if (!isAfterSync && projectInfoSerializer.load()?.checkMissing("ide", logger) == true) {
            logger.debug("updateCompileContext ide checkMissing true, reload project info")
            isNeedReloadProjectInfo = true
        }
        if (isNeedReloadProjectInfo) {
            updateProjectInfoFromIde(isNeedReloadProjectInfo = true)
            juggProjectInfoMerger.afterSync(projectInfoSerializer)
            compileContextInside.update(modules = getProjectInfo().modules)
            compileContextInfo?.let {
                updateCompileContextByFullBuildInfo(it)
            }
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
        val result = juggProjectInfoMerger.afterSync(projectInfoSerializer)
        return result.isFixMissingOrDelete
    }

    /**
     * Invoke after use confirm incremental compile libraries.
     */
    fun updateTempLibraries(addedTempLibraries: List<LibraryDependency>?, removedTempLibraries: List<LibraryDependency>?) {
        logger.debug("updateTempLibraries addedTempLibraries: $addedTempLibraries, removedTempLibraries: $removedTempLibraries")
        compileContextInside.update(addedTempLibraries = addedTempLibraries, removedTempLibraries = removedTempLibraries)
    }

    /**
     * Invoke after Gradle project info is updated.
     */
    fun updateCompileContextAfterLocalFetch() {
        logger.debug("updateCompileContextAfterLocalFetch")
        ensureInitProjectInfo()

        val newGradleInfos = mutableListOf<ProjectInfoSerializer>()
        val gradleProjectInfoSerializer = ProjectInfoSerializer(pathManager.gradleProjectInfoFile, logger)
        newGradleInfos.add(gradleProjectInfoSerializer)
        if (pathManager.gradleIncludeBuildsFile.exists()) {
            val newIncludeGradleInfos = pathManager.gradleIncludeBuildsFile.readLines().map {
                ProjectInfoSerializer(File(it), logger)
            }
            newGradleInfos.addAll(newIncludeGradleInfos)
        }
        this.allGradleProjectInfoSerializerList = newGradleInfos

        juggProjectInfoMerger.afterLocalFetch(newGradleInfos)
        compileContextInside.update(modules = getProjectInfo().modules)
        compileContextInfo?.let {
            updateCompileContextByFullBuildInfo(it)
        }
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

        compileContextInside.update(modules = getProjectInfo().modules)
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
        juggProjectInfoMerger.afterSync(projectInfoSerializer)
        juggProjectInfoMerger.afterLocalFetch(allGradleProjectInfoSerializerList)
        return juggProjectInfoMerger.juggProjectInfo ?: run {
            logger.warn("JuggProjectInfoMerger returns null, which should not happened.")
            return@run ideJuggProjectInfo
        }
    }

    private fun updateCompileContextByFullBuildInfo(compileContextInfo: CompileContextInfo) {
        val guessBuildPathBaseDir: File? = getProjectInfo().modules.firstNotNullOfOrNull { (name, module) ->
            val newBuildPathInfo = compileContextInfo.moduleBuildPathInfos[name] ?: return@firstNotNullOfOrNull null
            val relativePath = module.buildPathInfo.buildDir.relativeTo(module.buildPathInfo.projectRootDir)
            if (newBuildPathInfo.buildDir.endsWith(relativePath)) {
                return@firstNotNullOfOrNull File(newBuildPathInfo.buildDir.absolutePath.substringBefore(relativePath.absolutePath))
            } else {
                return@firstNotNullOfOrNull null
            }
        }

        val copyModules: Map<String, ModuleInfo> = getProjectInfo().modules.map { (name, module) ->
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
            )
            if (guessedBuildPathInfo.buildDir.exists()) {
                logger.info("guess build path success: ${guessedBuildPathInfo.buildDir}")
                return@map name to module.copy(buildPathInfo = guessedBuildPathInfo)
            } else {
                logger.warn("guess build path can't find build path for module $name, " +
                        "tried: ${guessedBuildPathInfo.buildDir}, " +
                        "use old build path: ${module.buildPathInfo}")
                return@map name to module
            }
        }.toMap()
        compileContextInside.update(apkInfos = compileContextInfo.apkInfos, modules = copyModules)
    }

    private fun createCompileContext(): BaseCompileContext {
        TimeLogger.start("createCompileContext")
        val androidHome = getAndroidSdkRootDir(logger)
        logger.debug("Use android sdk home: $androidHome")
        if (androidHome == null) {
            throw JuggException.androidHomeNotFound()
        }

        val context = BaseCompileContext(
            project,
            logger = JuggLogger.getInstance(project, "BaseCompileContext"),
            androidHome = androidHome,
            tempCompileDir = File(pathManager.compileRootDir, "compiled"),
            tempModuleDir = File(pathManager.compileRootDir, "temp_module"),
            modules = getProjectInfo().modules,
            projectDir = pathManager.projectDir,
            deployFileManager = deployFileManager,
            deployHistoryManager = deployHisManager,
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

        // use old cache to speed up library info reading
        val dependencyCacheMap = run {
            val result = mutableMapOf<String, LibraryDependency>()
            val oldModules = projectInfoSerializer.load()?.modules
            oldModules?.values?.forEach { moduleInfo ->
                moduleInfo.libraryDependencies.forEach {
                    val key = "${it.file.absolutePath}:${it.lastModifiedTime}"
                    if (!result.containsKey(key)) {
                        result[key] = it
                    }
                }
            }
            result
        }
        var totalCount = 0
        var hitCacheCount = 0

        val modules = mutableMapOf<String, ModuleInfo>()
        val addedModules = mutableSetOf<String>()
        val directoryNotFoundModules = mutableSetOf<String>()
        val ideaFolderModules = mutableSetOf<String>()
        val notGradleModules = mutableSetOf<String>()
        val testModules = mutableSetOf<String>()
        val noSourceModules = mutableMapOf<String, ModuleInfo>()
        moduleManager.modules.forEach { module ->

            // 1. guess base directory
            var ideModuleInfo = try {
                AsDeployerCompat.getIdeModuleInfo(project, module, logger, false)
            } catch (e: Throwable) {
                if (RuntimeMockUtils.isTestMode) {
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

            val relativePath = baseDir.relativeTo(pathManager.projectDir)
            if (relativePath.startsWith(".idea")) {
                ideaFolderModules.add(module.name)
                return@forEach
            }

            val stdModuleName = module.name.replace(Regex("~\\d+$"), "")
            if (stdModuleName.endsWith(".test") ||
                stdModuleName.endsWith(".androidTest") ||
                stdModuleName.endsWith(".unitTest")) {
                testModules.add(module.name)
                return@forEach
            }

            val isBuildSrc = baseDir.name.moduleSimpleName == "buildSrc"
                    && baseDir.relativeTo(pathManager.projectDir).path.startsWith("buildSrc")
            if (isBuildSrc) {
                return@forEach
            }

            if (ideModuleInfo.minifyEnabled != null && ideModuleInfo.toString() != "null") {
                // just log it
                logger.debug("module ${module.name} find minifyEnabled: ${ideModuleInfo.buildVariant} -> ${ideModuleInfo.minifyEnabled}")
            }

            var manifestFile: File? = null
            ideModuleInfo.manifestRelativePath?.let {
                manifestFile = File(baseDir, it)
            }
            val moduleBuildPathInfo = ModuleBuildPathInfo(pathManager.projectDir, baseDir, ideModuleInfo.buildVariant)

            // 3. find source roots
            val sourceDirs = mutableSetOf<File>()
            val resourceDirs = mutableSetOf<File>()
            val assetDirs = mutableSetOf<File>()

            val moduleRootManager = ModuleRootManager.getInstance(module)
            val subSourceRoots = moduleRootManager.getSourceRoots(
                setOf(
                    JavaSourceRootType.SOURCE,
                    org.jetbrains.kotlin.config.SourceKotlinRootType
                ))
                .filter { file ->
                    // ignore source in excludeRoots, etc. build
                    moduleRootManager.excludeRoots.all { !file.path.startsWith(it.path) }
                }
                .map {
                    VfsUtil.virtualToIoFile(it)
                }
                .filter {
                    return@filter !it.isChild(moduleBuildPathInfo.buildDir) // exclude generated source
                }
            sourceDirs.addAll(subSourceRoots)

            val subResourceRoots = moduleRootManager.getSourceRoots(
                setOf(
                    JavaResourceRootType.RESOURCE,
                    org.jetbrains.kotlin.config.ResourceKotlinRootType
                ))
            subResourceRoots.map {
                VfsUtil.virtualToIoFile(it)
            }.filter {
                return@filter !it.isChild(moduleBuildPathInfo.buildDir) // exclude generated source
            }.forEach { file ->
                if (file.name == "res") {
                    resourceDirs.add(file)
                } else if (file.name == "assets") {
                    assetDirs.add(file)
                } else {
                    val isResDir = file.guessIsResDir()
                    logger.debug("${module.name} unknown resource dir: ${file}, guess isResDir: $isResDir")
                    if (isResDir) {
                        resourceDirs.add(file)
                    } else {
                        assetDirs.add(file)
                    }
                }
            }

            // 4. find dependencies
            val moduleDependencies = mutableListOf<ModuleDependency>()
            val libraryDependencies = mutableListOf<LibraryDependency>()

            moduleRootManager.orderEntries.forEach {
                when (it) {
                    is ModuleOrderEntry -> {
                        moduleDependencies.add(ModuleDependency(it.moduleName.moduleSimpleName))
                    }
                    is LibraryOrderEntry -> {
                        it.getRootFiles(OrderRootType.CLASSES).forEach getRootFiles@{ file ->
                            val ioFile = VfsUtil.virtualToIoFile(file)
                            val key = "${ioFile.absolutePath}:${ioFile.lastModified()}"
                            if (ioFile.name == "kaptGeneratedClasses" && (!ioFile.exists() || ioFile.isDirectory)) {
                                // ignore kaptGeneratedClasses
                                return@getRootFiles
                            }
                            var libraryDependency = dependencyCacheMap[key]
                            if (libraryDependency == null) {
                                var name = (it.libraryName ?: ioFile.name)
                                if (name.startsWith("Gradle: ")) {
                                    name = name.substring("Gradle: ".length)
                                }
                                if (name.endsWith("@aar")) {
                                    name = name.substring(0, name.length - "@aar".length)
                                }
                                libraryDependency = LibraryDependency(name, ioFile)
                                dependencyCacheMap[key] = libraryDependency
                            } else {
                                hitCacheCount++
                            }
                            libraryDependencies.add(libraryDependency)
                            totalCount++
                        }
                    }
                    is ModuleJdkOrderEntry -> {
                        if (it.jdkTypeName == "Android SDK") {
                            val additionalData = it.jdk?.sdkAdditionalData as? AndroidSdkAdditionalData
                            val buildTarget = additionalData?.buildTargetHashString
                            if (buildTarget != null && buildTarget.startsWith("android-")) {
                                val compileVersion = buildTarget
                                    .substringAfter("android-")
                                    .substringBefore("-ext")
                                ideModuleInfo = ideModuleInfo!!.copy(compileVersion = compileVersion)
                            }
                        }
                    }
                }
            }

            // Smart cast to 'IdeModuleInfo' is impossible, because 'ideModuleInfo' is a local variable that is captured by a changing closure
            val info = ideModuleInfo!!
            val moduleInfo = ModuleInfo(
                module.name.moduleSimpleName, ModuleInfo.Type.Unknown, baseDir, pathManager.projectDir,
                sourceDirs.toList(), resourceDirs.toList(), assetDirs.toList(),
                manifestFile, null,
                info.buildVariant, info.compileVersion, info.minSdkVersion, info.buildToolsVersion,
                info.kotlinJvmTarget, info.kotlinFreeCompilerArgs ?: emptyList(),
                info.javaSourceCompatibility, info.javaTargetCompatibility,
                moduleBuildPathInfo,
                moduleDependencies,
                libraryDependencies,
                emptyList(), emptyList(), emptyList(), // read it in gradle
            )

            if (sourceDirs.isEmpty() && resourceDirs.isEmpty() && assetDirs.isEmpty() && moduleDependencies.isEmpty()) {
                noSourceModules[module.name] = moduleInfo
                return@forEach
            }

            modules[moduleInfo.name] = moduleInfo
            addedModules.add("add ${moduleInfo.name}(origin: ${module.name}) -> $moduleInfo, brokenFields: ${info.brokenFields}")
        }

        if (noSourceModules.isNotEmpty()) {
            val finalNoSourceModules = mutableSetOf<String>()
            val addNoSourceModules = mutableSetOf<String>()
            noSourceModules.forEach { (originName, moduleInfo) ->
                if (modules[moduleInfo.name] == null) {
                    addNoSourceModules.add(originName)
                    modules[moduleInfo.name] = moduleInfo
                    addedModules.add("add ${moduleInfo.name}(origin: $originName) -> $moduleInfo")
                } else {
                    finalNoSourceModules.add(originName)
                }
            }
            logger.debug("add ignore modules (no source module): ${addNoSourceModules.joinToString(", ")}")
            logger.debug("ignore modules (no source module): ${finalNoSourceModules.joinToString(", ")}")
        }

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
        logger.debug(addedModules.joinToString("\n"))

        logger.debug("getLibraryDependencies total $totalCount, hitCacheCount $hitCacheCount, unHitCacheCount ${totalCount - hitCacheCount}")
        logger.debug("total ${modules.size} modules loaded")
        TimeLogger.end("initModuleRoots", logger)
        return JuggProjectInfo(modules)
    }

    private fun File.guessIsResDir(): Boolean {
        val files = listFiles() ?: return false
        return files.any {
            it.name.startsWith("drawable") ||
                    it.name.startsWith("layout") ||
                    it.name.startsWith("values") ||
                    it.name.startsWith("mipmap")
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
        private val String.moduleSimpleName: String get() {
            var name = this
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