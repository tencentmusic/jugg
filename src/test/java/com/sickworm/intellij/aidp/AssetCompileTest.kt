package com.sickworm.intellij.aidp

import com.sickworm.intellij.aidp.compiler.*
import org.junit.Before
import org.junit.Test
import java.io.File

class AssetCompileTest {

    private val assetCompiler = AssetCompiler(logger)

    @Before
    fun init() {
        clearBuild()
    }

    val singleFileTask = CompileTask(
        listOf(
            CompileFile(File(assetsAssetsDir, "logo.png"), CompileFile.Type.Asset, assetsAssetsDir),
        ),
        stagingDir
    )
    @Test
    fun singleFileCompile() {
        val task = singleFileTask
        val result = assetCompiler.compile(task)
        assertCompileResultAssets(task, result)
    }

    val multiFilesTask = CompileTask(
        listOf(
            CompileFile(File(assetsAssetsDir, "logo.png"), CompileFile.Type.Asset, assetsAssetsDir),
            CompileFile(File(assetsAssetsDir, "git/index"), CompileFile.Type.Asset, assetsAssetsDir),
        ),
        stagingDir
    )
    @Test
    fun multiFileCompile() {
        val task = multiFilesTask
        val result = assetCompiler.compile(task)
        assertCompileResultAssets(task, result)
    }

    private fun assertCompileResultAssets(task: CompileTask, result: CompileResult) {
        val mapper: OutputFileMapper = {
            val outputFile = it.file.changeBaseDir(it.baseDir, task.outputDir)
            listOf(CompileOutput(outputFile, task.outputDir, CompileOutput.Type.Overlay))
        }
        assertCompileResult(task, result, mapper)
    }
}