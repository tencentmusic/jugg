package com.sickworm.intellij.aidp

import com.android.tools.deployer.AidpDeployerHelper
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.PersistentStateComponent
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

    private val operaThread = Executors.newSingleThreadExecutor()

    // detect file changes
    private val fileChangesManager = FileChangesManager(project, projectDir)

    // manage deploy data
    private val stagingDir = File("$projectDir/build/aidp/deploy/staging")
    private val deployDataManager = AidpDeployDataManager(stagingDir)

    // compile dependency
    private val libraryDir = File("$projectDir/.idea/libraries")
    private val classPathDir = File("$projectDir/build/aidp/deploy/classpath")
    private var dependencies = listOf<String>()

    // compile
    private val compileClassDir = File("$projectDir/build/aidp/deploy/compiled/classes")
    private val compileDexDir = File("$projectDir/build/aidp/deploy/compiled/dex")
    private val compiler = AidpCompiler(project, compileClassDir, classPathDir)

    init {
        register(project, this)
        Disposer.register(project, this)
    }

    fun start() {
        logger.info("start AIDP")

        operaThread.submit {
            try {
                initDependency()
            } catch (e: Exception) {
                logger.warn("dependencies load failed", e)
            }

            fileChangesManager.startListen(object: FileChangesListener {
                override fun onFileChanges(changeFiles: List<ChangeFileInfo>) {
                    operaThread.submit {
                        processFileChanged(changeFiles)
                    }
                }
            })
        }
    }

    private fun initDependency() {
        // TODO auto update when file changes
        val libDep = IntellijLibraryConfigParser(libraryDir).parse()?: emptyList()

        // TODO read project settings ( ModuleRootManager.getInstance(module).sdk.rootProvider.getFiles(OrderRootType.CLASSES) )
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

        if (!classPathDir.exists()) {
            classPathDir.mkdirs()
        }
        val aidpClassPathDep = listOf(classPathDir.absolutePath)

        dependencies = libDep + androidDep + projectDep + aidpClassPathDep

        logger.info("dependencies loaded, libDep size: ${libDep.size}, projectDep size: ${projectDep.size}, androidDep size: 1, aidpClassPathDep size: 1")
    }

    private fun processFileChanged(changeFiles: List<ChangeFileInfo>) {
        // store source files
        changeFiles.forEach {
            deployDataManager.addChangedFile(VfsUtil.virtualToIoFile(it.file))
        }

        // read all uncompiled files
        val compileFiles = deployDataManager.getUncompiledFiles().map {
            CompileFileInfo(it, dependencyPaths = dependencies)
        }

        // do compile
        compileDexDir.clearDir()
        val result = compiler.compile(CompileTask(compileFiles, compileDexDir))
        if (!result.isAllSuccess) {
            // TODO accept successfully compiled files
            return
        }

        // mark source files compiled
        compileFiles.forEach {
            deployDataManager.markAsCompiled(it.file)
        }

        // stage deploy files
        compileDexDir.listFilesRecursively().forEach {
            deployDataManager.addClassFile(it, compileDexDir, false)
        }

        if (AidpSettings.deployOnSave) {
            deployAsync()
        }
    }

    fun deployAsync() {
        operaThread.submit(::deploy)
    }

    private fun deploy() {
        try {
            logger.info("apply start")
            val deployData = deployDataManager.getDeployData()
            if (deployData.isEmpty) {
                logger.info("apply finished with no data to apply")
                return
            }

            logger.info("apply data:\n$deployData")

            AidpDeployerHelper.runTask(deployData, project, toolWindow)
            deployDataManager.commit()

            logger.info("apply finished")
        } catch (e: Throwable) {
            logger.error("apply failed", e)
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