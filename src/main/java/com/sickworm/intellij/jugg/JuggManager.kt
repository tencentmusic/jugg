package com.sickworm.intellij.jugg

import com.android.tools.deployer.JuggDeployerHelper
import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VfsUtil
import com.sickworm.intellij.jugg.deploy.JuggDeployDataManager
import com.sickworm.intellij.jugg.deploy.DeployState
import com.sickworm.intellij.jugg.deploy.DeployTargetManager
import com.sickworm.intellij.jugg.deploy.DisableMessage
import com.sickworm.intellij.jugg.compiler.JuggCompiler
import com.sickworm.intellij.jugg.toolWindow.JuggLogger
import com.sickworm.intellij.jugg.toolWindow.JuggToolWindow
import com.sickworm.intellij.jugg.compiler.CompileFile
import com.sickworm.intellij.jugg.compiler.CompileTask
import com.sickworm.intellij.jugg.compiler.file
import com.sickworm.intellij.jugg.project.ChangedFile
import com.sickworm.intellij.jugg.project.CompileContextManager
import com.sickworm.intellij.jugg.project.FileChangesListener
import com.sickworm.intellij.jugg.project.FileChangesManager
import java.io.File
import java.util.concurrent.Executors


class JuggManager(private val project: Project,
                  private val projectDir: String,
                  private val toolWindow: JuggToolWindow
): Disposable {

    private val logger = JuggLogger.getInstance(project, "#Jugg-JuggManager")

    private val compileThread = Executors.newSingleThreadExecutor()
    private val deployThread = Executors.newSingleThreadExecutor()

    // hold compile context
    private val compileContextManager = CompileContextManager(project, projectDir)

    // detect file changes
    private val fileChangesManager = FileChangesManager(project, projectDir)

    // manage deploy data
    private val deployDataManager = JuggDeployDataManager()

    // compile dependency
    private val libraryDir = File("$projectDir/.idea/libraries")

    // compile
    private lateinit var compiler: JuggCompiler

    // deploy target apk
    private val deployTargetManager = DeployTargetManager(project)
    private var deployState = DeployState(isReadyInstall = false, isReadyApply = false, DisableMessage(
        DisableMessage.DisableMode.DISABLED, "not initialized", "jugg not initialized"
    ))

    fun init() {
        logger.info("Start Jugg")
        register(project, this)
        Disposer.register(project, this)

        compileThread.submit {
            logger.debug("Init compile context start")
            try {
                compileContextManager.init()
            } catch (e: Throwable) {
                logger.warn("Init compile context failed, please contact ch.operation@gmail.com", e)
                return@submit
            }
            logger.info("Init compile context finished")
        }
    }

    private var hasInit = false

    fun updateStatus(state: DeployState) {
        if (deployState == state) {
            return
        }

        if (state.isReadyApply && !hasInit) {
            hasInit = true
            // TODO check apk md5
            logger.info("Detected deployable apk, start init compile")
            compileThread.submit {
                compileContextManager.compileContext.update(apks = deployTargetManager.getApks())
                initCompile()
            }
        }

        toolWindow.updateStatus(state)
        deployState = state
    }

    private fun initCompile() {
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

        compileThread.submit {
            try {
                compileChanges()
            } catch (e: Exception) {
                logger.warn("Compile changes failed", e)
            }

            if (JuggSettings.deployOnSave) {
                deployAsync()
            }
        }
    }

    private fun addChanges(changedFiles: List<ChangedFile>) {
        changedFiles.forEach {
            deployDataManager.addChangedFile(it)
        }
    }

    private fun compileChanges() {
        // read all uncompiled files
        val compileFiles = deployDataManager.getUncompiledFiles().map {
            CompileFile(it.type, VfsUtil.virtualToIoFile(it.file), it.baseDir, it.module, dependencyPaths = compileContextManager.dependencies)
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
    }

    fun deployAsync() {
        deployThread.submit(::deploy)
    }

    private fun deploy() {
        try {
            logger.info("Deploy start")

            if (deployState.isReadyApply) {
                val apks = deployTargetManager.getApks()
                if (apks.isEmpty()) {
                    logger.warn("Deploy failed, can not find apks")
                    return
                }
                val deployData = deployDataManager.getDeployData(apks)
                if (deployData.isEmpty) {
                    logger.info("Deploy finished with no data to deploy")
                    return
                }

                logger.info("Deploy data:\n$deployData")

                JuggDeployerHelper.runTask(deployData, project)
                deployDataManager.commit()
            } else if (deployState.isReadyInstall) {
                logger.info("Can not deploy, install and run apk")
                deployTargetManager.runNormalBuild()
                return
            } else {
                logger.warn("Not ready to deploy")
            }

            logger.info("Deploy finished")
        } catch (e: Throwable) {
            logger.error("Deploy failed", e)
        }
    }

    override fun dispose() {
        unregister(project)
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

        // TODO remove
        fun getInstance(project: Project): JuggManager? {
            return map[project]
        }
    }
}