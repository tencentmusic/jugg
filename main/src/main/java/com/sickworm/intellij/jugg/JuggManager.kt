package com.sickworm.intellij.jugg

import com.android.ddmlib.IDevice
import com.intellij.execution.RunManager
import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessOutputType
import com.intellij.openapi.Disposable
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.sickworm.intellij.jugg.compiler.*
import com.sickworm.intellij.jugg.deploy.*
import com.sickworm.intellij.jugg.deploy.run.AsDeployerCompat
import com.sickworm.intellij.jugg.deploy.run.JuggDeployerHelper
import com.sickworm.intellij.jugg.deploy.run.JuggDeploymentService
import com.sickworm.intellij.jugg.ide.*
import com.sickworm.intellij.jugg.ide.ChangedFileInfo
import com.sickworm.intellij.jugg.ide.JuggStateListener
import com.sickworm.intellij.jugg.server.ReportEventData
import com.sickworm.intellij.jugg.logger.JuggLogger
import com.sickworm.intellij.jugg.server.JuggServer
import com.sickworm.intellij.jugg.project.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.jetbrains.annotations.TestOnly
import org.jetbrains.kotlin.utils.addToStdlib.measureTimeMillisWithResult
import java.io.File
import kotlin.system.measureTimeMillis


class JuggManager @TestOnly constructor(
    val project: Project,
    val pathManager: JuggPathManager,
    private val logger: Logger = JuggLogger.getInstance(project, "JuggManager"),
    private val juggServer: JuggServer = JuggServer(project, pathManager),
    private val fileChangesHandler: IFileChangesHandler = FileChangesHandler(project, pathManager.juggRootDir),
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
    ),
    private val compileContextManager: CompileContextManager = CompileContextManager(project, pathManager, deployFileManager),
    private val deployTargetManager: IDeployTargetManager = DeployTargetManager(project),
    var deployStateListener: JuggStateListener = JuggStateListener.emptyImpl,
    private val deployStateManager: DeployStateManager = DeployStateManager(project, deployTargetManager, deployHistoryManager),
    private val juggDeployerHelper: JuggDeployerHelper = JuggDeployerHelper(project, deployTargetManager, deployFileManager, deployHistoryManager, deployStateManager, juggServer, { deployStateListener }),
    private val juggCompilerHelper: JuggCompilerHelper = JuggCompilerHelper(project, juggServer, deployTargetManager, deployStateManager, deployFileManager, deployHistoryManager, compileContextManager, fileChangesHandler, { deployStateListener }),
): Disposable {

    constructor(
        project2: Project,
        pathManager: JuggPathManager,
    ): this(project = project2, pathManager)

    fun init() {
        Disposer.register(this, juggCompilerHelper)
        runTaskSafe("Init Jugg", {
            JuggRunningTask.resetHasRun(project)
            logger.info("Init IDE API...")
            AsDeployerCompat.init(JuggLogger.getInstance(project, "AsDeployerCompat"))
            logger.info("Create run configuration...")
            createDefaultRunConfigurationIfNoneExist()
            logger.info("Start jugg finished.")

            // init async
            JuggDeploymentService.postWithLock {
                val costTime = measureTimeMillis {
                    preInit()
                }
                logger.debug("JuggDeploymentService.preInit cost ${costTime}ms")
            }

            logger.debug("Checking updates...")
            juggServer.checkUpdate()
        })
    }

    fun initProjectInfo(isNeedReloadProjectInfo: Boolean) {
        runTaskSafe("Init Project Info", {
            if (isNeedReloadProjectInfo) {
                // gradle sync finished, reset hasRun flag to avoid "No file changes" fallback
                JuggRunningTask.resetHasRun(project)
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

    private fun createDefaultRunConfigurationIfNoneExist() {
        val defaultName = "jugg:app"
        val currentList = RunManager.getInstance(project).getConfigurationSettingsList(JuggConfigurationType::class.java)
        logger.debug("JuggConfigurationType currentList: ${currentList.map { it.name} }")
        if (currentList.isNotEmpty()) {
            return
        }
        val factory: ConfigurationFactory = JuggConfigurationType.getInstance().configurationFactories[0]
        val settings = RunManager.getInstance(project).createConfiguration(defaultName, factory)
        settings.isActivateToolWindowBeforeRun = false
        RunManager.getInstance(project).addConfiguration(settings)
        RunManager.getInstance(project).selectedConfiguration = settings
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
        logger.debug("Create running task: $options")

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
            processHandler, compileTask, deployTask, initIncrementalCompileTask)
        currentTask = task
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

        if (isRemoteCompile) {
            logger.info("Fetching remote classpath...")
            val (costTime2, classpathRootDir) = measureTimeMillisWithResult {
                val originText = currentIndicator?.text
                currentIndicator?.text = "Jugg: Fetching remote classpath..."
                val result = juggCompilerHelper.fetchClasspathResult(true, moduleBuildPathInfos)
                currentIndicator?.text = originText
                return@measureTimeMillisWithResult result
            }
            logger.debug("fetchClasspathResult cost ${costTime2}ms")
            logger.debug("fetchClasspathResult classpathRootDir = $classpathRootDir")
            if (classpathRootDir == null || !classpathRootDir.exists()) {
                logger.warn("Fetch remote classpath failed, please check log for details.")
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
                allModules,
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
                }
            }
        }.setCancelText("Jugg: Stopping $jobName...").queue()
    }

    override fun dispose() {
        logger.debug("project ${pathManager.projectDir} dispose")
    }

}