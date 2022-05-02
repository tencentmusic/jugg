package com.sickworm.intellij.jugg

import com.android.tools.deployer.JuggDeployerHelper
import com.android.tools.idea.run.ApkInfo
import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VfsUtil
import com.sickworm.intellij.jugg.compiler.*
import java.util.concurrent.Executors
import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.jugg.deploy.*
import com.sickworm.intellij.jugg.ide.JuggSettings
import com.sickworm.intellij.jugg.ide.toolWindow.JuggStateListener
import com.sickworm.intellij.jugg.project.*
import org.jetbrains.annotations.TestOnly
import java.util.concurrent.ExecutorService

class JuggManager @TestOnly constructor(
    private val project: Project,
    projectDir: String,
    private val deployStateListener: JuggStateListener,
    private val logger: Logger = JuggLogger.getInstance(project, "#Jugg-JuggManager"),
    private val compileThread: ExecutorService = Executors.newSingleThreadExecutor(),
    private val deployThread: ExecutorService = Executors.newSingleThreadExecutor(),
    // hold compile context
    private val compileContextManager: CompileContextManager  = CompileContextManager(project, projectDir),
    // detect file changes
    private val fileChangesManager: FileChangesManager = FileChangesManager(project, projectDir),
    // manage deploy data
    private val deployDataManager: DeployDataManager = DeployDataManager(compileContextManager, logger),
    // manage deploy target apk and device
    private val deployTargetManager: DeployTargetManager = DeployTargetManager(project),
    // manage JuggDeployState
    private val deployStateManager: DeployStateManager = DeployStateManager(project),
    // deploy to device
    private val juggDeployerHelper: JuggDeployerHelper = JuggDeployerHelper(project, deployTargetManager),
): Disposable {

    constructor(project2: Project,
                projectDir: String,
                juggDeployStateListener: JuggStateListener):
            this(project = project2, projectDir, juggDeployStateListener)

    private var compiler: JuggCompiler? = null

    fun init() {
        logger.info("Start Jugg")
        register(project, this)
        Disposer.register(project, this)

        compileThread.submitSafe("Init compile context - initProjectInfo") {
            compileContextManager.initProjectInfo()
        }
    }

    private var hasInit = false

    fun onActionUpdate(): JuggDeployState {

        val oldDeployState = deployStateManager.deployState
        val deployState = deployStateManager.onActionUpdate()
        if (deployState == oldDeployState) {
            // won't do anything if deploy state is not changed
            return deployState
        }

        deployStateListener.onDeployStateUpdate(deployState)

        if (deployState.isReadyCompile) {
            initCompileIfNeeded()
        } else {
            if (!deployState.isReadyDeploy && oldDeployState.isReadyDeploy) {
                // revert
                hasInit = false
            }
        }

        return deployState
    }

    private fun initCompileIfNeeded() {
        if (hasInit) {
            return
        }

        val apks = deployTargetManager.getApks()
        if (apks.isEmpty()) {
            return
        }

        hasInit = true
        // TODO check apk md5
        logger.info("Detected deployable apk, start init compile")
        compileThread.submitSafe("Init compile context - initCompile") {
            initCompile(apks)
        }
    }

    private fun initCompile(apks: List<ApkInfo>) {
        compileContextManager.initFullBuildInfo(apks)
        compiler = JuggCompiler(compileContextManager.compileContext)

        fileChangesManager.startListen(compileContextManager.compileContext, object: FileChangesListener {
            override fun onFileChanges(changedFiles: List<ChangedFile>) {
                processFileChanged(changedFiles)
            }
        })
        logger.info("Jugg ready to deploy!")
    }

    private fun processFileChanged(changedFiles: List<ChangedFile>) {
        addChanges(changedFiles)

        if (JuggSettings.compileOnSave) {
            compileChangesAsync()
        }
    }

    private fun addChanges(changedFiles: List<ChangedFile>) {
        changedFiles.forEach {
            deployDataManager.addChangedFile(it)
        }
    }

    private fun compileChangesAsync() {
        compileThread.submitSafe("Compile") {
            val compileResult = compileChanges()
            logger.info("Compile result, success: ${compileResult.successFiles.size}, failure: ${compileResult.failedFiles.size}")

            if (JuggSettings.deployOnSave) {
                deployAsync(false)
            }
        }
    }

    @TestOnly
    fun compileChanges(): CompileResult {
        val compiler = compiler?: run {
            throw JuggInternalException.compilerNotInit()
        }

        // read all uncompiled files
        val compileFiles = deployDataManager.getUncompiledFiles().map {
            CompileFile(it.type, it.file, it.baseDir, it.module, dependencyPaths = compileContextManager.dependencies)
        }

        // do compile
        val result = compiler.compile(CompileTask(compileFiles, compileContextManager.stagingDir))

        // mark source files compiled
        result.details.forEach {
            if (it.isSuccess) {
                deployDataManager.markAsCompiled(it.file)
            }
        }

        // stage deploy files
        result.outputs.forEach {
            deployDataManager.addDeployFile(it)
        }

        return result
    }

    fun deployAsync(isUserClick: Boolean) {
        if (!deployStateManager.deployState.isReadyDeploy) {
            if (isUserClick) {
                logger.warn("Deployment is not ready, skip deploy")
            } else {
                logger.info("Deployment is not ready, skip deploy")
            }
            return
        }
        deployThread.submitSafe("Deploy", ::deploy)
    }

    @TestOnly
    fun deploy() {
        when {
            deployStateManager.deployState.isReadyDeploy -> {
                val deployData = deployDataManager.getDeployData()
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
                deployDataManager.commit(deployData)
            }
            deployStateManager.deployState.isReadyInstall -> {
                logger.info("Can not deploy, install and run apk")
                deployTargetManager.runNormalBuild()
                return
            }
            else -> {
                logger.warn("Not ready to deploy")
            }
        }
    }

    override fun dispose() {
        unregister(project)
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

    companion object {
        val map = mutableMapOf<Project, JuggManager>()

        fun register(project: Project, juggManager: JuggManager) {
            synchronized(map) {
                map[project] = juggManager
            }
        }

        fun unregister(project: Project) {
            synchronized(map) {
                map.remove(project)
            }
        }

        fun getInstance(project: Project): JuggManager? {
            return map[project]
        }
    }
}