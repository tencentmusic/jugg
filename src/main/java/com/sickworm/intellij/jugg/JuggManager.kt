package com.sickworm.intellij.jugg

import com.android.tools.deployer.JuggDeployData
import com.android.tools.deployer.JuggDeployerHelper
import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.sickworm.intellij.jugg.compiler.*
import java.util.concurrent.Executors
import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.jugg.deploy.*
import com.sickworm.intellij.jugg.ide.JuggSettings
import com.sickworm.intellij.jugg.ide.toolWindow.ChangedFileInfo
import com.sickworm.intellij.jugg.ide.toolWindow.JuggStateListener
import com.sickworm.intellij.jugg.project.*
import org.jetbrains.annotations.TestOnly
import org.jetbrains.kotlin.utils.addToStdlib.measureTimeMillisWithResult
import java.io.File
import java.util.concurrent.ExecutorService
import kotlin.system.measureTimeMillis

class JuggManager @TestOnly constructor(
    private val project: Project,
    private val projectDir: File,
    private val deployStateListener: JuggStateListener,
    private val logger: Logger = JuggLogger.getInstance(project, "#Jugg-JuggManager"),
    private val compileThread: ExecutorService = Executors.newSingleThreadExecutor(),
    private val pathManager: JuggPathManager = JuggPathManager(projectDir),
    private val compileContextManager: CompileContextManager = CompileContextManager(project, pathManager),
    private val fileChangesHandler: IFileChangesHandler = FileChangesHandler(project),
    private val fileChangesDetector: IFileChangesDetector = FileChangesDetector(project),
    private val deployHistoryManager: IDeployHistoryManager = DeployHistoryManager(
        projectDir,
        pathManager.historyDir,
        JuggLogger.getInstance(project, "#Jugg-DeployHistoryManager")
    ),
    private val deployFileManager: DeployFileManager = DeployFileManager(
        JuggLogger.getInstance(project, "#Jugg-DeployDataManager")
    ),
    private val deployTargetManager: IDeployTargetManager = DeployTargetManager(project),
    private val deployStateManager: DeployStateManager = DeployStateManager(project, deployHistoryManager),
    private val juggDeployerHelper: JuggDeployerHelper = JuggDeployerHelper(project, deployTargetManager),
): Disposable {

    constructor(project2: Project,
                projectDir: File,
                juggDeployStateListener: JuggStateListener):
            this(project = project2, projectDir, juggDeployStateListener)

    private var compiler: JuggCompiler? = null

    fun init() {
        logger.info("Start Jugg")
        Disposer.register(project, this)
        compileThread.submitSafe("InitProject", ::initProject)
    }

    private fun initProject() {
        try {
            logger.info("Init project info")
            compileContextManager.initProjectInfo()

            logger.info("Init deploy history")
            if (!deployHistoryManager.isRecoverFeatureAvailable) {
                logger.warn("Current project don't support deploy history, need full build")
                return
            }

            val contextRecoverInfo = deployHistoryManager.tryGetContextRecoverInfoFromDb()
            if (contextRecoverInfo == null) {
                logger.warn("Deploy history not available")
                return
            }
            recoverDeployContext(contextRecoverInfo)
        } finally {
            onActionUpdate()
        }
    }

    private fun recoverDeployContext(deployContextRecoverInfo: DeployContextRecoverInfo) {
        logger.info("Start recover deploy context")
        // step 1: recover compile context
        initCompile(deployContextRecoverInfo.compileContextInfo)
        // step 2: recover deploy files
        deployFileManager.addDeployFiles(deployContextRecoverInfo.deployedFiles)
        // step 3: recover changed files
        processFileChanged(deployContextRecoverInfo.changedFiles)
        // step 4: update deploy state
        onActionUpdate()
        logger.info("Finish recover deploy context")
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
            logger.info("Not ready to compile changes. Current deploy state: ${deployStateManager.deployState}")
            return
        }

        val compiler = compiler?: run {
            throw JuggInternalException.compilerNotInit()
        }

        // read all uncompiled files
        val compileFiles = deployFileManager.getUncompiledFiles().map {
            CompileFile(it.type, it.file, it.baseDir, it.module, dependencyPaths = compileContextManager.dependencies)
        }

        // do compile
        val compileResult = compiler.compile(CompileTask(compileFiles, compileContextManager.stagingDir))

        // update file status
        val successFiles = compileResult.details.filter { it.isSuccess }.map { it.get() }
        deployFileManager.markAsCompiled(successFiles)
        deployFileManager.addDeployFiles(compileResult.outputs)

        // notify ui state
        val successStates = compileResult.successFiles.map {
            ChangedFileInfo(it.get().file, ChangedFileInfo.State.COMPILED)
        }
        val failedStates = compileResult.failedFiles.map {
            ChangedFileInfo(it.get().file, ChangedFileInfo.State.COMPILE_FAILED)
        }
        logger.info("Compile result, success: ${compileResult.successFiles.size}, failure: ${compileResult.failedFiles.size}")
        deployStateListener.onFileStatesUpdate(successStates + failedStates)

        if (JuggSettings.deployOnSave) {
            deployAsync(false)
        }
    }

    fun deployAsync(isUserClick: Boolean) {
        if (!deployStateManager.deployState.isReadyRunFullBuild) {
            if (isUserClick) {
                logger.warn("Deployment is not ready, skip deploy")
            } else {
                logger.info("Deployment is not ready, skip deploy")
            }
            return
        }
        compileThread.submitSafe("Deploy", ::deploy)
    }

    @TestOnly
    fun deploy() {
        logger.info("start deploy, deploy state: ${deployStateManager.deployState}")

        when {
            deployStateManager.deployState.isReadyDeploy -> {
                val deployData = deployFileManager.getDeployData()
                if (deployData.apks.isEmpty()) {
                    logger.error("Deploy failed, can not find apks")
                    return
                }
                if (deployData.isEmpty) {
                    logger.info("Deploy finished with no data to deploy")
                    return
                }

                logger.info("Deploy data:\n$deployData")

                juggDeployerHelper.runTask(deployData)
                updateInfoAfterIncDeploy(deployData)
            }
            // TODO install app and deploy all deployed data
//            deployStateManager.deployState.isReadyIncCompile -> {
//                // recover deploy state for device
//            }
            deployStateManager.deployState.isReadyRunFullBuild -> {
                logger.info("Can not deploy, install and run apk")
                deployTargetManager.runFullBuildAndLaunch()
                initCompileAfterFullBuild()
                return
            }
            else -> {
                logger.warn("Not ready to deploy")
            }
        }

        deployStateListener.onDeployed()
    }

    private fun updateInfoAfterIncDeploy(deployData: JuggDeployData) {
        val compiledFiles = deployFileManager.getCompiledFiles()
        val deployedFiles = deployFileManager.getStagingFiles()
        deployHistoryManager.updateHistoryOnAfterDeployed(compiledFiles, deployedFiles)
        deployFileManager.commit(deployData)
    }

    private fun initCompileAfterFullBuild() {
        logger.debug("Init compile after full build")

        val (costTime, compileContextInfo) = measureTimeMillisWithResult {
            val apkInfos = deployTargetManager.getApks()
            if (apkInfos.isEmpty()) {
                logger.warn("Init compile failed for no apk found")
                return
            }
            deployHistoryManager.reInitAfterFullCompiled(
                apkInfos, compileContextManager.compileContext.modules)
        }
        logger.debug("reInitAfterFullCompiled cost ${costTime}ms")

        initCompile(compileContextInfo)
    }

    private fun initCompile(
        compileContextInfo: CompileContextInfo,
    ) {
        logger.info("Init compile")

        deployStateManager.isBuildGradleChanged = false
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

        logger.info("Jugg init complete, waiting for file changes")
        onActionUpdate()
    }

    private fun ExecutorService.submitSafe(jobName: String, task: Runnable) {
        submit {
            try {
                val startTime = System.currentTimeMillis()
                logger.info("job <$jobName> start")
                task.run()
                val costTime = System.currentTimeMillis() - startTime
                logger.info("job <$jobName> finished, cost ${costTime}ms")
            } catch (e: Throwable) {
                logger.error("job <$jobName> failed", e)
            }
        }
    }

    override fun dispose() {
        logger.info("project $projectDir dispose")
    }
}