package com.sickworm.intellij.jugg

import com.intellij.execution.RunManager
import com.intellij.execution.configurations.ConfigurationFactory
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
import com.sickworm.intellij.jugg.ide.*
import com.sickworm.intellij.jugg.ide.ChangedFileInfo
import com.sickworm.intellij.jugg.ide.JuggStateListener
import com.sickworm.intellij.jugg.logger.ReportEventData
import com.sickworm.intellij.jugg.logger.JuggLogger
import com.sickworm.intellij.jugg.logger.JuggReporter
import com.sickworm.intellij.jugg.project.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.jetbrains.annotations.TestOnly
import org.jetbrains.kotlin.utils.addToStdlib.measureTimeMillisWithResult
import java.io.File
import kotlin.system.measureTimeMillis


class JuggManager @TestOnly constructor(
    private val project: Project,
    val pathManager: JuggPathManager,
    private val logger: Logger = JuggLogger.getInstance(project, "JuggManager"),
    private val juggReporter: JuggReporter = JuggReporter(project),
    private val compileContextManager: CompileContextManager = CompileContextManager(project, pathManager),
    private val fileChangesHandler: IFileChangesHandler = FileChangesHandler(project),
    private val fileChangesDetector: IFileChangesDetector = FileChangesDetector(project, pathManager.projectDir),
    private val deployHistoryManager: IDeployHistoryManager = DeployHistoryManager(
        pathManager.projectDir,
        pathManager.historyDir,
        JuggLogger.getInstance(project, "DeployHistoryManager")
    ),
    private val deployFileManager: DeployFileManager = DeployFileManager(
        JuggLogger.getInstance(project, "DeployDataManager"),
        pathManager.tmpDir,
    ),
    private val deployTargetManager: IDeployTargetManager = DeployTargetManager(project),
    var deployStateListener: JuggStateListener = JuggStateListener.emptyImpl,
    private val deployStateManager: DeployStateManager = DeployStateManager(project, deployHistoryManager),
    private val juggDeployerHelper: JuggDeployerHelper = JuggDeployerHelper(project, deployTargetManager, deployFileManager, deployHistoryManager, deployStateManager, { deployStateListener }),
    private val juggCompilerHelper: JuggCompilerHelper = JuggCompilerHelper(project, juggReporter, deployTargetManager, deployStateManager, deployFileManager, compileContextManager, { deployStateListener }),
): Disposable {

    constructor(
        project2: Project,
        pathManager: JuggPathManager,
    ): this(project = project2, pathManager)

    fun init() {
        Disposer.register(this, juggCompilerHelper)
        runTaskSafe("Init Project", ::initProject)
    }

    fun refreshProjectInfo() {
        runTaskSafe("Refresh Project Info", {
            compileContextManager.refreshCompileContext()
            juggCompilerHelper.juggCompiler = JuggCompiler(compileContextManager.compileContext)
            warmUpCompile(false)
        })
    }

    private fun initProject() {
        try {
            logger.info("Create run configuration...")
            createRunConfiguration()
            logger.info("Init IDE API...")
            AsDeployerCompat.init(JuggLogger.getInstance(project, "AsDeployerCompat"))
            logger.info("Init deploy history...")
            recoverDeployContext()
            logger.info("Start jugg finished.")
        } finally {
            updateDeployState()
        }
    }

    private fun createRunConfiguration() {
        val defaultName = "jugg:app"
        val currentList = RunManager.getInstance(project).getConfigurationSettingsList(JuggConfigurationType::class.java)
        if (currentList.any { it.name == defaultName }) {
            return
        }
        val factory: ConfigurationFactory = JuggConfigurationType.getInstance().configurationFactories[0]
        val settings = RunManager.getInstance(project).createConfiguration(defaultName, factory)
        settings.isActivateToolWindowBeforeRun = false
        RunManager.getInstance(project).addConfiguration(settings)
        RunManager.getInstance(project).selectedConfiguration = settings
    }

    private fun recoverDeployContext() {
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
        initCompile(deployContextRecoverInfo.compileContextInfo, deployContextRecoverInfo.deployedFiles)
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
        if (fileChangesHandler.checkBuildGradleChanged(changedFiles)) {
            deployStateManager.isBuildGradleChanged = true
            logger.warn("Build.gradle changed, need rebuild")
            return
        }

        if (fileChangesHandler.checkAndroidManifestChanged(changedFiles)) {
            deployStateManager.isManifestChanged = true
            logger.warn("AndroidManifest.xml changed, need rebuild")
            return
        }

        val deletedFiles = changedFiles.filter { !it.exists() }
        if (deletedFiles.isNotEmpty()) {
            deletedFiles.forEach {
                logger.debug("File deleted: ${it.absolutePath}")
            }
            deployFileManager.removeChangedFile(deletedFiles)
        }

        val realChangedFiles = fileChangesHandler.filter(changedFiles)
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
    @Volatile
    private var onFinishListener: (() -> Unit)? = null

    fun cancelCurrentTask(onFinish: () -> Unit) {
        val currentTask = currentTask
        if (currentTask == null) {
            onFinish()
            return
        }
        if (!currentTask.isRunning) {
            onFinish()
            return
        }
        logger.info("cancelCurrentTask $currentTask")
        currentTask.cancel()
        onFinishListener = onFinish
    }

    fun createRunningTask(
        options: JuggGradleCompileOptions,
        processHandler: SimpleProcessHandler,
    ): JuggRunningTask {
        val compileTask= task@{ indicator: ProgressIndicator, isForceInstall: Boolean ->
            return@task juggCompilerHelper.compile(options, processHandler, indicator, isForceInstall)
        }
        val deployTask = task@{ isInstall: Boolean ->
            return@task juggDeployerHelper.deploy(isInstall)
        }
        val initIncrementalCompileTask = task@{
            // do it async
            fun action() {
                initIncrementalCompileAfterFullBuild(options.isRemoteCompile)
            }
            runTaskSafe("Init Incremental Compile", ::action)
        }
        return JuggRunningTask(project, juggReporter, processHandler, compileTask, deployTask, initIncrementalCompileTask)
    }

    @TestOnly
    fun compileChanges() {
        val result = juggCompilerHelper.incrementalCompile()
        if (result.isSuccess && JuggSettings.deployOnSave) {
            deployAsync(false)
        }
    }

    @Deprecated("use deploy(JuggRunConfigurationSettings) instead", ReplaceWith("deploy(JuggRunConfigurationSettings)"))
    fun deployAsync(isUserClick: Boolean) {
        TODO() // remove
    }

    @TestOnly
    @Deprecated("use deploy(JuggRunConfigurationSettings) instead", ReplaceWith("deploy(JuggRunConfigurationSettings)"))
    fun deploy() {
        TODO() // remove
    }

    @TestOnly
    fun initIncrementalCompileAfterFullBuild(isRemoteCompile: Boolean = false) {
        JuggLogger.resetLatestCompileLog(project)
        juggReporter.afterFullCompile()

        logger.debug("Init compile after full build, isRemoteCompile=$isRemoteCompile")

        var allModules = compileContextManager.getAllModulesByModuleManager()
        val moduleBuildPathInfos = allModules.map { it.value.buildPathInfo }

        if (isRemoteCompile) {
            logger.info("Fetching remote classpath...")
            val (costTime2, classpathRootDir) = measureTimeMillisWithResult {
                juggCompilerHelper.fetchClasspathResult(true, moduleBuildPathInfos)
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
                    ))
                }
                .associateBy { it.name }
        }

        val (costTime: Long, compileContextInfo: CompileContextInfo) = measureTimeMillisWithResult {
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


        initCompile(compileContextInfo, emptyList())
    }

    private fun initCompile(
        compileContextInfo: CompileContextInfo,
        deployedFiles: List<CompileOutput>
    ) {
        logger.info("Init compile...")

        deployStateManager.isBuildGradleChanged = false
        deployStateManager.isManifestChanged = false

        val costTime = measureTimeMillis {
            compileContextManager.initFullBuildInfo(compileContextInfo)
            deployFileManager.init(compileContextInfo.apkInfos, deployedFiles)
            juggCompilerHelper.juggCompiler = JuggCompiler(compileContextManager.compileContext)
        }
        logger.debug("Init compile cost ${costTime}ms")

        fileChangesHandler.init(compileContextManager.compileContext)
        fileChangesDetector.startListen(object: FileChangesListener {
            override fun onFileChanges(changedFiles: List<File>) {
                processFileChanged(changedFiles)
            }
        })

        logger.info("Jugg init complete, start listening file changes.")

        val isNeedWarmUpDeploy = deployedFiles.all { it.type != CompileOutput.Type.Overlay }
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
                    val result = juggDeployerHelper.deploy(isInstall = false, isWarmUp = true, canRetry = false)
                    juggReporter.report {
                        action = "warm_up_deploy"
                        isSuccess = result.isSuccess
                        costTime = result.costTime
                        detail = result.failedReason
                    }
                }
            } else {
                logger.debug("already warm up deploy, skip.")
            }
        }
    }

    private fun runTaskSafe(jobName: String, action: Runnable, isNeedShowIndicator: Boolean = true) {
        object : Task.Backgroundable(project, jobName, false) {
            override fun run(indicator: ProgressIndicator) {
                synchronized(this@JuggManager) {
                    val reportEventData = ReportEventData()
                    val startTime = System.currentTimeMillis()

                    try {
                        logger.debug("job <$jobName> start")
                        if (isNeedShowIndicator) {
                            indicator.text = "Jugg: $jobName..."
                            indicator.isIndeterminate = true
                        }
                        action.run()
                        val costTime = System.currentTimeMillis() - startTime
                        logger.debug("job <$jobName> finished, cost ${costTime}ms")
                    } catch (e: Throwable) {
                        logger.error("job <$jobName> failed", e)
                        reportEventData.detail = e.message ?: e.cause?.message ?: ""
                        reportEventData.isSuccess = false
                    } finally {
                        if (isNeedShowIndicator) {
                            indicator.stop()
                        }
                    }

                    reportEventData.action = jobName
                    reportEventData.costTime = System.currentTimeMillis() - startTime
                    juggReporter.report(reportEventData)
                }
            }
        }.setCancelText("Jugg: Stopping $jobName...").queue();
    }

    override fun dispose() {
        logger.debug("project ${pathManager.projectDir} dispose")
    }

}