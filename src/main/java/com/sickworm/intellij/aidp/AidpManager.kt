package com.sickworm.intellij.aidp

import com.android.tools.AidpDeployDataManager
import com.android.tools.deployer.AidpDeployerHelper
import com.intellij.openapi.Disposable
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessModuleDir
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.wm.ToolWindow
import java.io.File
import java.util.concurrent.Executors


class AidpManager(private val project: Project,
                  projectDir: String,
                  private val toolWindow: ToolWindow
): Disposable {

    private val logger = AidpLogger.getInstance(project, "#AIDP-AidpManager")

    private val compileDir = File("$projectDir/build/aidp/deploy/compile")
    private val stagingDir = File("$projectDir/build/aidp/deploy/staging")
    private val libraryDir = File("$projectDir/.idea/libraries")

    private val fileChangesManager = FileChangesManager(project, projectDir)
    private val deployDataManager = AidpDeployDataManager(stagingDir)
    private val compiler = AidpCompiler(project)

    private var dependencies = listOf<String>()

    private val operaThread = Executors.newSingleThreadExecutor()

    init {
        register(project, this)
        Disposer.register(project, this)
    }

    fun start() {
        logger.info("start")

        operaThread.submit {
            try {
                initDependency()
            } catch (e: Exception) {
                logger.warn("dependencies load failed", e)
            }
        }

        fileChangesManager.startListen(object: FileChangesListener {
            override fun onFileChanges(changeFiles: List<ChangeFileInfo>) {
                processFileChanged(changeFiles)
            }
        })
    }

    private fun initDependency() {
        // TODO auto update when file changes
        val libDep = IntellijLibraryConfigParser(libraryDir).parse()?: emptyList()

        // TODO read project settings
        val androidHome = System.getenv("ANDROID_HOME")
        val androidDep = "$androidHome/platforms/android-30/android.jar"
        if (!File(androidDep).exists()) {
            logger.warn("androidDep not found, path: $androidDep")
            throw IllegalStateException("androidDep not found, path: $androidDep")
        }

        // TODO OPTIMIZE split by modules
        val projectDep = ModuleManager.getInstance(project).modules.mapNotNull {
            val baseDir = it.guessModuleDir()?: return@mapNotNull null
            if (!baseDir.exists()) return@mapNotNull null
            "${baseDir.path}/build/intermediates/javac/debug/classes"
        }

        dependencies = libDep + androidDep + projectDep

        logger.info("dependencies loaded, libDep size: ${libDep.size}, androidDep size: 1, projectDep size: ${projectDep.size}")
    }

    private fun processFileChanged(changeFiles: List<ChangeFileInfo>) {
        val compileFiles = changeFiles.map {
            CompileFileInfo(
                VfsUtil.virtualToIoFile(it.file),
                dependencyPaths = dependencies
            )
        }
        val result = compiler.compile(CompileTask(compileFiles, compileDir))
        if (result.isAllSuccess) {
            compileDir.listFilesRecursively().forEach {
                val relativePath = it.relativeTo(compileDir).path
                deployDataManager.addClass(it, relativePath, false)
            }
        }
    }

    fun apply() {
        try {
            logger.info("apply start")
            val deployData = deployDataManager.getDeployData()
            if (deployData.isEmpty) {
                logger.info("apply finished with no data to apply")
                return
            }

            AidpDeployerHelper.runTask(deployData, project, toolWindow)

            logger.info("apply finished")
        } catch (e: Throwable) {
            logger.error("apply failed", e)
        }
    }

    fun applyAsync() {
        operaThread.submit(::apply)
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

        fun getInstance(project: Project): AidpManager? {
            return map[project]
        }
    }
}