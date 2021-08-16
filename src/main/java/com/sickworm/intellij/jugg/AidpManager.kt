package com.sickworm.intellij.jugg

import com.android.tools.deployer.AidpDeployerHelper
import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VfsUtil
import com.sickworm.intellij.jugg.deploy.AidpDeployDataManager
import com.sickworm.intellij.jugg.deploy.DeployState
import com.sickworm.intellij.jugg.deploy.DeployTargetManager
import com.sickworm.intellij.jugg.deploy.DisableMessage
import com.sickworm.intellij.jugg.compiler.AidpCompiler
import com.sickworm.intellij.jugg.toolWindow.AidpLogger
import com.sickworm.intellij.jugg.toolWindow.AidpToolWindow
import com.sickworm.intellij.jugg.compiler.CompileFile
import com.sickworm.intellij.jugg.compiler.CompileTask
import com.sickworm.intellij.jugg.compiler.file
import com.sickworm.intellij.jugg.project.ChangedFile
import com.sickworm.intellij.jugg.project.CompileContextManager
import com.sickworm.intellij.jugg.project.FileChangesListener
import com.sickworm.intellij.jugg.project.FileChangesManager
import java.io.File
import java.util.concurrent.Executors


class AidpManager(private val project: Project,
                  private val projectDir: String,
                  private val toolWindow: AidpToolWindow
): Disposable {

    private val logger = AidpLogger.getInstance(project, "#AIDP-AidpManager")

    private val compileThread = Executors.newSingleThreadExecutor()
    private val deployThread = Executors.newSingleThreadExecutor()

    // hold compile context
    private val compileContextManager = CompileContextManager(project, projectDir)

    // detect file changes
    private val fileChangesManager = FileChangesManager(project, projectDir)

    // manage deploy data
    private val deployDataManager = AidpDeployDataManager()

    // compile dependency
    private val libraryDir = File("$projectDir/.idea/libraries")

    // compile
    private lateinit var compiler: AidpCompiler

    // deploy target apk
    private val deployTargetManager = DeployTargetManager(project)
    private var deployState = DeployState(isReadyInstall = false, isReadyApply = false, DisableMessage(
        DisableMessage.DisableMode.DISABLED, "not initialized", "jugg not initialized"
    ))

    fun init() {
        logger.info("start AIDP")
        register(project, this)
        Disposer.register(project, this)

        compileThread.submit {
            logger.debug("init compile context start")
            try {
                compileContextManager.init()
            } catch (e: Throwable) {
                logger.warn("init compile context failed, please contact ch.operation@gmail.com", e)
                return@submit
            }
            logger.info("init compile context finished")
        }
    }

    fun updateStatus(state: DeployState) {
        if (deployState == state) {
            return
        }

        if (state.isReadyApply) {
            // TODO check apk md5
            logger.info("detected deployable apk, start init compile")
            compileThread.submit {
                compileContextManager.compileContext.update(apks = deployTargetManager.getApks())
                initCompile()
            }
        }

        toolWindow.updateStatus(state)
        deployState = state
    }

    private fun initCompile() {
        compiler = AidpCompiler(compileContextManager.compileContext)

        fileChangesManager.startListen(compileContextManager.compileContext, object: FileChangesListener {
            override fun onFileChanges(changedFiles: List<ChangedFile>) {
                processFileChanged(changedFiles)
            }
        })
        logger.info("AIDP ready to deploy!")
    }

    private fun processFileChanged(changedFiles: List<ChangedFile>) {
        addChanges(changedFiles)

        compileThread.submit {
            try {
                compileChanges()
            } catch (e: Exception) {
                logger.warn("compile changes failed", e)
            }

            if (AidpSettings.deployOnSave) {
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
            logger.info("deploy start")

            if (deployState.isReadyApply) {
                val apks = deployTargetManager.getApks()
                if (apks.isEmpty()) {
                    logger.warn("deploy failed, can not find apks")
                    return
                }
                val deployData = deployDataManager.getDeployData(apks)
                if (deployData.isEmpty) {
                    logger.info("deploy finished with no data to deploy")
                    return
                }

                logger.info("deploy data:\n$deployData")

                AidpDeployerHelper.runTask(deployData, project)
                deployDataManager.commit()
            } else if (deployState.isReadyInstall) {
                logger.info("can not deploy, install and run apk")
                deployTargetManager.runNormalBuild()
                return
            } else {
                logger.warn("not ready to deploy")
            }

            logger.info("deploy finished")
        } catch (e: Throwable) {
            logger.error("deploy failed", e)
        }
    }

    override fun dispose() {
        unregister(project)
    }

    companion object {
        val map = mutableMapOf<Project, AidpManager>()

        fun register(project: Project, aidpManager: AidpManager) {
            synchronized(map) {
                map[project] = aidpManager
            }
        }

        fun unregister(project: Project) {
            synchronized(map) {
                map.remove(project)
            }
        }

        // TODO remove
        fun getInstance(project: Project): AidpManager? {
            return map[project]
        }
    }
}