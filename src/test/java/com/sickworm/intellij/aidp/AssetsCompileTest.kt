package com.sickworm.intellij.aidp

import com.sickworm.intellij.aidp.compiler.*
import org.junit.Before
import org.junit.Test
import java.io.File

class AssetsCompileTest {

    private val overlayCompiler = AssetsCompiler(logger)

    @Before
    fun init() {
        clearBuild()
    }

    val singleFileTask = CompileTask(
        listOf(
            CompileFile(File(assetsAssetsDir, "logo.png"), CompileFile.Type.Overlay, assetsAssetsDir),
        ),
        stagingDir
    )
    @Test
    fun singleFileCompile() {
        val result = overlayCompiler.compile(singleFileTask)
        checkError(singleFileTask, result)
    }

    val multiFilesTask = CompileTask(
        listOf(
            CompileFile(File(assetsAssetsDir, "logo.png"), CompileFile.Type.Overlay, assetsAssetsDir),
            CompileFile(File(assetsAssetsDir, "git/index"), CompileFile.Type.Overlay, assetsAssetsDir),
        ),
        stagingDir
    )
    @Test
    fun multiFileCompile() {
        val result = AssetsCompiler(logger).compile(multiFilesTask)
        checkError(multiFilesTask, result)
    }

    fun checkError(task: CompileTask, result: CompileResult, outputDir: File = task.outputDir) {
        assertCompileResult(task, result, CompileOutput.Type.Overlay)
        task.files.forEach { file ->
            val destFile = file.file.changeBaseDir(file.baseDir, outputDir)
            assert(result.outputs.any { it.file.absolutePath == destFile.absolutePath })
        }
    }
}