package com.sickworm.intellij.jugg.project

import com.android.tools.idea.gradle.dsl.api.ProjectBuildModel
import com.android.tools.idea.util.toIoFile
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.projectRoots.ProjectJdkTable
import com.intellij.openapi.roots.*
import com.sickworm.intellij.jugg.compiler.*
import com.sickworm.intellij.jugg.deploy.CompileContextInfo
import com.sickworm.intellij.jugg.deploy.DeployFileManager
import com.sickworm.intellij.jugg.deploy.run.AsDeployerCompat
import com.sickworm.intellij.jugg.ide.JuggSettings
import com.sickworm.intellij.jugg.logger.JuggLogger
import org.jetbrains.android.facet.AndroidFacet
import org.jetbrains.jps.model.java.JavaResourceRootType
import org.jetbrains.jps.model.java.JavaSourceRootType
import java.io.File
import kotlin.system.measureTimeMillis

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
    private val moduleManager: ModuleManager = AsDeployerCompat.getModuleManager(project), // mock
    private val projectBuildModel: ProjectBuildModel = ProjectBuildModel.get(project), // mock,
    private val logger: Logger = JuggLogger.getInstance(project, "CompileContextManager"),
) {

    val stagingDir = File(pathManager.compileRootDir, "staging")
    private val projectInfoJsonFile = File(pathManager.historyDir, "project_infos.db/project_infos.dat")
    private val projectInfoSerializer = ProjectInfoSerializer(projectInfoJsonFile, logger)

    /** Init after [initCompileContext] */
    val compileContext: BaseCompileContext
        get() {
            return compileContextInside?: throw JuggInternalException.compilerContextNotInit()
        }

    /** Init after [initCompileContext] */
    private var compileContextInside: BaseCompileContext? = null

    private var compileContextInfo: CompileContextInfo? = null

    private var isFirstTimeLoad = true

    fun refreshCompileContext(): Boolean {
        val compileContextInfo = compileContextInfo
        if (compileContextInfo == null) {
            logger.info("compileContextInfo is null, which means not full build yet. Skip refreshCompileContext")
            return false
        }
        initFullBuildInfo(compileContextInfo, true)
        return true
    }

    fun initFullBuildInfo(compileContextInfo: CompileContextInfo, isNeedReloadProjectInfo: Boolean) {
        initCompileContext(isNeedReloadProjectInfo)

        this.compileContextInfo = compileContextInfo

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
        compileContext.update(apkInfos = compileContextInfo.apkInfos, modules = copyModules)
    }

    private fun initCompileContext(isNeedReloadProjectInfo: Boolean) {
        logger.debug("initCompileContext start")
        val costTime = measureTimeMillis {
            val modules = getAllModulesByModuleManager(isNeedReloadProjectInfo)
            initCompileContext(modules)
        }
        logger.debug("initCompileContext finish, cost ${costTime}ms")
    }

    private fun initCompileContext(modules: Map<String, ModuleInfo>) {
        logger.debug("Start initContext")

        // TODO read project settings ( ModuleRootManager.getInstance(module).sdk.rootProvider.getFiles(OrderRootType.CLASSES) )
        // TODO AndroidSdkEventListener on sdk path changed
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
            modules = modules,
            minApi = JuggSettings.minApi,
            projectDir = pathManager.projectDir,
            deployFileManager = deployFileManager,
        )
        logger.debug("""
            context loaded:
            build-tools:${context.androidBuildTools}
            android.jar:${context.androidJar}
        """.trimIndent())

        compileContextInside = context
    }

    fun getAllModulesByModuleManager(isNeedReloadProjectInfo: Boolean): Map<String, ModuleInfo> {
        var modules: Map<String, ModuleInfo>? = null
        if (!isNeedReloadProjectInfo) {
            val cacheModules = projectInfoSerializer.load()
            logger.debug("Try to load project info from cache, is success: ${cacheModules != null}")
            if (cacheModules != null) {
                modules = cacheModules
            }
        }
        if (modules == null) {
            val gradleVariableHelper = GradleVariableHelper()
            gradleVariableHelper.init(project)
            if (isFirstTimeLoad) {
                isFirstTimeLoad = false
            } else {
                // Reparse gradle. Otherwise GradleBuildModel won't update after sync
                val costTime = measureTimeMillis {
                    projectBuildModel.reparse()
                }
                logger.debug("Reparse gradle cost ${costTime}ms")
            }
            modules = doGetAllModulesByModuleManager(gradleVariableHelper)
            gradleVariableHelper.release()
            projectInfoSerializer.save(modules)
        }
        return modules
    }

    private fun doGetAllModulesByModuleManager(gradleVariableHelper: GradleVariableHelper): Map<String, ModuleInfo> {
        logger.debug("Start init module roots")

        val modules = mutableMapOf<String, ModuleInfo>()
        val addedModules = mutableSetOf<String>()
        val directoryNotFoundModules = mutableSetOf<String>()
        val ideaFolderModules = mutableSetOf<String>()
        val notGradleModules = mutableSetOf<String>()
        val testModules = mutableSetOf<String>()
        val noSourceModules = mutableSetOf<String>()
        val fullLibraryDependencies = mutableSetOf<String>()
        moduleManager.modules.forEach { module ->
            val sourceDirs = mutableSetOf<File>()
            val resourceDirs = mutableSetOf<File>()
            val assetDirs = mutableSetOf<File>()

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

            val moduleRootManager = ModuleRootManager.getInstance(module)

            // find source roots
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
                    val relativeFile = it.relativeToOrNull(baseDir) ?: return@filter true
                    return@filter !relativeFile.path.startsWith("build/") // exclude generated source
                }
            sourceDirs.addAll(subSourceRoots)

            val subResourceRoots = moduleRootManager.getSourceRoots(
                setOf(
                    JavaResourceRootType.RESOURCE,
                    org.jetbrains.kotlin.config.ResourceKotlinRootType
                ))
            subResourceRoots.forEach {
                val file = it.toIoFile()
                if (it.name == "res") {
                    resourceDirs.add(file)
                } else if (it.name == "assets") {
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

            // find dependencies
            val moduleDependencies = mutableListOf<ModuleDependency>()
            val libraryDependencies = mutableListOf<LibraryDependency>()
            moduleRootManager.orderEntries.forEach {
                when (it) {
                    is ModuleOrderEntry -> {
                        moduleDependencies.add(ModuleDependency(it.moduleName))
                    }
                    is LibraryOrderEntry -> {
                        it.getRootFiles(OrderRootType.CLASSES).forEach { file ->
                            libraryDependencies.add(LibraryDependency(file.toIoFile()))
                            fullLibraryDependencies.add(file.toIoFile().absolutePath)
                        }
                    }
                }
            }

            if (sourceDirs.isEmpty() && resourceDirs.isEmpty() && assetDirs.isEmpty() && moduleDependencies.isEmpty() && libraryDependencies.isEmpty()) {
                noSourceModules.add(module.name)
                return@forEach
            }

            val buildModel = projectBuildModel.getModuleBuildModel(module)
            if (buildModel == null) {
                notGradleModules.add(module.name)
                return@forEach
            }



            val buildToolsVersion: String? = gradleVariableHelper.readVariable(
                buildModel.android().buildToolsVersion(), buildModel) {
                this.all { it.isDigit() || it == '.' }
            }
            val compileVersion: String? = gradleVariableHelper.readVariable(
                buildModel.android().compileSdkVersion(), buildModel) {
                this.all { it.isDigit() }
            }
            val kotlinJvmTarget: String? = buildModel.android().kotlinOptions().jvmTarget()
                .toLanguageLevel()?.toJavaVersion()?.toString()
            val javaSourceCompatibility: String? = buildModel.android().compileOptions().sourceCompatibility()
                .toLanguageLevel()?.toJavaVersion()?.toString()
            val javaTargetCompatibility: String? = buildModel.android().compileOptions().targetCompatibility()
                .toLanguageLevel()?.toJavaVersion()?.toString()

            val androidFacet = AndroidFacet.getInstance(module)
            var buildVariant = androidFacet?.properties?.SELECTED_BUILD_VARIANT
            if (buildVariant.isNullOrEmpty()) {
                buildVariant = ModuleInfo.DEFAULT_BUILD_VARIANT
            }

            var manifestFile: File? = null
            val manifestProperties = androidFacet?.properties?.MANIFEST_FILE_RELATIVE_PATH
            if (manifestProperties != null) {
                manifestFile = File(baseDir, manifestProperties)
            }

            val moduleInfo = ModuleInfo(
                module.name, baseDir, pathManager.projectDir,
                sourceDirs.toList(), resourceDirs.toList(), assetDirs.toList(),
                manifestFile,
                buildVariant, compileVersion, buildToolsVersion,
                kotlinJvmTarget, javaSourceCompatibility, javaTargetCompatibility,
                ModuleBuildPathInfo(pathManager.projectDir, baseDir),
                moduleDependencies,
                libraryDependencies,
            )

            modules[module.name] = moduleInfo
            addedModules.add("add $moduleInfo")

        }

        gradleVariableHelper.release()
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

        logger.debug("total ${modules.size} modules loaded")
        return modules
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
    }
}
