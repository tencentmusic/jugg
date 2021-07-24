package com.sickworm.intellij.aidp

import com.android.tools.deployer.AidpDeployerHelper
import com.intellij.openapi.Disposable
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessModuleDir
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.wm.ToolWindow
import com.sickworm.intellij.aidp.compiler.AidpCompiler
import com.sickworm.intellij.aidp.compiler.CompileFile
import com.sickworm.intellij.aidp.compiler.CompileTask
import com.sickworm.intellij.aidp.compiler.file
import com.sickworm.intellij.aidp.deploy.DeployTargetManager
import com.sickworm.intellij.aidp.toolWindow.AidpToolWindow
import java.io.File
import java.util.concurrent.Executors


class AidpManager(private val project: Project,
                  projectDir: String,
                  private val toolWindow: AidpToolWindow
): Disposable {

    private val logger = AidpLogger.getInstance(project, "#AIDP-AidpManager")

    private val compileThread = Executors.newSingleThreadExecutor()
    private val deployThread = Executors.newSingleThreadExecutor()

    // detect file changes
    private val fileChangesManager = FileChangesManager(project, projectDir)
    private val buildDir = File("$projectDir/build/aidp/build/")

    // manage deploy data
    private val stagingDir = File(buildDir, "staging")
    private val deployDataManager = AidpDeployDataManager()

    // compile dependency
    private val libraryDir = File("$projectDir/.idea/libraries")
    private val classPathDir = File(buildDir, "classpath")
    private var dependencies = listOf<String>()

    // compile
    private val compileClassDir = File(buildDir, "compiled")
    private lateinit var compiler: AidpCompiler

    // deploy target apk
    private val deployTargetManager = DeployTargetManager(project)

    init {
        register(project, this)
        Disposer.register(project, this)
    }

    fun start() {
        logger.info("start AIDP")

        compileThread.submit {
            try {
                initDependency()
            } catch (e: Exception) {
                logger.warn("dependencies load failed", e)
            }

            fileChangesManager.startListen(object: FileChangesListener {
                override fun onFileChanges(changedFiles: List<ChangedFile>) {
                    processFileChanged(changedFiles)
                }
            })
        }
    }

    private fun initDependency() {
        // TODO auto update when file changes
        // TODO try Class.forName("com.android.tools.idea.AndroidProjectModelUtils").declaredMethods[3].invoke(Class.forName("com.android.tools.idea.AndroidProjectModelUtils"), project)
        val libDep = IntellijLibraryConfigParser(libraryDir).parse()!!

        // TODO read project settings ( ModuleRootManager.getInstance(module).sdk.rootProvider.getFiles(OrderRootType.CLASSES) )
        // TODO AndroidSdkEventListener on sdk path changed
        val androidHome = System.getenv("ANDROID_HOME")
        // TODO select sdk and build tools by gradle
        val androidDep = "$androidHome/platforms/android-30/android.jar"
        val androidBuildTools = "$androidHome/build-tools/30.0.3"
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

        // TODO use apk analyze
        // FIXME
        val appBuildDir = File("")
        val flatDir = File(appBuildDir, "intermediates/res/merged/debug")
        val manifest = File(appBuildDir, "intermediates/merged_manifests/debug/AndroidManifest.xml")
        compiler = AidpCompiler(project,
            tempCompileDir = compileClassDir,
            classPathDir = classPathDir,
            androidJar = File(androidDep),
            androidBuildTools = File(androidBuildTools),
            flatDir = flatDir,
            manifest = manifest,
            stableIds = File(buildDir, "stableIds.txt")
            )
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
            CompileFile(it.type, VfsUtil.virtualToIoFile(it.file), it.baseDir, dependencyPaths = dependencies)
        }

        // do compile
        val result = compiler.compile(CompileTask(compileFiles, stagingDir))

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
        if (!deployTargetManager.canDeploy()) {
            logger.info("no apk found. run normal build")
            deployTargetManager.runNormalBuild()
            return
        }

        try {
            logger.info("deploy start")
            val deployData = deployDataManager.getDeployData()
            if (deployData.isEmpty) {
                logger.info("apply finished with no data to apply")
                return
            }

            logger.info("deploy data:\n$deployData")

            AidpDeployerHelper.runTask(deployData, project)
            deployDataManager.commit()

            logger.info("deploy finished")
        } catch (e: Throwable) {
            logger.error("deploy failed", e)
        }
    }

    fun updateStatus(msg: String) {
        toolWindow.updateStatus(msg)
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