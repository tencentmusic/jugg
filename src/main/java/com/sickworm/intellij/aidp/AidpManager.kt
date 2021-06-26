package com.sickworm.intellij.aidp

import com.android.tools.deployer.AidpDeployerHelper
import com.intellij.openapi.Disposable
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessModuleDir
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VfsUtil
import java.io.File
import java.lang.IllegalStateException
import java.util.concurrent.Executors


class AidpManager(private val project: Project,
                  projectDir: String
): Disposable {

    private val logger = AidpLogger.getInstance(project, "#AIDP-AidpManager")

    private val fileChangesManager = FileChangesManager(project, projectDir)
    private val compiler = AidpCompiler(project)
    private val outputDir = File("$projectDir/build/aidp/class/")
    private var dependencies = listOf<String>()

    private val libraryDir = "$projectDir/.idea/libraries"

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
        compiler.compile(CompileTask(compileFiles, outputDir))
    }

    fun apply() {
        try {
            logger.info("apply start")
            AidpDeployerHelper.runTask(project)
            logger.info("apply end")
        } catch (e: Error) {
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