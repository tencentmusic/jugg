package com.sickworm.intellij.jugg.cmdline.standalone

import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.jugg.compiler.ICompileContext
import com.sickworm.intellij.jugg.compiler.JuggCompiler
import com.sickworm.intellij.jugg.compiler.JuggCompilerHelper
import com.sickworm.intellij.jugg.compiler.ui.RunResult
import com.sickworm.intellij.jugg.compiler.context.CompileContextManager
import com.sickworm.intellij.jugg.compiler.custom.CustomCompilerManager
import com.sickworm.intellij.jugg.deploy.CompileContextInfo
import com.sickworm.intellij.jugg.deploy.DeployFileManager
import com.sickworm.intellij.jugg.deploy.DeployHistoryManager
import com.sickworm.intellij.jugg.deploy.DeployStateManager
import com.sickworm.intellij.jugg.deploy.FullBuildInfo
import com.sickworm.intellij.jugg.deploy.IDeployHistoryManager
import com.sickworm.intellij.jugg.deploy.IDeployStateManager
import com.sickworm.intellij.jugg.deploy.IJuggRunningTaskStatusManager
import com.sickworm.intellij.jugg.deploy.JuggRunningTaskStatusManager
import com.sickworm.intellij.jugg.deploy.getTargetDeviceSerialList
import com.sickworm.intellij.jugg.deploy.cache.JuggDeploymentCacheStore
import com.sickworm.intellij.jugg.deploy.run.IAsDeployerCompat
import com.sickworm.intellij.jugg.deploy.run.JuggDeployerHelper
import com.sickworm.intellij.jugg.deploy.run.JuggDeploymentService
import com.sickworm.intellij.jugg.deploy.run.StandaloneApplyChangesExecutor
import com.sickworm.intellij.jugg.deploy.run.StandaloneDeviceManager
import com.sickworm.intellij.jugg.ide.bean.JuggGradleCompileOptions
import com.sickworm.intellij.jugg.ide.bean.JuggSettings
import com.sickworm.intellij.jugg.logger.JuggLogger
import com.sickworm.intellij.jugg.project.change.FileChangeManager
import com.sickworm.intellij.jugg.project.change.FileChangeSource
import com.sickworm.intellij.jugg.project.change.FileChangesHandler
import com.sickworm.intellij.jugg.project.change.GitFileChangesDetector
import com.sickworm.intellij.jugg.project.change.IFileChangesHandler
import com.sickworm.intellij.jugg.project.change.WatchServiceFileChangeMonitor
import com.sickworm.intellij.jugg.project.dependency.GradleProjectInfoLocalFetchManager
import com.sickworm.intellij.jugg.project.dependency.IDependencyChangeManager
import com.sickworm.intellij.jugg.project.dependency.create
import com.sickworm.intellij.jugg.project.info.GradleProjectModelSource
import com.sickworm.intellij.jugg.project.runtime.CliRunConfigurationGenerator
import com.sickworm.intellij.jugg.project.runtime.CliRunConfigurationStore
import com.sickworm.intellij.jugg.project.runtime.IHostTaskExecutor
import com.sickworm.intellij.jugg.project.runtime.JuggPathManager
import com.sickworm.intellij.jugg.project.runtime.ProjectCustomConfigManager
import com.sickworm.intellij.jugg.project.runtime.RuntimeInfo
import com.sickworm.intellij.jugg.project.runtime.RuntimeOwnerChangeEvent
import com.sickworm.intellij.jugg.project.runtime.TaskRunnerManager
import com.sickworm.intellij.jugg.server.JuggServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/** Owns the standalone project domain shared by MCP compile, deploy, and status tools. */
internal class StandaloneProjectServices(
    val projectDir: File,
    runtimeInfo: RuntimeInfo,
    private val activity: StandaloneDaemonActivity,
    private val resources: StandaloneProjectResources,
) : AutoCloseable {
    val pathManager = JuggPathManager(projectDir)
    private val logKey = projectDir.absolutePath

    init {
        JuggLogger.register(logKey, pathManager.standaloneCliLogDir)
        resources.register { JuggLogger.unregister(logKey) }
    }

    val logger: Logger = JuggLogger.getInstance(logKey, "StandaloneProjectRuntime")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO).also { scope ->
        resources.register { scope.cancel() }
    }

    init {
        logger.info("Start Init Jugg standalone on ${projectDir.absolutePath}, version=${runtimeInfo.runtimeVersion}, " +
                "host=${runtimeInfo.hostVersion}")
    }

    val compileEnvironmentSource = StandaloneCompileEnvironmentSource(projectDir)
    private var deviceManager: StandaloneDeviceManager? = null
    private val adbFile = resolveAdb()
    private val fileChangesHandler: IFileChangesHandler = FileChangesHandler(projectDir, pathManager.juggRootDir, logger)
    private val deployHistoryManager: IDeployHistoryManager = DeployHistoryManager(pathManager, fileChangesHandler, logger)
    private val deployTargetManagerInside = StandaloneDeployTargetManager(::getDeviceManager, ::deployEnvironment, logger)
    private val juggServer = JuggServer(projectDir.name, pathManager, scope, runtimeInfo, logger)
    val deployStateManager: IDeployStateManager = DeployStateManager(
        deployTargetManagerInside, deployHistoryManager, StandaloneHostDeployStateResolver(::deployEnvironment, logger), logger,
    )
    private val taskRunnerManager = TaskRunnerManager(
        logger, deployStateManager, juggServer, ImmediateHostTaskExecutor, pathManager,
        runtimeInfo.runtimeType, runtimeInfo.runtimeVersion, scope,
    ).also { manager -> resources.register(manager::dispose) }
    val deployFileManager = DeployFileManager(pathManager, taskRunnerManager, logger).also { manager ->
        resources.register(manager::dispose)
    }
    private val customCompilerManager = CustomCompilerManager(
        projectDir,
        pathManager.customCompilerDir,
        juggServer,
        logger,
    ).also { manager -> resources.register(manager::close) }
    private val compileContextManager = CompileContextManager(
        pathManager, GradleProjectModelSource(pathManager, logger), deployFileManager, deployHistoryManager,
        customCompilerManager, compileEnvironmentSource, ICompileContext.Scene.IDE, logger,
    )
    private val runningTaskStatusManager: IJuggRunningTaskStatusManager = JuggRunningTaskStatusManager()
    private val dependencyChangeManager: IDependencyChangeManager = IDependencyChangeManager.create(logger)
    private val gradleProjectInfoManager = GradleProjectInfoLocalFetchManager(
        pathManager, compileContextManager, taskRunnerManager, dependencyChangeManager,
        deployHistoryManager, compileEnvironmentSource, logger,
    ).also { manager -> resources.register(manager::close) }
    private val gitFileChangesDetector = GitFileChangesDetector(
        deployHistoryManager, deployFileManager, taskRunnerManager, logger,
    )
    private val fileChangeManager = FileChangeManager(
        fileChangesHandler, deployFileManager, dependencyChangeManager, gitFileChangesDetector,
        deployStateManager, taskRunnerManager, logger,
    )
    private val fileMonitor = WatchServiceFileChangeMonitor(projectDir, logger).also { monitor ->
        resources.register(monitor::close)
    }
    private val deploymentService by lazy {
        JuggDeploymentService(
            pathManager,
            JuggDeploymentCacheStore(pathManager.deploymentCacheDbFile, taskRunnerManager),
            StandaloneApplyChangesExecutor(),
        )
    }
    private val projectCustomConfigManager = ProjectCustomConfigManager(
        pathManager.configDir, logger, juggServer, fileChangesHandler, deployHistoryManager,
        compileContextManager, customCompilerManager,
    )
    val compilerHelper = JuggCompilerHelper(
        pathManager, juggServer, deployTargetManagerInside, deployStateManager, deployFileManager,
        deployHistoryManager, runningTaskStatusManager, compileContextManager, fileChangesHandler,
        dependencyChangeManager, gradleProjectInfoManager, compileEnvironmentSource,
        gitFileChangesDetector, taskRunnerManager, logger,
    ).also { helper -> resources.register(helper::close) }
    val deployerHelper: JuggDeployerHelper by lazy {
        JuggDeployerHelper(
            deployTargetManagerInside, deployFileManager, deployHistoryManager, deployStateManager,
            dependencyChangeManager, runningTaskStatusManager, compileContextManager, juggServer,
            taskRunnerManager, logger, deploymentService = deploymentService, environment = deployEnvironment(),
        )
    }
    val configurationStore = CliRunConfigurationStore(pathManager)
    val deployTargetManager get() = deployTargetManagerInside
    var ownerChange: RuntimeOwnerChangeEvent? = null
        private set
    private val monitoringStarted = AtomicBoolean()
    private val closed = AtomicBoolean()

    init {
        juggServer.initialize()
        IAsDeployerCompat.updateMinApi(JuggSettings.finalIsEnableCompatibleDeploymentMode)
        activity.beginProjectWrite()
        try {
            taskRunnerManager.runProjectWriteLocked("Initialize standalone runtime") {
                ownerChange = taskRunnerManager.consumeRuntimeOwnerChange()
                projectCustomConfigManager.refresh()
                deployHistoryManager.checkProjectDirChanged()
                deploymentService.preInit(logger)
                recoverDeployContext()
            }
        } finally {
            activity.endProjectWrite()
        }
    }

    fun initializeProject() = runProjectWriteLocked("Initialize standalone project") {
        StandaloneProjectInitializer(pathManager, compileEnvironmentSource, logger).initialize()
    }

    fun <T> runProjectWriteLocked(jobName: String, action: () -> T): T {
        activity.beginProjectWrite()
        return try {
            taskRunnerManager.runProjectWriteLocked(jobName) {
                taskRunnerManager.consumeRuntimeOwnerChange()?.let(::recoverAfterOwnerChange)
                action()
            }
        } finally {
            activity.endProjectWrite()
        }
    }

    fun <T : Any> tryRunProjectWriteLocked(jobName: String, action: () -> T): T? {
        activity.beginProjectWrite()
        return try {
            taskRunnerManager.tryRunProjectWriteLocked(jobName) {
                taskRunnerManager.consumeRuntimeOwnerChange()?.let(::recoverAfterOwnerChange)
                action()
            }
        } finally {
            activity.endProjectWrite()
        }
    }

    fun onBuildStarted() {
        dependencyChangeManager.onStartBuilding()
    }

    fun onBuildFinished(runResult: RunResult?, handler: StandaloneCompileUiHandler) {
        val isCanceled = handler.isCanceled && !handler.processHandler.isCanceledByNextTask
        val isSuccess = if (runResult?.isGradleCompile == true) {
            runResult.isCompileSuccess
        } else {
            runResult?.isDeploySuccess == true
        }
        dependencyChangeManager.onEndBuilding(isSuccess, isCanceled)
        if (runResult?.isGradleCompile == true && !handler.isCanceled) {
            deployHistoryManager.isLastFullCompileFailed = !runResult.isCompileSuccess
        }
        if (isCanceled || runResult?.isNeedResetHasRun == true) {
            runningTaskStatusManager.resetHasRun()
        } else {
            runningTaskStatusManager.setHasRun(deployTargetManagerInside.getTargetDeviceSerialList(handler.targetDeviceSerial))
        }
        runningTaskStatusManager.isProjectSwitchedThisRun = false
    }

    fun refreshChangedFiles() {
        if (deployHistoryManager.hasBeenFullCompiled) gitFileChangesDetector.updateChangedFiles()
    }

    fun refreshCustomConfig() {
        projectCustomConfigManager.refresh()
    }

    fun initAfterFullBuild(startCompileTime: Long, options: JuggGradleCompileOptions) {
        JuggLogger.resetLatestCompileLog(logKey)
        logger.info("Standalone Gradle build completed, reinitializing project runtime on ${projectDir.absolutePath}")
        juggServer.afterFullCompile()
        pathManager.stagingDir.deleteRecursively()
        pathManager.compileRootDir.deleteRecursively()
        compileContextManager.updateCompileContextAfterLocalFetch(options.buildTarget)
        val projectInfo = compileContextManager.getProjectInfo()
        val apkInfos = deployTargetManagerInside.getApks()
        check(apkInfos.isNotEmpty()) { "Gradle build succeeded but no APK was found" }
        configurationStore.loadCurrent()?.let { current ->
            val updated = CliRunConfigurationGenerator.fromCompileOptions(current, options, projectInfo)
            configurationStore.save(updated)
            configurationStore.select(updated.id)
        }
        val contextInfo = deployHistoryManager.reInitAfterFullCompiled(
            FullBuildInfo(options.compileCommand, options.buildTarget, System.currentTimeMillis()),
            apkInfos, projectInfo.modules, startCompileTime,
        )
        initCompile(contextInfo, emptyList(), startCompileTime)
    }

    private fun recoverDeployContext(reloadProjectModel: Boolean = false): Boolean {
        val recoverInfo = deployHistoryManager.tryGetContextRecoverInfoFromDb(isOnInit = true) ?: return false
        initCompile(recoverInfo.compileContextInfo, recoverInfo.deployedFiles, null, reloadProjectModel)
        deployTargetManagerInside.setApks(recoverInfo.compileContextInfo.apkInfos)
        fileChangeManager.processFileChanges(recoverInfo.changedFiles, emptyList(), FileChangeSource.RECOVER)
        return true
    }

    private fun recoverAfterOwnerChange(change: RuntimeOwnerChangeEvent) {
        logger.debug("Recover standalone runtime after owner changed: ${change.previousOwner.runtimeType} -> " +
                change.currentOwner.runtimeType)
        runningTaskStatusManager.isProjectSwitchedThisRun = true
        deploymentService.invalidateMemoryCache()
        if (!recoverDeployContext(reloadProjectModel = true)) {
            compilerHelper.juggCompiler = null
            deployTargetManagerInside.setApks(emptyList())
            deployFileManager.init(emptyList(), emptyList(), null)
            runningTaskStatusManager.resetHasRun()
        }
        refreshChangedFiles()
    }

    private fun initCompile(
        compileContextInfo: CompileContextInfo,
        deployedFiles: List<com.sickworm.intellij.jugg.compiler.CompileOutput>,
        startCompileTime: Long?,
        reloadProjectModel: Boolean = false,
    ) {
        deployStateManager.isBuildFileChanged = false
        val apkInfos = if (projectCustomConfigManager.hasEmbeddedApks()) {
            projectCustomConfigManager.fillApkInfosWithEmbeddedApks(
                compileContextInfo.apkInfos, pathManager.localClasspathStoragePathManager.embeddedApkDir,
            )
        } else {
            compileContextInfo.apkInfos
        }
        compileContextManager.setCompileContext(compileContextInfo, reloadProjectModel)
        deployTargetManagerInside.setApks(apkInfos)
        deployFileManager.init(apkInfos, deployedFiles, startCompileTime)
        dependencyChangeManager.init(pathManager.projectInfosDir, compileContextManager.compileContext)
        rebindCompileContext()
        if (monitoringStarted.compareAndSet(false, true)) {
            fileChangeManager.start(fileMonitor) {}
        }
    }

    private fun rebindCompileContext() {
        val context = compileContextManager.compileContext
        deployFileManager.updateModuleInfos(context.modules, context.mappingFile)
        compilerHelper.juggCompiler = JuggCompiler(context, object : com.intellij.openapi.Disposable {
            override fun dispose() = Unit
        })
        fileChangesHandler.init(context)
        fileChangeManager.init(projectDir, context.modules)
        customCompilerManager.init(context)
    }

    private var deployEnvironmentInside: StandaloneDeployEnvironment? = null

    internal fun deployEnvironment(): StandaloneDeployEnvironment {
        deployEnvironmentInside?.let { return it }
        return StandaloneDeployEnvironment(getDeviceManager(), logger).also {
            deployEnvironmentInside = it
        }
    }

    private fun getDeviceManager(): StandaloneDeviceManager {
        deviceManager?.let { return it }
        check(adbFile.isFile) { "ADB executable not found. Set ANDROID_HOME or ANDROID_SDK_ROOT." }
        return StandaloneDeviceManager(adbFile).also { manager ->
            resources.register(manager::close)
            deviceManager = manager
        }
    }

    private fun resolveAdb(): File {
        val androidHome = compileEnvironmentSource.getAndroidHome(logger) ?: return File("adb")
        return listOf(File(androidHome, "platform-tools/adb"), File(androidHome, "platform-tools/adb.exe"))
            .firstOrNull(File::isFile) ?: File("adb")
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        val errors = mutableListOf<Throwable>()
        runCatching { logger.info("Close Jugg standalone project on ${projectDir.absolutePath}") }
            .exceptionOrNull()
            ?.let(errors::add)
        errors += resources.cleanup()
        if (errors.isNotEmpty()) {
            throw errors.first().also { first -> errors.drop(1).forEach(first::addSuppressed) }
        }
    }

    private object ImmediateHostTaskExecutor : IHostTaskExecutor {
        override val isOnEdt: Boolean = false
        override fun submit(title: String, cancelText: String, showIndicator: Boolean, action: Runnable) = action.run()
    }
}
