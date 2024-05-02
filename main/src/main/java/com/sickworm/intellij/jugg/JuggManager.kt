package com.sickworm.intellij.jugg

import com.android.ddmlib.IDevice
import com.intellij.execution.RunManager
import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessOutputType
import com.intellij.openapi.Disposable
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.DumbProgressIndicator
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.sickworm.intellij.jugg.compiler.*
import com.sickworm.intellij.jugg.deploy.*
import com.sickworm.intellij.jugg.deploy.run.AsDeployerCompat
import com.sickworm.intellij.jugg.deploy.run.JuggDeployerHelper
import com.sickworm.intellij.jugg.deploy.run.JuggDeploymentService
import com.sickworm.intellij.jugg.deploy.run.SuggestRunConfiguration
import com.sickworm.intellij.jugg.ide.*
import com.sickworm.intellij.jugg.ide.ChangedFileInfo
import com.sickworm.intellij.jugg.ide.JuggStateListener
import com.sickworm.intellij.jugg.server.ReportEventData
import com.sickworm.intellij.jugg.logger.JuggLogger
import com.sickworm.intellij.jugg.logger.TimeLogger
import com.sickworm.intellij.jugg.logger.getInstance
import com.sickworm.intellij.jugg.server.JuggServer
import com.sickworm.intellij.jugg.project.*
import com.sickworm.intellij.jugg.server.CheckUpdateHandler
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
    private val logger: Logger = JuggLogger.getInstance(project, "JuggManager"),
    private val juggServer: JuggServer = JuggServer(project, pathManager),
    private val fileChangesHandler: IFileChangesHandler = FileChangesHandler(pathManager.projectDir, pathManager.juggRootDir, JuggLogger.getInstance(project, "FileChangesHandler")),
    private val fileChangesDetector: IFileChangesDetector = FileChangesDetector(project, pathManager.projectDir),
    private val deployHistoryManager: IDeployHistoryManager = DeployHistoryManager(
        pathManager.projectDir,
        pathManager.historyDir,
        JuggLogger.getInstance(project, "DeployHistoryManager")
    ),
    private val deployFileManager: DeployFileManager = DeployFileManager(
        JuggLogger.getInstance(project, "DeployFileManager"),
        pathManager.tmpDir,
        pathManager.historyDir,
        coroutineScope,
    ),
    private val compileContextManager: CompileContextManager = CompileContextManager(project, pathManager, deployFileManager),
    private val deployTargetManager: IDeployTargetManager = DeployTargetManager(project),
    var deployStateListener: JuggStateListener = JuggStateListener.emptyImpl,
    private val deployStateManager: DeployStateManager = DeployStateManager(project, deployTargetManager, deployHistoryManager),
    private val juggRunningTaskStatusManager: IJuggRunningTaskStatusManager = JuggRunningTaskStatusManager(),
    private val juggDeployerHelper: JuggDeployerHelper = JuggDeployerHelper(project, deployTargetManager, deployFileManager, deployHistoryManager, deployStateManager, juggServer, { deployStateListener }),
    private val juggCompilerHelper: JuggCompilerHelper = JuggCompilerHelper(project, pathManager.localClasspathStoragePathManager, juggServer, deployTargetManager, deployStateManager, deployFileManager, deployHistoryManager, juggRunningTaskStatusManager, compileContextManager, fileChangesHandler, { deployStateListener }),
    private val customConfigManager: CustomConfigManager = CustomConfigManager(pathManager.configDir, JuggLogger.getInstance(project, "CustomConfigManager")),
    ): Disposable, CoroutineScope by coroutineScope {

    constructor(
        project2: Project,
        pathManager: JuggPathManager,
    ): this(project = project2, pathManager)

    fun init() {
        Disposer.register(this, juggCompilerHelper)
        runTaskSafe("Init Jugg", {
            AsDeployerCompat.init(JuggLogger.getInstance(project, "AsDeployerCompat"))
            loadCustomConfig()
            ProjectInfoReader(project, logger.getInstance("ProjectInfoReader")).printInfo()
            tryCreateRunConfigurations(isSyncFinished = false)
            logger.info("Start jugg finished.")

            // init project info async
            initProjectInfo(isNeedReloadProjectInfo = false)

            // init deployment service async
            JuggDeploymentService.postWithLock {
                val costTime = measureTimeMillis {
                    preInit()
                }
                logger.debug("JuggDeploymentService.preInit cost ${costTime}ms")
            }

            logger.debug("Checking updates...")
            juggServer.checkUpdate {
                val checkUpdateHandler = CheckUpdateHandler(
                    project, juggServer.version, customConfigManager,
                    JuggLogger.getInstance(project, "CheckUpdateHandler"),
                )
                checkUpdateHandler.handle(it)
                loadCustomConfig()
            }
        })
    }

    private fun loadCustomConfig() {
        if (!customConfigManager.isConfigChanged()) {
            return
        }
        customConfigManager.config?.let { config ->
            config.serverUrl?.let {
                juggServer.updateServerUrl(it)
            }
            config.buildFileList.let {
                fileChangesHandler.updateBuildFileList(it)
            }
        }
    }

    fun initProjectInfo(isNeedReloadProjectInfo: Boolean) {
        runTaskSafe("Init Project Info", {
            if (isNeedReloadProjectInfo) {
                // gradle sync finished, reset hasRun flag to avoid "No file changes" fallback
                juggRunningTaskStatusManager.resetHasRun()
            }

            if (!deployStateManager.deployState.isReadyIncCompile) {
                logger.debug("Deploy state is not ready inc compile")
                recoverDeployContext(isNeedReloadProjectInfo)
            } else {
                logger.debug("Deploy state is ready inc compile, isNeedReloadProjectInfo=$isNeedReloadProjectInfo")
                if (!isNeedReloadProjectInfo) {
                    return@runTaskSafe
                }
                val isSuccess = compileContextManager.refreshCompileContext()
                if (isSuccess) {
                    reInitOnCompileContextUpdate()
                }
            }
        })
    }

    fun onSyncEvent(syncEvent: SyncEvent) {
        logger.debug("onSyncEvent: $syncEvent")
        when (syncEvent) {
            SyncEvent.SUCCEEDED -> {
                tryCreateRunConfigurations(isSyncFinished = true)
                initProjectInfo(isNeedReloadProjectInfo = true)
            }
            SyncEvent.SKIPPED -> {
                tryCreateRunConfigurations(isSyncFinished = false)
            }
            else -> {
            }
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

        val suggestRunConfiguration = AsDeployerCompat.getSuggestRunConfigurations(
            currentListNames, project,
            logger.getInstance("GetSuggestRunConfigurations"),
            isNeedDefaultRunConfig = maxRetryCount <= 0,
        )
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

    private fun recoverDeployContext(isNeedReloadProjectInfo: Boolean) {
        logger.debug("Start recover deploy context")

        val deployContextRecoverInfo = deployHistoryManager.tryGetContextRecoverInfoFromDb()
        if (deployContextRecoverInfo == null) {
            logger.debug("Can not recover from deploy history, please run gradle compile first")
            return
        } else {
            logger.debug("Recover deploy context from history successfully:")
            logger.debug("$deployContextRecoverInfo")
        }

        // step 1: recover compile context
        initCompile(deployContextRecoverInfo.compileContextInfo, deployContextRecoverInfo.deployedFiles,
            isNeedReloadProjectInfo, false, null)
        // step 2: recover deploy files
        logger.debug("Start recover deploy history...")
        deployTargetManager.setApks(deployContextRecoverInfo.compileContextInfo.apkInfos)
        // step 3: recover changed files
        processFileChanged(deployContextRecoverInfo.changedFiles)
        // step 4: update deploy state
        updateDeployState()

        logger.debug("Deploy history recover successfully, no need full compile.")
    }

    fun updateDeployState(): JuggDeployState {
        val oldDeployState = deployStateManager.deployState
        val deployState = deployStateManager.updateDeployState()
        if (deployState == oldDeployState) {
            // won't do anything if deploy state is not changed
            return deployState
        }

        deployStateListener.onDeployStateUpdate(deployState)
        return deployState
    }

    private fun processFileChanged(changedFiles: List<File>) {
        val deletedFiles = changedFiles.filter { !it.exists() }
        if (deletedFiles.isNotEmpty()) {
            deployFileManager.removeChangedFile(deletedFiles)
        }

        val realChangedFiles = fileChangesHandler.filter(changedFiles)
        realChangedFiles.forEach {
            logger.debug("${it.type} file changed: ${it.file.name}")
        }
        if (realChangedFiles.isEmpty()) {
            return
        }

        deployFileManager.addChangedFile(realChangedFiles)
        deployStateListener.onFileStatesUpdate(realChangedFiles.map {
            ChangedFileInfo(it.file, ChangedFileInfo.State.MODIFIED)
        })

        if (JuggSettings.compileOnSave) {
            runTaskSafe("Compile Changes", ::compileChanges)
        }
    }

    @Volatile
    private var currentTask: JuggRunningTask? = null

    fun cancelCurrentTask(processHandler: ProcessHandler, onFinish: () -> Unit) {
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

    fun createRunningTask(
        options: JuggGradleCompileOptions,
        processHandler: SimpleProcessHandler,
    ): JuggRunningTask {
        logger.debug("Create running task: ${options.toSafeString()}")

        val startCompileTime = System.currentTimeMillis()
        val compileTask= task@{ indicator: ProgressIndicator, isForceInstall: Boolean ->
            return@task juggCompilerHelper.compile(options, processHandler, indicator, isForceInstall)
        }
        val deployTask = task@{ device: IDevice, isInstall: Boolean, isLastDevice: Boolean ->
            return@task juggDeployerHelper.deploy(device, isLastDevice, processHandler, isInstall)
        }
        val initIncrementalCompileTask = task@{
            // do it async
            fun action() {
                initIncrementalCompileAfterFullBuild(startCompileTime, options.isRemoteCompile)
            }
            runTaskSafe("Init Incremental Compile", ::action)
        }
        val task = JuggRunningTask(project, juggServer, deployTargetManager,
            juggRunningTaskStatusManager, processHandler, compileTask, deployTask, initIncrementalCompileTask)
        currentTask = task

        // try reload custom config if changed
        loadCustomConfig()

        return task
    }

    @TestOnly
    fun compileChanges() {
        juggCompilerHelper.incrementalCompile(SimpleProcessHandler())
    }

    @TestOnly
    fun initIncrementalCompileAfterFullBuild(startCompileTime: Long, isRemoteCompile: Boolean = false) {
        JuggLogger.resetLatestCompileLog(project)
        juggServer.afterFullCompile()

        logger.debug("Init compile after full build, isRemoteCompile=$isRemoteCompile")

        var allModules = compileContextManager.getAllModulesByModuleManager(isNeedReloadProjectInfo = false)
        val moduleBuildPathInfos = allModules.map { it.value.buildPathInfo }

        logger.info("Fetching classpath...")
        val (costTime2, classpathRootDir) = measureTimeMillisWithResult {
            val originText = currentIndicator?.text
            currentIndicator?.text = "Jugg: Fetching classpath..."
            val result = juggCompilerHelper.fetchClasspathResult(isRemoteCompile, moduleBuildPathInfos)
            currentIndicator?.text = originText
            return@measureTimeMillisWithResult result
        }
        logger.debug("fetchClasspathResult cost ${costTime2}ms")
        logger.debug("fetchClasspathResult classpathRootDir = $classpathRootDir")
        if (classpathRootDir == null || !classpathRootDir.exists()) {
            logger.warn("Fetch classpath failed, please check log for details.")
            return
        }
        // wrap local CompileContextInfo to CompileContextInfo fetched from remote
        allModules = allModules.values
            .map {
                it.copy(buildPathInfo = ModuleBuildPathInfo(
                    classpathRootDir,
                    File(classpathRootDir, it.buildPathInfo.modulePathRelative.path),
                    it.buildVariant,
                ))
            }
            .associateBy { it.name }

        val (costTime: Long, compileContextInfo: CompileContextInfo) = measureTimeMillisWithResult {
            pathManager.compileRootDir.clearDir()
            val apkInfos = deployTargetManager.getApks()
            if (apkInfos.isEmpty()) {
                logger.warn("Init compile failed for no apk found")
                return
            }
            deployHistoryManager.reInitAfterFullCompiled(
                apkInfos,
                allModules,
                startCompileTime,
            )

        }
        logger.debug("reInitAfterFullCompiled cost ${costTime}ms")

        initCompile(compileContextInfo, emptyList(), false,
            isNeedWarmUpDeploy = JuggSettings.isEnableWarmUpDeploy,
            startCompileTime = startCompileTime,
        )
    }

    fun restartApp() {
        AsDeployerCompat.getDevices(project)?.forEach {
            deployTargetManager.restartApp(it)
        }
    }

    fun markAsSyncedAndReInitCompiler(isNeedReloadProjectInfo: Boolean) {
        logger.info("[test options] markAsSyncedAndReInitCompiler")
        initProjectInfo(isNeedReloadProjectInfo)
    }

    fun markAsGradleCompiledAndReInitCompiler(options: JuggRunConfigurationOptions) {
        logger.info("[test options] markAsGradleCompiledAndReInitCompiler")
        runTaskSafe("Mark as Gradle Compiled", {
            val compileOptions = JuggGradleCompileOptions.fromOptions(
                pathManager.projectDir.absolutePath,
                pathManager.localClasspathStoragePathManager,
                options,
            )

            // login and get apks
            val result = juggCompilerHelper.gradleCompile(
                compileOptions,
                SimpleProcessHandler(),
                currentIndicator ?: DumbProgressIndicator.INSTANCE,
                isOnlyFetchResult = true,
            )
            if (!result.isSuccess) {
                logger.warn("gradleCompile(isOnlyFetchResult) failed, please check log for details.")
                return@runTaskSafe
            }

            // re-init compiler and mark all compiled
            initIncrementalCompileAfterFullBuild(System.currentTimeMillis(), compileOptions.isRemoteCompile)
        })
    }

    fun dumpLogcatErrorLogs(): String {
        return deployTargetManager.dumpErrorLogs()
    }

    private fun reInitOnCompileContextUpdate() {
        deployFileManager.updateModuleInfos(compileContextManager.compileContext.modules)
        juggCompilerHelper.juggCompiler = JuggCompiler(compileContextManager.compileContext, this)
        fileChangesHandler.init(compileContextManager.compileContext)
    }

    private fun initCompile(
        compileContextInfo: CompileContextInfo,
        deployedFiles: List<CompileOutput>,
        isNeedReloadProjectInfo: Boolean,
        isNeedWarmUpDeploy: Boolean,
        startCompileTime: Long?,
    ) {
        logger.info("Init compile... isNeedReloadProjectInfo=$isNeedReloadProjectInfo")

        deployStateManager.isBuildFileChanged = false

        val costTime = measureTimeMillis {
            compileContextManager.initFullBuildInfo(compileContextInfo, isNeedReloadProjectInfo)
            deployFileManager.init(compileContextInfo.apkInfos, deployedFiles, startCompileTime)
            reInitOnCompileContextUpdate()
        }
        logger.debug("Init compile cost ${costTime}ms")

        fileChangesDetector.startListen(object: FileChangesListener {
            override fun onFileChanges(changedFiles: List<File>) {
                processFileChanged(changedFiles)
            }
        })

        logger.info("Jugg init complete, start listening file changes.")

        warmUpCompile(isNeedWarmUpDeploy)
    }

    private fun warmUpCompile(isNeedWarmUpDeploy: Boolean) {
        runTaskSafe("Warm Up Compile", {
            doWarmUpCompile(isNeedWarmUpDeploy)
        })
    }

    private fun doWarmUpCompile(isNeedWarmUpDeploy: Boolean) {
        runBlocking {
            launch(Dispatchers.IO) {
                juggCompilerHelper.warmUp()
            }
            if (isNeedWarmUpDeploy) {
                launch(Dispatchers.IO) {
                    val devices = deployTargetManager.getDevices()
                    devices.forEachIndexed { index, device ->
                        val isLastDevice = index == devices.size - 1
                        val result = juggDeployerHelper.deploy(device, isLastDevice, processHandler = null, isInstall = false, isWarmUp = true, retryReason = JuggDeployerHelper.DO_NOT_RETRY)
                        juggServer.report {
                            action = "warm_up_deploy"
                            isSuccess = result.isSuccess
                            costTime = result.costTime
                            detail = result.failedReason
                        }
                    }
                }
            } else {
                logger.debug("no need warm up deploy, skip.")
            }
        }
    }

    private var currentIndicator: ProgressIndicator? = null
    private var retryGapMill = 5_000L

    private fun runTaskSafe(jobName: String, action: Runnable, isNeedShowIndicator: Boolean = true) {
        object : Task.Backgroundable(project, jobName, false) {
            override fun run(indicator: ProgressIndicator) {
                synchronized(this@JuggManager) {
                    val reportEventData = ReportEventData()
                    val startTime = System.currentTimeMillis()

                    try {
                        logger.debug("job <$jobName> start")
                        deployStateManager.isInitializingIncrementalCompile = true
                        if (isNeedShowIndicator) {
                            indicator.text = "Jugg: $jobName..."
                            indicator.isIndeterminate = true
                            currentIndicator = indicator
                        }
                        action.run()
                        val costTime = System.currentTimeMillis() - startTime
                        logger.debug("job <$jobName> finished, cost ${costTime}ms")
                    } catch (e: Throwable) {
                        logger.error("job <$jobName> failed", e)
                        reportEventData.detail = e.message ?: e.cause?.message ?: ""
                        reportEventData.isSuccess = false
                    } finally {
                        deployStateManager.isInitializingIncrementalCompile = false
                        if (isNeedShowIndicator) {
                            indicator.stop()
                            currentIndicator = null
                        }
                    }

                    reportEventData.action = jobName
                    reportEventData.costTime = System.currentTimeMillis() - startTime
                    juggServer.report(reportEventData)

                    if (!reportEventData.isSuccess) {
                        if (jobName == "Init project info") {
                            // compatible with com.intellij.serviceContainer.AlreadyDisposedException: Already disposed: Module: 'xxx' (disposed)
                            logger.debug("retry $jobName after ${retryGapMill}ms") // maybe
                            launch {
                                delay(retryGapMill)
                                retryGapMill *= 2
                                runTaskSafe(jobName, action, isNeedShowIndicator)
                            }
                        }
                    } else {
                        retryGapMill = 5_000L
                    }
                }
            }
        }.setCancelText("Jugg: Stopping $jobName...").queue()
    }

    override fun dispose() {
        logger.debug("project ${pathManager.projectDir} dispose")
        coroutineScope.cancel()
    }

}