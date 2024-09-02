package com.sickworm.intellij.jugg.project

import com.android.tools.idea.gradle.dsl.api.ProjectBuildModel
import com.android.tools.idea.util.toIoFile
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.module.Module
import com.intellij.openapi.project.rootManager
import com.intellij.openapi.projectRoots.ProjectJdkTable
import com.intellij.openapi.roots.*
import com.intellij.openapi.vfs.VfsUtil
import com.sickworm.intellij.jugg.compiler.*
import com.sickworm.intellij.jugg.deploy.CompileContextInfo
import com.sickworm.intellij.jugg.deploy.DeployFileManager
import com.sickworm.intellij.jugg.deploy.IDeployHistoryManager
import com.sickworm.intellij.jugg.deploy.run.AsDeployerCompat
import com.sickworm.intellij.jugg.gradle.compile.isChild
import com.sickworm.intellij.jugg.logger.JuggLogger
import com.sickworm.intellij.jugg.logger.TimeLogger
import com.sickworm.intellij.jugg.project.data.*
import org.jetbrains.android.facet.AndroidFacet
import org.jetbrains.android.sdk.AndroidSdkAdditionalData
import org.jetbrains.annotations.TestOnly
import org.jetbrains.jps.model.java.JavaResourceRootType
import org.jetbrains.jps.model.java.JavaSourceRootType
import java.io.File

/**
 * Manage context for JuggCompiler.
 * Do:
 * 1. read project structure
 * 2. read android sdk information
 * 3. parse apk
 * 4. generate class path
 * ...
 */
class CompileContextManager(
    private val project: Project,
    private val pathManager: JuggPathManager,
    private val deployFileManager: DeployFileManager,
    private val deployHisManager: IDeployHistoryManager,
    private val moduleManager: ModuleManager = AsDeployerCompat.getModuleManager(project), // mock
    private val projectBuildModel: ProjectBuildModel = ProjectBuildModel.get(project), // mock,
    private val logger: Logger = JuggLogger.getInstance(project, "CompileContextManager"),
) {

    private val projectInfoSerializer = ProjectInfoSerializer(pathManager.ideProjectInfoFile, logger)
    private val gradleProjectInfoSerializer = ProjectInfoSerializer(pathManager.gradleProjectInfoFile, logger)
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
    fun updateCompileContextAfterSync(): Boolean {
        logger.debug("updateCompileContextAfterSync")
        ensureInitProjectInfo()
        updateProjectInfoFromIde(isNeedReloadProjectInfo = true)
        juggProjectInfoMerger.afterSync(projectInfoSerializer)
        compileContextInside.update(modules = getProjectInfo().modules)
        compileContextInfo?.let {
            updateCompileContextByFullBuildInfo(it)
        }
        return true
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
        gradleProjectInfoSerializer.clearMemoryCache() // file is updated, clear memory cache
        juggProjectInfoMerger.afterLocalFetch(gradleProjectInfoSerializer)
        compileContextInside.update(modules = getProjectInfo().modules)
        compileContextInfo?.let {
            updateCompileContextByFullBuildInfo(it)
        }
    }

    @TestOnly
    fun getProjectInfo(): JuggProjectInfo {
        juggProjectInfoMerger.juggProjectInfo?.let {
            return it
        }
        return initProjectInfo()
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
        juggProjectInfoMerger.afterLocalFetch(gradleProjectInfoSerializer)
        return juggProjectInfoMerger.juggProjectInfo ?: run {
            logger.warn("JuggProjectInfoMerger returns null, which should not happened.")
            return@run ideJuggProjectInfo
        }
    }

    private fun updateCompileContextByFullBuildInfo(compileContextInfo: CompileContextInfo) {
        val guessBuildPathBaseDir: File? = compileContext.modules.firstNotNullOfOrNull { (name, module) ->
            val newBuildPathInfo = compileContextInfo.moduleBuildPathInfos[name] ?: return@firstNotNullOfOrNull null
            val relativePath = module.buildPathInfo.buildDir.relativeTo(module.buildPathInfo.projectRootDir)
            if (newBuildPathInfo.buildDir.endsWith(relativePath)) {
                return@firstNotNullOfOrNull File(newBuildPathInfo.buildDir.absolutePath.substringBefore(relativePath.absolutePath))
            } else {
                return@firstNotNullOfOrNull null
            }
        }

        val copyModules: Map<String, ModuleInfo> = compileContext.modules.map { (name, module) ->
            val newBuildPathInfo = compileContextInfo.moduleBuildPathInfos[name]
            if (newBuildPathInfo != null) {
                return@map name to module.copy(buildPathInfo = newBuildPathInfo)
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
            logger = JuggLogger.getInstance(project, "Compiler"),
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

        val gradleVariableHelper = GradleVariableHelper(logger)
        gradleVariableHelper.init(project)
        val juggProjectInfo = doGetAllModulesByModuleManager(gradleVariableHelper)
        gradleVariableHelper.release()
        projectInfoSerializer.save(juggProjectInfo)
        return juggProjectInfo
    }

    private fun doGetAllModulesByModuleManager(gradleVariableHelper: GradleVariableHelper): JuggProjectInfo {
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
        val noSourceModules = mutableSetOf<String>()
        moduleManager.modules.forEach { module ->

            // 1. guess base directory
            val baseDir = module.guessModuleDirAdv(projectBuildModel)
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

            // 2. read attributes
            val buildModel = projectBuildModel.getModuleBuildModel(module)
            if (buildModel == null) {
                notGradleModules.add(module.name)
                return@forEach
            }

            val buildToolsVersion: String? = gradleVariableHelper.readVariable(
                buildModel.android().buildToolsVersion(), buildModel) {
                this.all { it.isDigit() || it == '.' }
            }
            var compileVersion: String? = gradleVariableHelper.readVariable(
                buildModel.android().compileSdkVersion(), buildModel) {
                this.all { it.isDigit() }
            }
            val minSdkVersion: String? = gradleVariableHelper.readVariable(
                buildModel.android().defaultConfig().minSdkVersion(), buildModel) {
                this.all { it.isDigit() }
            }
            val kotlinJvmTarget: String? = buildModel.android().kotlinOptions().jvmTarget()
                .toLanguageLevel()?.toJavaVersion()?.toString()
            val kotlinFreeCompilerArgs: List<String> = buildModel.android().kotlinOptions().freeCompilerArgs()
                .toList()?.map { it.toString() } ?: emptyList()
            val javaSourceCompatibility: String? = buildModel.android().compileOptions().sourceCompatibility()
                .toLanguageLevel()?.toJavaVersion()?.toString()
            val javaTargetCompatibility: String? = buildModel.android().compileOptions().targetCompatibility()
                .toLanguageLevel()?.toJavaVersion()?.toString()

            val androidFacet = AndroidFacet.getInstance(module)
            var buildVariant = androidFacet?.properties?.SELECTED_BUILD_VARIANT
            if (buildVariant.isNullOrEmpty()) {
                buildVariant = ModuleInfo.DEFAULT_BUILD_VARIANT
            }

            val minifyEnabled = buildModel.android().buildTypes()
                .find { it.name() == buildVariant }?.minifyEnabled()
            @Suppress("SENSELESS_COMPARISON")
            if (minifyEnabled.toString() != null && minifyEnabled.toString() != "null") {
                // just log it
                logger.debug("module ${module.name} find minifyEnabled: $buildVariant -> $minifyEnabled")
            }

            var manifestFile: File? = null
            val manifestProperties = androidFacet?.properties?.MANIFEST_FILE_RELATIVE_PATH
            if (manifestProperties != null) {
                manifestFile = File(baseDir, manifestProperties)
            }
            val moduleBuildPathInfo = ModuleBuildPathInfo(pathManager.projectDir, baseDir, buildVariant)

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
                    it.toIoFile()
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
                it.toIoFile()
            }.filter {
                return@filter !it.isChild(moduleBuildPathInfo.buildDir) // exclude generated source
            }.forEach { file ->
                if (file.name == "res") {
                    resourceDirs.add(file)
                } else if (file.name == "assets") {
                    assetDirs.add(file)
                } else {
                    val isResDir = file.guessIsResDir()
                    logger.warn("${module.name} unknown resource dir: ${file}, guess isResDir: $isResDir")
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
                            val ioFile = file.toIoFile()
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
                                compileVersion = buildTarget
                                    .substringAfter("android-")
                                    .substringBefore("-ext")
                            }
                        }
                    }
                }
            }

            if (sourceDirs.isEmpty() && resourceDirs.isEmpty() && assetDirs.isEmpty() && moduleDependencies.isEmpty()) {
                noSourceModules.add(module.name)
                return@forEach
            }

            val moduleInfo = ModuleInfo(
                module.name.moduleSimpleName, ModuleInfo.Type.Unknown, baseDir, pathManager.projectDir,
                sourceDirs.toList(), resourceDirs.toList(), assetDirs.toList(),
                manifestFile, null,
                buildVariant, compileVersion, minSdkVersion, buildToolsVersion,
                kotlinJvmTarget, kotlinFreeCompilerArgs,
                javaSourceCompatibility, javaTargetCompatibility,
                moduleBuildPathInfo,
                moduleDependencies,
                libraryDependencies,
                emptyList(), emptyList(), emptyList(), // read it in gradle
            )

            modules[moduleInfo.name] = moduleInfo
            addedModules.add("add ${module.name} -> $moduleInfo")

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
        if (noSourceModules.isNotEmpty()) {
            logger.debug("ignore modules (no source module): ${noSourceModules.joinToString(", ")}")
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
        return files.any { it.name == "drawable" || it.name == "layout" || it.name == "values" }
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
                val subDirs = homeDirectory.toIoFile().listFiles() ?: return@filter false
                val platformsDir = subDirs.firstOrNull { it.name == "platforms" } ?: return@filter false
                if (platformsDir.listFiles().isNullOrEmpty()) return@filter false
                val buildToolsDir = subDirs.firstOrNull { it.name == "build-tools" } ?: return@filter false
                if (buildToolsDir.listFiles().isNullOrEmpty()) return@filter false
                return@filter true
            }
            logger.debug("All available android jdks: $androidJdks")

            return androidJdks.firstOrNull()?.homeDirectory?.toIoFile()
        }

        // e.g. name = example.lib_common.main
        // simpleName = lib_common
        // e.g. name = example.lib_common.lib2.main
        // simpleName = lib_common.lib2
        private val String.moduleSimpleName: String get() {
            val name = this
            val splits = name.split('.')
            return when (splits.size) {
                0 -> name
                1 -> name
                else -> splits.subList(1, splits.size - 1).joinToString(".")
            }
        }
    }
}

private fun Module.guessModuleDirAdv(projectBuildModel: ProjectBuildModel): File? {
    val gradleRootDir = projectBuildModel.getModuleBuildModel(this)?.moduleRootDirectory
    if (gradleRootDir != null) {
        return gradleRootDir
    }

    val contentRoots = rootManager.contentRoots.filter { it.isDirectory }
    val virtualFile = contentRoots.find { name.endsWith(it.name) }
        ?: contentRoots.firstOrNull()
        ?: moduleFile?.parent
        ?: return null
    return VfsUtil.virtualToIoFile(virtualFile)
}