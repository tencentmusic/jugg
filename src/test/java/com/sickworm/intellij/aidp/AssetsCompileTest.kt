package com.sickworm.intellij.aidp

import com.sickworm.intellij.aidp.compiler.CompileFile
import com.sickworm.intellij.aidp.compiler.CompileResult
import com.sickworm.intellij.aidp.compiler.CompileTask
import com.sickworm.intellij.aidp.compiler.AssetsCompiler
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
        result.printCompileErrors()

        assert(result.details.size == task.files.size)
        assert(result.outputs.size == task.files.size)
        assert(result.isAllSuccess)

        task.files.forEach {
            assert(it.file.exists() && it.file.length() > 0)
            val destFile = it.file.changeBaseDir(it.baseDir, outputDir)
            assert(destFile.exists() && destFile.length() > 0)
        }
    }
}