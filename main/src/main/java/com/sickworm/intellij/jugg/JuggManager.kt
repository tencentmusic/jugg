package com.sickworm.intellij.jugg

import com.intellij.execution.RunManager
import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.openapi.Disposable
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.sickworm.intellij.jugg.compiler.*
import com.sickworm.intellij.jugg.deploy.*
import com.sickworm.intellij.jugg.deploy.run.AsDeployerCompat
import com.sickworm.intellij.jugg.deploy.run.JuggDeployerHelper
import com.sickworm.intellij.jugg.ide.*
import com.sickworm.intellij.jugg.ide.toolWindow.ChangedFileInfo
import com.sickworm.intellij.jugg.ide.toolWindow.JuggStateListener
import com.sickworm.intellij.jugg.logger.JuggLogger
import com.sickworm.intellij.jugg.project.*
import org.jetbrains.annotations.TestOnly
import org.jetbrains.kotlin.utils.addToStdlib.measureTimeMillisWithResult
import java.io.File
import kotlin.system.measureTimeMillis


class JuggManager @TestOnly constructor(
    private val project: Project,
    val pathManager: JuggPathManager,
    private val logger: Logger = JuggLogger.getInstance(project, "JuggManager"),
    private val compileContextManager: CompileContextManager = CompileContextManager(project, pathManager),
    private val fileChangesHandler: IFileChangesHandler = FileChangesHandler(project),
    private val fileChangesDetector: IFileChangesDetector = FileChangesDetector(project),
    private val deployHistoryManager: IDeployHistoryManager = DeployHistoryManager(
        pathManager.projectDir,
        pathManager.historyDir,
        JuggLogger.getInstance(project, "DeployHistoryManager")
    ),
    private val deployFileManager: DeployFileManager = DeployFileManager(
        JuggLogger.getInstance(project, "DeployDataManager")
    ),
    private val deployTargetManager: IDeployTargetManager = DeployTargetManager(project),
    var deployStateListener: JuggStateListener = JuggStateListener.emptyImpl,
    private val deployStateManager: DeployStateManager = DeployStateManager(project, deployHistoryManager),
    private val juggDeployerHelper: JuggDeployerHelper = JuggDeployerHelper(project, deployTargetManager, deployFileManager, deployHistoryManager, deployStateManager, { deployStateListener }),
    private val juggCompilerHelper: JuggCompilerHelper = JuggCompilerHelper(project, deployTargetManager, deployStateManager, deployFileManager, compileContextManager, { deployStateListener }),
): Disposable {

    constructor(
        project2: Project,
        pathManager: JuggPathManager,
    ): this(project = project2, pathManager)

    fun init() {
        Disposer.register(this, juggCompilerHelper)
        runTaskSafe("Init Project", ::initProject)
    }

    private fun initProject() {
        try {
            logger.info("Reading project structure...")
            AsDeployerCompat.init(JuggLogger.getInstance(project, "AsDeployerCompat"))
            compileContextManager.initProjectInfo()
            logger.debug("Init deploy history...")
            recoverDeployContext()
            logger.debug("Create run configuration...")
            createRunConfiguration()
            logger.debug("Start jugg finished.")
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
        }

        // step 1: recover compile context
        initCompile(deployContextRecoverInfo.compileContextInfo)
        // step 2: recover deploy files
        logger.debug("Start recover deploy history...")
        deployTargetManager.setApks(deployContextRecoverInfo.compileContextInfo.apkInfos)
        deployFileManager.addDeployFiles(deployContextRecoverInfo.deployedFiles)
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

        logger.debug("deploy state changed: $oldDeployState -> $deployState")
        deployStateListener.onDeployStateUpdate(deployState)

        return deployState
    }

    private fun processFileChanged(changedFiles: List<File>) {
        if (fileChangesHandler.checkBuildGradleChanged(changedFiles)) {
            deployStateManager.isBuildGradleChanged = true
            logger.warn("Build.gradle changed, need rebuild")
            return
        }

        val realChangedFiles = fileChangesHandler.filter(changedFiles)
        if (realChangedFiles.isEmpty()) {
            return
        }

        if (realChangedFiles.find { it.type == CompileFile.Type.Resource } != null) {
            // FIXME inc aapt not stable, close for now
            logger.warn("Resource changed, need rebuild")
            deployStateManager.isResourceFileChanged = true
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
        val fetchClasspathTask = task@{
            // do it async
            runTaskSafe("Init Incremental Compile", ::initIncrementalCompileAfterFullBuild)
        }
        return JuggRunningTask(project, processHandler, compileTask, deployTask, fetchClasspathTask)
    }

    @TestOnly
    fun compileChanges() {
        val isSuccess = juggCompilerHelper.incrementalCompile()
        if (isSuccess && JuggSettings.deployOnSave) {
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
    fun initIncrementalCompileAfterFullBuild() {
        logger.debug("Init compile after full build")
        val (costTime, compileContextInfo) = measureTimeMillisWithResult {
            val apkInfos = deployTargetManager.getApks()
            if (apkInfos.isEmpty()) {
                logger.warn("Init compile failed for no apk found")
                return
            }
            deployHistoryManager.reInitAfterFullCompiled(
                apkInfos,
                compileContextManager.getAllModulesByModuleManager()
            )
        }
        logger.debug("reInitAfterFullCompiled cost ${costTime}ms")

        initCompile(compileContextInfo)
    }

    private fun initCompile(
        compileContextInfo: CompileContextInfo,
    ) {
        logger.info("Init compile...")

        deployStateManager.isBuildGradleChanged = false
        deployStateManager.isResourceFileChanged = false
        deployFileManager.reset()

        val costTime = measureTimeMillis {
            deployFileManager.initAndResetAfterFullCompile(compileContextInfo.apkInfos)
            compileContextManager.initFullBuildInfo(compileContextInfo)
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

        warnUpCompiler()
    }

    private fun warnUpCompiler() {
        logger.debug("going to warn up compiler")
        runTaskSafe("Warn Up Compiler", ::doWarnUpCompiler)
    }

    private fun doWarnUpCompiler() {
        juggCompilerHelper.warnUp()
    }

    private fun runTaskSafe(jobName: String, action: Runnable, isNeedShowIndicator: Boolean = true) {
        val task = object : Task.Backgroundable(project, jobName) {
            override fun run(indicator: ProgressIndicator) {
                synchronized(this@JuggManager) {
                    try {
                        val startTime = System.currentTimeMillis()
                        logger.debug("job <$jobName> start")
                        if (isNeedShowIndicator) {
                            indicator.text = "Jugg-$jobName..."
                            indicator.isIndeterminate = true
                        }
                        action.run()
                        val costTime = System.currentTimeMillis() - startTime
                        logger.debug("job <$jobName> finished, cost ${costTime}ms")
                    } catch (e: Throwable) {
                        logger.error("job <$jobName> failed", e)
                    } finally {
                        if (isNeedShowIndicator) {
                            indicator.stop()
                        }
                    }
                }
            }
        }
        ProgressManager.getInstance().run(task)
    }

    override fun dispose() {
        logger.debug("project ${pathManager.projectDir} dispose")
    }

}