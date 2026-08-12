package com.sickworm.intellij.jugg.compiler.context

import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.jugg.JuggException
import com.sickworm.intellij.jugg.apk.ApkInfo
import com.sickworm.intellij.jugg.compiler.BuildTarget
import com.sickworm.intellij.jugg.compiler.ICompileContext
import com.sickworm.intellij.jugg.compiler.changeBaseDir
import com.sickworm.intellij.jugg.compiler.custom.CustomCompilerManager
import com.sickworm.intellij.jugg.deploy.CompileContextInfo
import com.sickworm.intellij.jugg.deploy.DeployFileManager
import com.sickworm.intellij.jugg.deploy.IDeployHistoryManager
import com.sickworm.intellij.jugg.logger.TimeLogger
import com.sickworm.intellij.jugg.logger.getInstance
import com.sickworm.intellij.jugg.project.info.JuggProjectInfo
import com.sickworm.intellij.jugg.project.info.LibraryDependency
import com.sickworm.intellij.jugg.project.info.ModuleBuildPathInfo
import com.sickworm.intellij.jugg.project.info.ModuleInfo
import com.sickworm.intellij.jugg.project.info.IProjectModelSource
import com.sickworm.intellij.jugg.project.info.ProjectModelLoadReason
import com.sickworm.intellij.jugg.project.info.ProjectModelResult
import com.sickworm.intellij.jugg.project.runtime.JuggPathManager
import com.sickworm.intellij.jugg.server.protocols.ModuleCustomConfig
import java.io.File

/**
 * Manages the shared compile context independently from IDEA project model APIs.
 */
class CompileContextManager(
    private val pathManager: JuggPathManager,
    private val projectModelSource: IProjectModelSource,
    private val deployFileManager: DeployFileManager,
    private val deployHistoryManager: IDeployHistoryManager,
    private val customCompilerManager: CustomCompilerManager,
    private val compileEnvironmentSource: ICompileEnvironmentSource,
    private val scene: ICompileContext.Scene,
    private val logger: Logger,
) {

    private val compileContextInside: BaseCompileContext by lazy { createCompileContext() }
    private var baseProjectInfo: JuggProjectInfo? = null
    private var projectInfo: JuggProjectInfo? = null
    private var moduleCustomConfigs: List<ModuleCustomConfig> = emptyList()
    private var includedBuildModuleRoots = emptySet<File>()

    val compileContext: ICompileContext
        get() = compileContextInside

    private var compileContextInfo: CompileContextInfo? = null

    /** Updates the context with authoritative full-build paths and APK metadata. */
    fun setCompileContext(
        compileContextInfo: CompileContextInfo,
        reloadProjectModel: Boolean = false,
    ) {
        logger.debug("setCompileContext")
        ensureInitProjectInfo()
        if (reloadProjectModel) {
            loadProjectInfo(ProjectModelLoadReason.GRADLE_FETCH)
        }
        this.compileContextInfo = compileContextInfo
        val projectInfo = getProjectInfo()
        compileContextInside.update(
            apkInfos = compileContextInfo.apkInfos,
            modules = buildEffectiveModules(projectInfo.modules, compileContextInfo),
            agpR8Classpath = projectInfo.agpR8Classpath,
            includedBuildModuleRoots = includedBuildModuleRoots,
        )
    }

    /** Refreshes the host project model and reports whether runtime consumers need rebinding. */
    fun updateCompileContext(
        isAfterSync: Boolean,
        preferGradleLibraryDependencies: Boolean = false,
        updateGradleAsync: () -> Unit,
    ): Boolean {
        logger.debug("updateCompileContext isAfterSync: $isAfterSync, " +
                "preferGradleLibraryDependencies: $preferGradleLibraryDependencies")
        ensureInitProjectInfo()
        val reason = when {
            preferGradleLibraryDependencies -> ProjectModelLoadReason.HOST_FULL_BUILD_FALLBACK
            isAfterSync -> ProjectModelLoadReason.HOST_SYNC
            else -> ProjectModelLoadReason.VALIDATE
        }
        val result = loadProjectInfo(reason)
        if (result.isModelReloaded) {
            updateCompileContextModules(result.projectInfo!!)
        }
        if (result.needsGradleRefresh) {
            updateGradleAsync()
        }
        return result.isModelReloaded
    }

    /** Tries to repair dependency gaps by merging the latest source snapshots. */
    fun triggerMerge(): Boolean {
        val result = loadProjectInfo(ProjectModelLoadReason.MERGE)
        return result.isFixMissingOrDelete
    }

    fun updateTempLibraries(
        addedTempLibraries: List<LibraryDependency>?,
        removedTempLibraries: List<LibraryDependency>?,
    ) {
        logger.debug("updateTempLibraries addedTempLibraries: $addedTempLibraries, removedTempLibraries: $removedTempLibraries")
        compileContextInside.update(
            addedTempLibraries = addedTempLibraries,
            removedTempLibraries = removedTempLibraries,
        )
    }

    fun updateApkInfos(apkInfos: List<ApkInfo>) {
        compileContextInside.update(apkInfos = apkInfos)
    }

    /** Refreshes the effective model after Gradle writes new project info snapshots. */
    fun updateCompileContextAfterLocalFetch(buildTarget: BuildTarget = currentBuildTarget()) {
        logger.debug("updateCompileContextAfterLocalFetch")
        ensureInitProjectInfo()
        val result = loadProjectInfo(ProjectModelLoadReason.GRADLE_FETCH, buildTarget)
        updateCompileContextModules(result.projectInfo!!)
    }

    fun getProjectInfo(): JuggProjectInfo {
        return projectInfo ?: loadProjectInfo(ProjectModelLoadReason.INITIALIZE).projectInfo!!
    }

    fun updateCustomClasspath(moduleCustomConfigs: List<ModuleCustomConfig>) {
        if (this.moduleCustomConfigs == moduleCustomConfigs) {
            return
        }
        logger.debug("updateCustomClasspath: $moduleCustomConfigs")
        this.moduleCustomConfigs = moduleCustomConfigs
        val sourceProjectInfo = baseProjectInfo ?: projectModelSource.load(
            ProjectModelLoadReason.INITIALIZE,
            currentBuildTarget(),
        ).also {
            includedBuildModuleRoots = it.includedBuildModuleRoots
        }.projectInfo ?: error("Project model is unavailable")
        baseProjectInfo = sourceProjectInfo
        val effectiveProjectInfo = applyCustomClasspath(sourceProjectInfo)
        projectInfo = effectiveProjectInfo
        updateCompileContextModules(effectiveProjectInfo)
    }

    fun ensureInitProjectInfo() {
        if (projectInfo != null) {
            return
        }
        logger.info("Initializing project info...")
        val startTime = System.currentTimeMillis()
        loadProjectInfo(ProjectModelLoadReason.INITIALIZE)
        val costTime = (System.currentTimeMillis() - startTime) / 1000
        logger.info("Initializing project info done, cost ${costTime}s.")
    }

    private fun loadProjectInfo(reason: ProjectModelLoadReason, buildTarget: BuildTarget = currentBuildTarget()): ProjectModelResult {
        val result = projectModelSource.load(reason, buildTarget)
        val sourceProjectInfo = result.projectInfo ?: baseProjectInfo ?: error("Project model is unavailable")
        baseProjectInfo = sourceProjectInfo
        includedBuildModuleRoots = result.includedBuildModuleRoots
        val effectiveProjectInfo = applyCustomClasspath(sourceProjectInfo)
        projectInfo = effectiveProjectInfo
        return result.copy(projectInfo = effectiveProjectInfo)
    }

    private fun applyCustomClasspath(projectInfo: JuggProjectInfo): JuggProjectInfo {
        if (moduleCustomConfigs.isEmpty()) {
            return projectInfo
        }
        val configs = moduleCustomConfigs.associateBy { it.moduleStdPath }
        return projectInfo.copy(modules = projectInfo.modules.mapValues { (_, module) ->
            val config = configs[module.moduleStdPath] ?: return@mapValues module
            module.copy(buildPathInfo = module.buildPathInfo.copy(customClasspath = config.customClasspath, customSyncFilePath = config.customSyncFilePath))
        })
    }

    private fun updateCompileContextModules(projectInfo: JuggProjectInfo) {
        compileContextInside.update(
            apkInfos = compileContextInfo?.apkInfos,
            modules = buildEffectiveModules(projectInfo.modules),
            agpR8Classpath = projectInfo.agpR8Classpath,
            includedBuildModuleRoots = includedBuildModuleRoots,
        )
    }

    private fun currentBuildTarget(): BuildTarget {
        return deployHistoryManager.getFullBuildInfo()?.buildTarget ?: BuildTarget.APP
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
                File(newBuildPathInfo.buildDir.absolutePath.substringBefore(relativePath.absolutePath))
            } else {
                null
            }
        }

        return baseModules.map { (name, module) ->
            val newBuildPathInfo = compileContextInfo.moduleBuildPathInfos[name]
            if (newBuildPathInfo != null) {
                return@map name to module.copy(
                    buildPathInfo = newBuildPathInfo.copy(
                        customClasspath = module.buildPathInfo.customClasspath,
                        customSyncFilePath = module.buildPathInfo.customSyncFilePath,
                    )
                )
            }

            logger.info(
                "build path of module($name) is missing, maybe module is synced after full build. " +
                    "Try to guess build path by guessBuildPathBaseDir=$guessBuildPathBaseDir"
            )
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
                name to module.copy(buildPathInfo = guessedBuildPathInfo)
            } else {
                logger.debug(
                    "guess build path can't find build path for module $name, " +
                        "tried: ${guessedBuildPathInfo.buildDir}, use old build path: ${module.buildPathInfo}"
                )
                name to module
            }
        }.toMap()
    }

    private fun createCompileContext(): BaseCompileContext {
        TimeLogger.start("createCompileContext")
        val finalAndroidHome = compileEnvironmentSource.getAndroidHome(logger) ?: throw JuggException.androidHomeNotFound()
        logger.debug("Use android sdk home: $finalAndroidHome")
        val projectInfo = getProjectInfo()
        val context = BaseCompileContext(
            logger = logger.getInstance("BaseCompileContext"),
            androidHome = finalAndroidHome,
            tempCompileDir = File(pathManager.compileRootDir, "compiled"),
            tempModuleDir = File(pathManager.compileRootDir, "temp_module"),
            modules = buildEffectiveModules(projectInfo.modules),
            projectDir = pathManager.projectDir,
            agpR8Classpath = projectInfo.agpR8Classpath,
            deployFileManager = deployFileManager,
            deployHistoryManager = deployHistoryManager,
            customCompilerManager = customCompilerManager,
            incrementalDataDir = File(pathManager.compileRootDir, "incremental"),
            cmdCompileEnv = compileEnvironmentSource.buildCompileEnv(logger),
            scene = scene,
            includedBuildModuleRoots = includedBuildModuleRoots,
        )
        TimeLogger.end("createCompileContext", logger)
        return context
    }

    private fun <T, R : Any> Iterable<T>.firstNotNullOfOrNull(transform: (T) -> R?): R? {
        for (element in this) {
            val result = transform(element)
            if (result != null) {
                return result
            }
        }
        return null
    }
}
