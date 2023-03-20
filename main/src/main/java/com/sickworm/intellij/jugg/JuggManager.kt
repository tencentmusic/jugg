package com.sickworm.intellij.jugg

import com.intellij.execution.DefaultExecutionResult
import com.intellij.execution.ExecutionResult
import com.intellij.execution.RunManager
import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.openapi.Disposable
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.sickworm.intellij.jugg.compiler.*
import com.sickworm.intellij.jugg.deploy.*
import com.sickworm.intellij.jugg.deploy.run.AsDeployerCompat
import com.sickworm.intellij.jugg.deploy.run.JuggDeployData
import com.sickworm.intellij.jugg.deploy.run.JuggDeployerHelper
import com.sickworm.intellij.jugg.ide.JuggConfigurationType
import com.sickworm.intellij.jugg.ide.JuggSettings
import com.sickworm.intellij.jugg.ide.JuggGradleCompileOptions
import com.sickworm.intellij.jugg.ide.toolWindow.ChangedFileInfo
import com.sickworm.intellij.jugg.ide.toolWindow.JuggStateListener
import com.sickworm.intellij.jugg.logger.JuggLogger
import com.sickworm.intellij.jugg.project.*
import org.jetbrains.annotations.TestOnly
import org.jetbrains.kotlin.utils.addToStdlib.measureTimeMillisWithResult
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.system.measureTimeMillis


class JuggManager @TestOnly constructor(
    private val project: Project,
    val pathManager: JuggPathManager,
    private val logger: Logger = JuggLogger.getInstance(project, "JuggManager"),
    private val compileThread: ExecutorService = Executors.newSingleThreadExecutor(),
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
    private val deployStateManager: DeployStateManager = DeployStateManager(project, deployHistoryManager),
    private val juggDeployerHelper: JuggDeployerHelper = JuggDeployerHelper(project, deployTargetManager),
): Disposable {

    constructor(
        project2: Project,
        pathManager: JuggPathManager,
    ): this(project = project2, pathManager)

    private var compiler: JuggCompiler? = null

    var deployStateListener: JuggStateListener = JuggStateListener.emptyImpl

    fun init() {
        Disposer.register(this, juggDeployerHelper)
        compileThread.submitSafe("InitProject", ::initProject)
    }

    private fun initProject() {
        try {
            logger.info("Reading project structure...")
            AsDeployerCompat.init(logger)
            compileContextManager.initProjectInfo()
            logger.debug("Init deploy history...")
            recoverDeployContext()
            logger.debug("Create run configuration...")
            createRunConfiguration()
            logger.debug("Start jugg finished.")
        } finally {
            onActionUpdate()
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
        deployTargetManager.setApksFromRecover(deployContextRecoverInfo.compileContextInfo.apkInfos)
        deployFileManager.addDeployFiles(deployContextRecoverInfo.deployedFiles)
        // step 3: recover changed files
        processFileChanged(deployContextRecoverInfo.changedFiles)
        // step 4: update deploy state
        onActionUpdate()

        logger.debug("Deploy history recover successfully, no need full compile.")
    }

    fun onActionUpdate(): JuggDeployState {
        val oldDeployState = deployStateManager.deployState
        val deployState = deployStateManager.onActionUpdate()
        if (deployState == oldDeployState) {
            // won't do anything if deploy state is not changed
            return deployState
        }

        logger.debug("deploy state changed: $oldDeployState -> $deployState")
        deployStateListener.onDeployStateUpdate(deployState)

        if (oldDeployState.isGradleBuilding && deployState.isReadyRunFullBuild) {
            logger.info("Gradle build finished.")
            synchronized(buildFinishedLock) {
                buildFinishedLock.notify()
                compileThread.submitSafe("InitCompile", ::initCompileAfterFullBuild)
            }
        }

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
            compileThread.submitSafe("Compile", ::compileChanges)
        }
    }

    @TestOnly
    fun compileChanges() {
        if (!deployStateManager.deployState.isReadyIncCompile) {
            logger.info("Not ready to compile changes. Current deploy state: ${deployStateManager.deployState}.")
            return
        }

        val compiler = compiler?: run {
            throw JuggInternalException.compilerNotInit()
        }

        // read all uncompiled files
        val uncompiledFiles = deployFileManager.getUncompiledFiles()
        if (uncompiledFiles.all { it.hasCompiledOnce }) {
            logger.debug("All files has compiled at least once, skip compile")
            return
        }

        val compileFiles = uncompiledFiles.map {
            CompileFile(it.type, it.file, it.baseDir, it.module, dependencyPaths = compileContextManager.dependencies)
        }

        deployStateListener.onFileStatesUpdate(compileFiles.map {
            ChangedFileInfo(it.file, ChangedFileInfo.State.COMPILING)
        })

        // do compile
        logger.info("Compile files: $compileFiles")
        val compileResult = compiler.compile(CompileTask(compileFiles, compileContextManager.stagingDir))

        // update file status
        val successFiles = compileResult.details.filter { it.isSuccess }.map { it.get() }
        val failedFiles = compileResult.details.filter { !it.isSuccess }.map { it.getFailure().file }
        deployFileManager.updateUncompiledFiles(successFiles, failedFiles)
        deployFileManager.addDeployFiles(compileResult.outputs)

        // notify ui state
        val successStates = compileResult.successFiles.map {
            ChangedFileInfo(it.file.file, ChangedFileInfo.State.COMPILED)
        }
        val failedStates = compileResult.failedFiles.map {
            ChangedFileInfo(it.file.file, ChangedFileInfo.State.COMPILE_FAILED)
        }
        logger.info("Compile finished, success: ${compileResult.successFiles.size}, " +
                "failure: ${compileResult.failedFiles.size}.")
        deployStateListener.onFileStatesUpdate(successStates + failedStates)

        if (JuggSettings.deployOnSave) {
            deployAsync(false)
        }
    }

    @Deprecated("use deploy(JuggRunConfigurationSettings) instead", ReplaceWith("deploy(JuggRunConfigurationSettings)"))
    fun deployAsync(isUserClick: Boolean) {
        if (!deployStateManager.deployState.isReadyRunFullBuild) {
            if (isUserClick) {
                logger.warn("Deployment is not ready, skip deploy.")
            } else {
                logger.info("Deployment is not ready, skip deploy.")
            }
            return
        }
        compileThread.submitSafe("Deploy", ::deploy)
    }

    @TestOnly
    @Deprecated("use deploy(JuggRunConfigurationSettings) instead", ReplaceWith("deploy(JuggRunConfigurationSettings)"))
    fun deploy() {
        deploy(null)
    }

    // TODO remove
    fun deployFull(settings: JuggGradleCompileOptions): ExecutionResult {
        return juggDeployerHelper.runFullBuildAndLaunch(settings)
    }

    fun deploy(settings: JuggGradleCompileOptions?): ExecutionResult {
        if (!checkDeviceAvailable()) {
            logger.warn("No available device to run, please connect device first")
            return DefaultExecutionResult()
        }

        logger.info("Start deploy, deploy state: ${deployStateManager.deployState}.")

        var executionResult: ExecutionResult = DefaultExecutionResult()
        when {
            deployStateManager.deployState.isReadyDeploy -> {
                val deployData = deployFileManager.getDeployData()
                if (deployData.apks.isEmpty()) {
                    logger.error("Deploy failed, can not find apks.")
                    return DefaultExecutionResult()
                }
                if (deployData.isEmpty) {
                    logger.info("Deploy finished with no data to deploy.")
                    return DefaultExecutionResult()
                }

                logger.info("Deploy data:\n$deployData")

                juggDeployerHelper.runTask(deployData)
                deployStateListener.onDeployed(
                    false,
                    deployFileManager.getCompiledFiles().map { it.file },
                )
                updateInfoAfterIncDeploy(deployData)
            }
            deployStateManager.deployState.isReadyIncCompile -> {
                recoverDeployState()
            }
            deployStateManager.deployState.isReadyRunFullBuild -> {
                logger.info("Build, install and run apk...")
                executionResult = deployTargetManager.runFullBuildAndLaunch()
                // TODO remove after refactor test
                if (settings == null) {
                    waitingForBuildFinished()
                }
                logger.info("Build, install and run apk finished.")
            }
            else -> {
                logger.warn("Not ready to deploy.")
                return DefaultExecutionResult()
            }
        }

        onActionUpdate()
        return executionResult
    }

    private fun updateInfoAfterIncDeploy(deployData: JuggDeployData) {
        val compiledFiles = deployFileManager.getCompiledFiles()
        val deployedFiles = deployFileManager.getStagingFiles()
        deployHistoryManager.updateHistoryOnAfterDeployed(compiledFiles, deployedFiles)
        deployFileManager.commit(deployData)
    }

    /**
     * Redeploy apk and compiled files.
     * Will check deploy state on device first. If matched, won't reinstall apk and redeploy compiled files.
     */
    private fun recoverDeployState() {
        logger.info("Recover deploy state from history.")

        // dry deploy first, if success, no need to reinstall and recover
        if (tryDryDeploy()) {
            logger.info("Deploy state matched, no need reinstall app.")
            deployAsync(false)
            return
        }
        logger.info("Need reinstall app.")

        // recover deploy state for device
        val deployData = deployFileManager.getDeployData()
        juggDeployerHelper.runTask(deployData, true)
        val isDeviceDeployable = waitingForDeployable()
        if (!isDeviceDeployable) {
            logger.warn("Recovery failed for app not launched.")
            return
        }

        logger.info("Device online, start recover and deploy.")
        deployAsync(false)
    }

    private fun tryDryDeploy(): Boolean {
        logger.info("Start app directly.")
        if (!deployTargetManager.restartApp()) {
            logger.debug("Try start app failed")
            return false
        }
        val isDeviceDeployable = waitingForDeployable()
        if (!isDeviceDeployable) {
            logger.warn("Dry deploy failed for app not launched.")
            return false
        }

        logger.info("Device online, try dry deploy.")
        return try {
            val deployData = deployFileManager.getDeployData()
            val dryDeployData = JuggDeployData(deployData.apks, emptyList(), emptyList(), emptyList(), emptyList())
            juggDeployerHelper.runTask(dryDeployData)
            true
        } catch (e: Exception) {
            logger.debug("Dry deploy failed, reason: $e")
            false
        }
    }

    private fun checkDeviceAvailable(): Boolean {
        return try {
            deployTargetManager.getDevice()
            true
        } catch (e: Exception) {
            false
        }
    }

    private val buildFinishedLock = Object()
    private fun waitingForBuildFinished() {
        synchronized(buildFinishedLock) {
            buildFinishedLock.wait()
        }
    }

    private fun waitingForDeployable(): Boolean {
        val maxWaitTimeSecond = 5
        var waitedTimeSecond = 0
        val waitGapMillSecond = 1
        while (waitedTimeSecond < maxWaitTimeSecond) {
            Thread.sleep(waitGapMillSecond * 1000L)
            waitedTimeSecond += waitGapMillSecond
            logger.info("($waitedTimeSecond/$maxWaitTimeSecond)waiting app launched...")
            if (deployStateManager.deployState.isReadyDeploy) {
                return true
            }
        }

        logger.warn("App not launched, please check the app is started and adb is not occupied by other process")
        return false
    }

    @TestOnly
    fun initCompileAfterFullBuild() {
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
        deployStateListener.onDeployed(true, emptyList())
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
            compiler = JuggCompiler(compileContextManager.compileContext)
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
        compileThread.submitSafe("WarnUpCompiler", ::doWarnUpCompiler)
    }

    private fun doWarnUpCompiler() {
        compiler?.warnUp()
    }

    private fun ExecutorService.submitSafe(jobName: String, task: Runnable) {
        submit {
            try {
                val startTime = System.currentTimeMillis()
                logger.debug("job <$jobName> start")
                task.run()
                val costTime = System.currentTimeMillis() - startTime
                logger.debug("job <$jobName> finished, cost ${costTime}ms")
            } catch (e: Throwable) {
                logger.error("job <$jobName> failed, try clicking reset button if error still occurred", e)
            }
        }
    }

    override fun dispose() {
        logger.debug("project ${pathManager.projectDir} dispose")
    }
}