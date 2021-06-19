package com.sickworm.intellij.aidp

import com.android.tools.deployer.AidpDeployerHelper
import com.intellij.ide.impl.ProjectUtil
import com.intellij.openapi.Disposable
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessModuleDir
import com.intellij.openapi.vfs.VfsUtil
import org.jetbrains.kotlin.idea.util.sourceRoots
import java.io.File
import java.util.concurrent.Executors

private val logger = Logger.getInstance("#AIDP-AidpManager")

class AidpManager(private val project: Project,
                  private val projectDir: String
): Disposable {

    private val fileChangesManager = FileChangesManager(project, projectDir)
    private val compiler = AidpCompiler()
    private val outputDir = File("$projectDir/build/aidp")
    private var dependencies = listOf<String>()

    private val libraryDir = "$projectDir/.idea/libraries"

    private val operaThread = Executors.newSingleThreadExecutor()

    fun start() {
        logger.info("start")

        operaThread.submit {
            // TODO auto update when file changes
            val libDep = IntellijLibraryConfigParser(libraryDir).parse()?: emptyList()

            // TODO read project settings
            val androidHome = System.getenv("ANDROID_HOME")
            val androidDep = "$androidHome/platforms/android-30/android.jar"

            // TODO OPTIMIZE split by modules
            val projectDep = ModuleManager.getInstance(project).modules.mapNotNull {
                val baseDir = it.guessModuleDir()?: return@mapNotNull null
                if (!baseDir.exists()) return@mapNotNull null
                "${baseDir.path}/build/intermediates/javac/debug/classes"
            }

            dependencies = libDep + androidDep + projectDep

            logger.info("dependencies loaded, size: ${dependencies.size}")
        }

        fileChangesManager.startListen(object: FileChangesListener {
            override fun onFileChanges(changeFiles: List<ChangeFileInfo>) {
                process(changeFiles)
            }
        })
    }

    private fun process(changeFiles: List<ChangeFileInfo>) {
        val compileFiles = changeFiles.map {
            CompileFileInfo(
                VfsUtil.virtualToIoFile(it.file),
                dependencyPaths = dependencies
            )
        }
        compiler.compile(CompileTask(compileFiles, outputDir))

        // TODO test
//        AidpDeployerHelper.runTask(project)
    }

    override fun dispose() {
    }
}