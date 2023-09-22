package com.sickworm.intellij.jugg.compile

import com.sickworm.intellij.jugg.compiler.*
import com.sickworm.intellij.jugg.compiler.overlay.AssetOverlayCompiler
import com.sickworm.intellij.jugg.mock.*
import org.junit.Before
import org.junit.Test
import java.io.File

class AssetCompileTest {

    private val assetCompiler = AssetOverlayCompiler(context)

    @Before
    fun init() {
        clearBuild()
    }

    val singleFileTask = CompileTask(
        listOf(
            CompileFile(CompileFile.Type.Asset, File(assetsAssetsDir, "logo.png"), assetsAssetsDir, mockModule),
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
            CompileFile(CompileFile.Type.Asset, File(assetsAssetsDir, "logo.png"), assetsAssetsDir, mockModule),
            CompileFile(CompileFile.Type.Asset, File(assetsAssetsDir, "git/index"), assetsAssetsDir, mockModule),
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
            listOf(CompileOutput(CompileOutput.Type.Asset, outputFile, task.outputDir))
        }
        assertCompileResult(task, result, mapper)
    }
}