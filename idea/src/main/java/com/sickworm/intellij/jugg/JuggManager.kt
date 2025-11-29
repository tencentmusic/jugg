package com.sickworm.intellij.jugg

import com.android.ide.common.util.pathTreeMapOf
import com.intellij.execution.DefaultExecutionResult
import com.intellij.execution.ExecutionResult
import com.intellij.execution.RunManager
import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.filters.TextConsoleBuilderFactory
import com.intellij.execution.process.ProcessOutputType
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.util.Disposer
import com.sickworm.intellij.jugg.compiler.*
import com.sickworm.intellij.jugg.compiler.custom.CustomCompilerManager
import com.sickworm.intellij.jugg.project.data.ModuleBuildPathInfo
import com.sickworm.intellij.jugg.deploy.*
import com.sickworm.intellij.jugg.deploy.run.*
import com.sickworm.intellij.jugg.ide.*
import com.sickworm.intellij.jugg.ide.bean.IProcessHandler
import com.sickworm.intellij.jugg.ide.bean.JuggGradleCompileOptions
import com.sickworm.intellij.jugg.ide.bean.JuggSettings
import com.sickworm.intellij.jugg.ide.logic.*
import com.sickworm.intellij.jugg.logger.JuggLogger
import com.sickworm.intellij.jugg.logger.TimeLogger
import com.sickworm.intellij.jugg.logger.getInstance
import com.sickworm.intellij.jugg.server.JuggServer
import com.sickworm.intellij.jugg.project.*
import com.sickworm.intellij.jugg.project.dependency.IDependencyChangeManager
import com.sickworm.intellij.jugg.project.dependency.create
import com.sickworm.intellij.jugg.project.dependency.GradleProjectInfoLocalFetchManager
import com.sickworm.intellij.jugg.ide.ui.CheckUpdateHandler
import com.sickworm.intellij.jugg.ide.ui.DirectorySelector
import com.sickworm.intellij.jugg.ide.ui.ReportConfirmDialog
import com.sickworm.intellij.jugg.ide.ui.ReportProgressDialog
import com.sickworm.intellij.jugg.ide.ui.SimpleProcessHandler
import com.sickworm.intellij.jugg.rpc.RpcCaller
import com.sickworm.intellij.jugg.rpc.RpcRequest
import com.sickworm.intellij.jugg.rpc.RpcResponse
import com.sickworm.intellij.jugg.server.JuggHotUpdateDownloader
import kotlinx.coroutines.*
import org.jetbrains.annotations.TestOnly
import org.jetbrains.kotlin.utils.addToStdlib.measureTimeMillisWithResult
import java.io.File
import java.lang.Runnable
import kotlin.system.measureTimeMillis


class JuggManager @TestOnly constructor(
    val project: Project,
    val pathManager: JuggPathManager,
    private val coroutineScope: CoroutineScope = CoroutineScope(Dispatchers.IO),
    val logger: Logger = JuggLogger.getInstance(project, "JuggManager"),
    private val juggServer: JuggServer = JuggServer(project.name, pathManager, coroutineScope, logger),
    private val juggHotUpdateDownloader: JuggHotUpdateDownloader = JuggHotUpdateDownloader(juggServer, logger),
    private val fileChangesHandler: IFileChangesHandler = FileChangesHandler(pathManager.projectDir, pathManager.juggRootDir, JuggLogger.getInstance(project, "FileChangesHandler")),
    private val fileChangesDetector: IFileChangesDetector = FileChangesDetector(project, pathManager.projectDir),
    private val deployHistoryManager: IDeployHistoryManager = DeployHistoryManager(
        pathManager,
        fileChangesHandler,
        JuggLogger.getInstance(project, "DeployHistoryManager")
    ),
    private val deployFileManager: DeployFileManager = DeployFileManager(
        pathManager.projectDir,
        JuggLogger.getInstance(project, "DeployFileManager"),
        pathManager.tmpDir,
        pathManager.databaseDir,
        coroutineScope,
    ),
    private val compileContextManager: CompileContextManager = CompileContextManager(project, pathManager, deployFileManager, deployHistoryManager),
    private val deployTargetManager: IDeployTargetManager = DeployTargetManager(project),
    private val deployStateManager: DeployStateManager = DeployStateManager(project, deployTargetManager, deployHistoryManager),
    private val juggRunningTaskStatusManager: IJuggRunningTaskStatusManager = JuggRunningTaskStatusManager(),
    private val dependencyChangeManager: IDependencyChangeManager = IDependencyChangeManager.create(JuggLogger.getInstance(project, "DependencyChangeManager")),
    private val taskRunnerManager: TaskRunnerManager = TaskRunnerManager(project, logger, deployStateManager, juggServer, coroutineScope),
    private val gradleProjectInfoLocalFetchManager: GradleProjectInfoLocalFetchManager = GradleProjectInfoLocalFetchManager(project, pathManager, compileContextManager, taskRunnerManager, dependencyChangeManager, logger),
    private val gitFileChangesDetector: GitFileChangesDetector = GitFileChangesDetector(deployHistoryManager, taskRunnerManager, logger),
    private val juggDeployerHelper: JuggDeployerHelper = JuggDeployerHelper(project, deployTargetManager, deployFileManager, deployHistoryManager, deployStateManager, dependencyChangeManager, compileContextManager, juggServer, taskRunnerManager),
    private val juggCompilerHelper: JuggCompilerHelper = JuggCompilerHelper(project, pathManager, juggServer, deployTargetManager, deployStateManager, deployFileManager, deployHistoryManager, juggRunningTaskStatusManager, compileContextManager, fileChangesHandler, dependencyChangeManager, gradleProjectInfoLocalFetchManager),
    private val customConfigManager: CustomConfigManager = CustomConfigManager(pathManager.configDir, JuggLogger.getInstance(project, "CustomConfigManager")),
    private val customCompilerManager: CustomCompilerManager = CustomCompilerManager(pathManager.projectDir, pathManager.customCompilerDir, juggServer, logger),
    private val ideSyncProblemResolver: IdeSyncProblemResolver = IdeSyncProblemResolver(project),
    ): IJuggManagerCaller, Disposable, CoroutineScope by coroutineScope {

    constructor(
        project2: Project,
        pathManager: JuggPathManager,
    ): this(project = project2, pathManager)

    override fun init() {
        Disposer.register(this, juggCompilerHelper)
        Disposer.register(this, gradleProjectInfoLocalFetchManager)
        runTaskSafe("Init Jugg", {
            AsDeployerCompat.init(JuggLogger.getInstance(project, "AsDeployerCompat"))
            loadCustomConfig()
            tryCreateRunConfigurations(isSyncFinished = false)
            IAsDeployerCompat.updateMinApi(JuggSettings.finalIsEnableCompatibleDeploymentMode)
            ProjectInfoReader(project, logger.getInstance("ProjectInfoReader")).printInfo()
            deployHistoryManager.checkProjectDirChanged()
            logger.info("Start jugg finished.")

            // init project info async
            runTaskSafe("Init project info", ::recoverDeployContext)
            // init deployment service async
            JuggDeploymentService.preInit(logger)

            logger.debug("Checking updates...")
            juggServer.checkUpdate {
                val checkUpdateHandler = CheckUpdateHandler(
                    project, juggServer.version, customConfigManager,
                    JuggLogger.getInstance(project, "CheckUpdateHandler"),
                )
                checkUpdateHandler.handle(it)
                loadCustomConfig()
                juggHotUpdateDownloader.init(project)
            }
        })
    }

    private fun loadCustomConfig() {
        try {
            if (!customConfigManager.isConfigChanged()) {
                return
            }
            customConfigManager.config?.let { config ->
                juggServer.updateServer(config.servers)
                fileChangesHandler.updateBuildFileRules(config.buildFileRules, config.moduleCustomConfigs?.map { it.moduleStdPath } ?: emptyList())
                deployHistoryManager.updateDontFilterIgnoredFileRules(config.dontFilterIgnoredFileRules)
                compileContextManager.updateCustomClasspath(config.moduleCustomConfigs ?: emptyList())
                customCompilerManager.updateCustomCompilers(config.customCompilers)
            }
        } catch (e: Exception) {
            // maybe structure is updated
            logger.info("loadCustomConfig failed", e)
        }
    }

    private fun updateProjectInfo(isAfterSync: Boolean) {
        logger.debug("updateProjectInfo isAfterSync: $isAfterSync")

        if (isAfterSync) {
            // gradle sync finished, reset hasRun flag to avoid "No file changes" fallback
            juggRunningTaskStatusManager.resetHasRun()
        }

        // update project info if needed
        var isForceUpdateGradle = false
        val isUpdated = compileContextManager.updateCompileContext(isAfterSync) {
            isForceUpdateGradle = true
        }
        logger.debug("updateProjectInfo isUpdated: $isUpdated, isForceUpdateGradle: $isForceUpdateGradle")
        gradleProjectInfoLocalFetchManager.runUpdateIfNeeded(isForceUpdateGradle)

        // reinit compiler after update compile context
        if (isUpdated) {
            reInitOnCompileContextUpdate()
            dependencyChangeManager.onEndSyncing(isFromIde = true, true, compileContextManager.compileContext)
            if (!isCompiling) {
                warmUpCompile()
                launch {
                    // do it async to let warmUpCompile run
                    dependencyChangeManager.tryShowChangeConfirmDialog(isRunCompileLater = true)
                }
            }
        }

        // check dependency again to avoid missing dependency(in ide little chance)
        if (isAfterSync) {
            taskRunnerManager.runBackgroundSafe("Check Project Info Delay", delayMs = 5000L) {
                updateProjectInfo(isAfterSync = false)
            }
        }
    }

    override fun onSyncEvent(syncEvent: SyncEvent) {
        logger.debug("onSyncEvent: $syncEvent")
        try {
            when (syncEvent) {
                SyncEvent.SUCCEEDED -> {
                    ideSyncProblemResolver.onIdeSyncSucceeded()
                    tryCreateRunConfigurations(isSyncFinished = true)
                    runTaskSafe("Update project info", { updateProjectInfo(isAfterSync = true) })
                }
                SyncEvent.SKIPPED -> {
                    tryCreateRunConfigurations(isSyncFinished = false)
                    runTaskSafe("Update project info", { updateProjectInfo(isAfterSync = false) })
                }
                SyncEvent.STARTED -> {
                    dependencyChangeManager.onStartSyncing(isFromIde = true)
                }
                SyncEvent.FAILED -> {
                    dependencyChangeManager.onEndSyncing(isFromIde = true, false, compileContextManager.compileContext)
                }
            }
        } catch (e: Throwable) {
            logger.warn("onSyncEvent failed: ", e)
        }
    }

    @Synchronized
    private fun tryCreateRunConfigurations(isSyncFinished: Boolean, maxRetryCount: Int = 5) {
        TimeLogger.start("tryCreateDefaultRunConfiguration")
        val currentList = RunManager.getInstance(project).getConfigurationSettingsList(JuggConfigurationType::class.java)
        val currentListNames = currentList.map { it.name }
        logger.debug("JuggConfigurationType currentList: $currentListNames")

        val currentListNamesExceptDefault = currentList.filter { it.name != SuggestRunConfiguration.DEFAULT.runConfigName }
        if (currentListNamesExceptDefault.isNotEmpty() && !isSyncFinished) {
            logger.debug("Not sync finished and exits non-default configs is not empty, skip create default run configuration")
            return
        }

        val suggestRunConfiguration =
            try {
                AsDeployerCompat.getSuggestRunConfigurations(
                    currentListNames, project,
                    logger.getInstance("GetSuggestRunConfigurations"),
                    isNeedDefaultRunConfig = maxRetryCount <= 0,
                )
            } catch (e: Throwable) {
                logger.warn("Get suggest run configuration failed ", e)
                if (RuntimeMockUtils.isTestMode) {
                    throw e
                }
                emptyList()
            }
        logger.debug("Suggest run configurations: $suggestRunConfiguration")
        if (suggestRunConfiguration.isEmpty()) {
            logger.debug("No suggest run configuration")
            if (currentListNamesExceptDefault.isEmpty() && isSyncFinished && maxRetryCount > 0) {
                logger.debug("No current run configuration, retry after 2s")
                launch {
                    delay(2000)
                    tryCreateRunConfigurations(isSyncFinished = true, maxRetryCount = maxRetryCount - 1)
                }
            }
            return
        }

        val settingsList = suggestRunConfiguration.map { suggest ->
            val factory: ConfigurationFactory = JuggConfigurationType.getInstance().configurationFactories[0]
            val settings = RunManager.getInstance(project).createConfiguration(suggest.runConfigName, factory)
            (settings.configuration as JuggRunConfiguration).state?.let {
                it.compileCommand = suggest.compileCommand
                it.outputApkName = suggest.outputApkPath
                it.setDefaultRemoteOption(JuggSettings.defaultCompileSettings)
            }
            settings.isActivateToolWindowBeforeRun = false
            settings
        }
        settingsList.forEach {
            RunManager.getInstance(project).addConfiguration(it)
        }

        // select if first created except default
        val settingsListExceptDefault = settingsList.filter { it.name != SuggestRunConfiguration.DEFAULT.runConfigName }
        if (currentListNamesExceptDefault.isEmpty() && settingsListExceptDefault.isNotEmpty()) {
            val settings = settingsListExceptDefault[0]
            RunManager.getInstance(project).selectedConfiguration = settings
        }
        TimeLogger.end("tryCreateDefaultRunConfiguration", logger)
    }

    @TestOnly
    fun recoverDeployContext() {
        logger.debug("Start recover deploy context")

        val deployContextRecoverInfo = deployHistoryManager.tryGetContextRecoverInfoFromDb(isOnInit = true)
        if (deployContextRecoverInfo == null) {
            logger.debug("Can not recover from deploy history, please run gradle compile first")
            return
        } else {
            logger.debug("Recover deploy context from history successfully:")
            logger.debug("$deployContextRecoverInfo")
        }

        // step 1: recover compile context
        initCompile(deployContextRecoverInfo.compileContextInfo, deployContextRecoverInfo.deployedFiles,
            startCompileTime = null
        )
        // step 2: recover deploy files
        logger.debug("Start recover deploy history...")
        deployTargetManager.setApks(deployContextRecoverInfo.compileContextInfo.apkInfos)
        // step 3: recover changed files
        processFileChanged(deployContextRecoverInfo.changedFiles, emptyList(), isFromRecover = true)

        logger.debug("Deploy history recover successfully, no need full compile.")
    }

    fun updateDeployState(): JuggDeployState {
        val oldDeployState = deployStateManager.deployState
        val deployState = deployStateManager.updateDeployState()
        if (deployState == oldDeployState) {
            // won't do anything if deploy state is not changed
            return deployState
        }

        return deployState
    }

    private fun processFileChanged(
        changedFiles: List<File>,
        deletedFiles: List<File>,
        isFromRecover: Boolean,
    ) {
        // prints file changed info
        if (deletedFiles.isNotEmpty()) {
            // not strict rules, just print it out for debug
            val simpleFilterFiles = changedFiles.filter {
                !it.path.contains("build") &&
                        !it.path.contains(".idea") &&
                        !it.path.contains(".git") &&
                        it.name != ".DS_Store"
            }
            if (simpleFilterFiles.isNotEmpty()) {
                logger.debug("Detect file deleted: ${simpleFilterFiles.map { it.name }}")
            }
            deployFileManager.removeChangedFile(deletedFiles)
        }
        if (changedFiles.isNotEmpty()) {
            // not strict rules, just print it out for debug
            val simpleFilterFiles = changedFiles.filter {
                !it.path.contains("build") &&
                    !it.path.contains(".idea") &&
                    !it.path.contains(".git") &&
                    !it.path.contains(".gradle") &&
                    it.name != ".DS_Store"
            }
            if (simpleFilterFiles.isNotEmpty()) {
                logger.debug("Detect file changed (before filter): ${simpleFilterFiles.map { it.path }}")
            }
        }

        val realChangedFiles = fileChangesHandler.filter(changedFiles)
        realChangedFiles.forEach {
            logger.debug("Detect file changed: [${it.type}]${it.file.path}")
        }
        if (realChangedFiles.isEmpty()) {
            return
        }

        deployFileManager.addChangedFile(realChangedFiles)

        val isBuildFileChanged = realChangedFiles.any { it.type == CompileFile.Type.BuildFile }
        if (isBuildFileChanged || isFromRecover) {
            val allBuildFiles = deployFileManager.getUndeployedFiles()
                .filter { it.type == CompileFile.Type.BuildFile }
                .map { it.file }
            dependencyChangeManager.onUpdateChangedBuildFiles(allBuildFiles)
        }

        if (!isFromRecover) {
            gitFileChangesDetector.onSourceFileChanged(realChangedFiles)
        }

        if (JuggSettings.compileOnSave) {
            runTaskSafe("Compile Changes", ::compileChanges)
        }
    }

    @Volatile
    private var currentTask: JuggRunningTask? = null
    private val isCompiling: Boolean get() = currentTask?.isRunning == true

    private fun cancelCurrentTask(processHandler: IProcessHandler, onFinish: () -> Unit) {
        val currentTask = currentTask
        if (currentTask == null) {
            logger.debug("Current task is null")
            onFinish()
            return
        }
        if (!currentTask.isRunning) {
            logger.debug("Current task is not running")
            onFinish()
            return
        }
        logger.warn("Canceling task...")
        processHandler.notifyTextAvailable("Waiting last task finishing... \n\n", ProcessOutputType.STDOUT)
        currentTask.cancel(onFinish)
    }

    override fun runTask(options: JuggRunConfigurationOptions): ExecutionResult {
        val compileUiHandler = JuggCompileUiHandler(project,
            isForceGradleCompile = ForceGradleCompileHelper.isForceGradleCompileNextTime,
            isRpcMode = false,
            options.toCompileOptions(pathManager),
            logger
        )
        return runTask(options, compileUiHandler)
    }

    fun runTask(options: JuggRunConfigurationOptions, compileUiHandler: CompileUiHandler): ExecutionResult {
        if (ForceGradleCompileHelper.isCleanAndReinstallNextTime) {
            forceReInstallNextTime()
        }
        val consoleView = TextConsoleBuilderFactory.getInstance().createBuilder(project).console
        val processHandler = SimpleProcessHandler()
        consoleView.attachToProcess(processHandler)
        processHandler.startNotify()
        compileUiHandler.processHandler = processHandler

        cancelCurrentTask(processHandler) {
            val task = createRunningTask(options.toCompileOptions(pathManager), compileUiHandler)
            ProgressManager.getInstance().run(task)
        }
        ForceGradleCompileHelper.isCleanAndReinstallNextTime = false
        ForceGradleCompileHelper.isForceGradleCompileNextTime = false
        return DefaultExecutionResult(consoleView, processHandler)
    }

    private fun createRunningTask(
        options: JuggGradleCompileOptions,
        compileUiHandler: CompileUiHandler,
    ): JuggRunningTask {
        logger.debug("Create running task: ${options.toSafeString()}")

        val startCompileTime = System.currentTimeMillis()
        val initIncrementalCompileTask = task@{
            // do it async
            fun action() {
                initIncrementalCompileAfterFullBuild(startCompileTime, options.isRemoteCompile)
            }
            runTaskSafe("Init Incremental Compile", ::action)
        }
        val task = JuggRunningTask(options, project, juggServer, deployTargetManager, dependencyChangeManager,
            juggRunningTaskStatusManager, deployHistoryManager, juggCompilerHelper, juggDeployerHelper, initIncrementalCompileTask,
            compileUiHandler,
        )
        currentTask = task

        // try reload custom config if changed
        loadCustomConfig()

        return task
    }

    @TestOnly
    fun compileChanges() {
        juggCompilerHelper.incrementalCompile(CompileUiHandler.DEFAULT)
    }

    @TestOnly
    fun initIncrementalCompileAfterFullBuild(startCompileTime: Long, isRemoteCompile: Boolean = false) {
        JuggLogger.resetLatestCompileLog(project)
        juggServer.afterFullCompile()
        pathManager.stagingDir.deleteRecursively()
        compileContextManager.compileContext.tempCompileDir.deleteRecursively()

        logger.debug("Init compile after full build, isRemoteCompile=$isRemoteCompile")
        if (!isRemoteCompile) {
            compileContextManager.updateCompileContextAfterLocalFetch()
        }

        var projectInfo = compileContextManager.getProjectInfo()

        logger.info("Fetching classpath...")
        val backupProjectInfo = juggCompilerHelper.fetchClasspath(
            isRemoteCompile, projectInfo, taskRunnerManager.currentIndicator, coroutineScope)
        if (backupProjectInfo == null) {
            if (isRemoteCompile) {
                logger.warn("Fetch classpath failed, unable to init incremental compile. Please check log for details.")
                // unable to continue
                return
            } else {
                logger.debug("Fetch classpath failed, use local classpath instead.")
                // just continue use local build classpath
            }
        } else {
            projectInfo = backupProjectInfo
        }

        val (costTime: Long, compileContextInfo: CompileContextInfo) = measureTimeMillisWithResult {
            pathManager.compileRootDir.clearDir()
            val apkInfos = deployTargetManager.getApks()
            if (apkInfos.isEmpty()) {
                logger.warn("Init compile failed for no apk found")
                return
            }
            deployHistoryManager.reInitAfterFullCompiled(
                apkInfos,
                projectInfo.modules,
                startCompileTime,
            )

        }
        logger.debug("reInitAfterFullCompiled cost ${costTime}ms")

        if (isRemoteCompile) {
            copyGeneratedSourceToLocal()
        }
        initCompile(compileContextInfo, emptyList(),
            startCompileTime = startCompileTime,
        )

        // checks whether project info is missing(cleaned by gradle)
        if (ideSyncProblemResolver.isNeedSyncAfterBuild()) {
            updateProjectInfo(true) // the IDE may not return Sync Success, so we read info here
        } else {
            updateProjectInfo(false)
        }
    }

    override fun gradleCompile() {
        logger.debug("[action] gradleCompile")
        ForceGradleCompileHelper.executeGradleCompile(this)
    }

    override fun restartApp() {
        AsDeployerCompat.getSelectedDevices(project)?.forEach {
            deployTargetManager.restartApp(it)
        }
    }

    fun forceReInstallNextTime() {
        // clear lastDeployOverlayIds to force re-reinstall
        deployHistoryManager.isCleanAndReinstall = true
        juggRunningTaskStatusManager.resetHasRun()
    }

    fun exportIncrementalApk(dialog: DialogWrapper) {
        logger.debug("exportIncrementalApk")
        ExportIncrementalApkHelper(project, taskRunnerManager, deployFileManager, logger)
            .exportIncrementalApk(dialog, compileContextManager.compileContext)
    }

    private fun copyGeneratedSourceToLocal() {
        logger.info("copyGeneratedSourceToLocal")
        taskRunnerManager.runTaskSafe("Copy Generated Source to local", {
            val modules = compileContextManager.compileContext.modules
            modules.values.forEach module@{
                val baseDir = ModuleBuildPathInfo(it.projectRootDir, it.moduleRootDir, it.buildVariant).buildDir
                it.buildPathInfo.syncToLocalPathList.forEach { fileOrDirInClasspath ->
                    val fileOrDirInLocal = fileOrDirInClasspath.changeBaseDir(it.buildPathInfo.buildDir, baseDir)
                    logger.debug("Copy generated source from $fileOrDirInClasspath to $fileOrDirInLocal")
                    if (!fileOrDirInClasspath.exists()) {
                        logger.debug("Skip copy, $fileOrDirInClasspath not exists")
                        return@forEach
                    }
                    if (fileOrDirInClasspath.path.equals(fileOrDirInLocal.path)) {
                        logger.debug("Skip copy, source and target are the same")
                        return@forEach
                    }
                    if (fileOrDirInLocal.exists() && !fileOrDirInLocal.isDirectory) {
                        fileOrDirInLocal.delete()
                    }
                    fileOrDirInClasspath.copyRecursively(fileOrDirInLocal, overwrite = true)
                }
            }
        }, isBlockIncrementalCompile = false)
    }

    override fun getMoreOptions(options: JuggRunConfigurationOptions): ActionGroup {
        return MoreOptionsManager(
            this, pathManager, taskRunnerManager,
            deployHistoryManager, deployTargetManager, dependencyChangeManager,
            juggCompilerHelper, juggServer, juggHotUpdateDownloader, logger,
        ).createOptions(options)
    }

    override fun getJuggRunSettingsComponent(): IJuggRunSettingsComponent {
        try {
            val result = JuggRunSettingsComponent()
            logger.debug("getJuggRunSettingsComponent ok")
            return result
        } catch (e: LinkageError) {
            logger.warn("getJuggRunSettingsComponent failed: ", e)
            throw e
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun reportIssue() {
        val isConfirmed = ReportConfirmDialog().showAndGet()
        if (!isConfirmed) {
            return
        }

        val dialog = ReportProgressDialog()
        taskRunnerManager.runBackgroundSafe("Report issue") {
            dialog.setProgress("Dumping logcat...")
            ProjectInfoReader(project, JuggLogger.getInstance(project, "ProjectInfoReader")).printInfo()
            val logcatErrorLog = deployTargetManager.dumpErrorLogs()
            dialog.setProgress("Uploading logs...")
            val deferred = juggServer.reportAndUploadLogs(logcatErrorLog)
            deferred.invokeOnCompletion {
                val uploadResult = deferred.getCompleted()
                dialog.setResult(uploadResult)
            }
        }
        dialog.show()
    }

    override fun call(rpcRequest: RpcRequest): RpcResponse {
        return RpcCaller(this, gitFileChangesDetector).call(rpcRequest)
    }

    private fun reInitOnCompileContextUpdate() {
        deployFileManager.updateModuleInfos(compileContextManager.compileContext.modules)
        juggCompilerHelper.juggCompiler = JuggCompiler(compileContextManager.compileContext, this, customCompilerManager::getCustomCompilers)
        fileChangesHandler.init(compileContextManager.compileContext)
        gitFileChangesDetector.init(pathManager.projectDir, compileContextManager.compileContext.modules)
    }

    private fun initCompile(
        compileContextInfo: CompileContextInfo,
        deployedFiles: List<CompileOutput>,
        startCompileTime: Long?,
    ) {
        logger.info("Init compile...")

        deployStateManager.isBuildFileChanged = false

        var finalApkInfos = compileContextInfo.apkInfos
        logger.debug("hasEmbeddedApks: ${customConfigManager.hasEmbeddedApks()}")
        if (customConfigManager.hasEmbeddedApks()) {
            finalApkInfos = customConfigManager.fillApkInfosWithEmbeddedApks(finalApkInfos, pathManager.localClasspathStoragePathManager.embeddedApkDir)
        }

        val costTime = measureTimeMillis {
            compileContextManager.setCompileContext(compileContextInfo)
            deployFileManager.init(finalApkInfos, deployedFiles, startCompileTime)
            dependencyChangeManager.init(pathManager.projectInfosDir, compileContextManager.compileContext)
            reInitOnCompileContextUpdate()
        }
        logger.debug("Init compile cost ${costTime}ms")

        fileChangesDetector.startListen(object: FileChangesListener {
            override fun onFileChanges(changedFiles: List<File>, deletedFiles: List<File>) {
                processFileChanged(changedFiles, deletedFiles, isFromRecover = false)
            }
        })
        gitFileChangesDetector.startListen(object: FileChangesListener {
            override fun onFileChanges(changedFiles: List<File>, deletedFiles: List<File>) {
                processFileChanged(changedFiles, deletedFiles, isFromRecover = false)
            }
        })

        logger.info("Jugg init complete, start listening file changes.")

        if (JuggSettings.isEnableWarmUp) {
            warmUpCompile()
        }
    }

    private fun warmUpCompile() {
        runTaskSafe("Warm Up Compile", {
            juggCompilerHelper.warmUp()
        })
    }

    private fun runTaskSafe(jobName: String, action: Runnable, isNeedShowIndicator: Boolean = true) {
        taskRunnerManager.runTaskSafe(jobName, action, isNeedShowIndicator)
    }

    override fun dispose() {
        logger.debug("project ${pathManager.projectDir} dispose")
        coroutineScope.cancel()
    }
}