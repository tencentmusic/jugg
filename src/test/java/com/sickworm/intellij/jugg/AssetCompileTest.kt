package com.sickworm.intellij.jugg

import com.sickworm.intellij.jugg.compiler.overlay.AssetOverlayCompiler
import com.sickworm.intellij.jugg.changeBaseDir
import com.sickworm.intellij.jugg.compiler.CompileFile
import com.sickworm.intellij.jugg.compiler.CompileOutput
import com.sickworm.intellij.jugg.compiler.CompileResult
import com.sickworm.intellij.jugg.compiler.CompileTask
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
            CompileFile(CompileFile.Type.Asset, File(assetsAssetsDir, "logo.png"), assetsAssetsDir),
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
            CompileFile(CompileFile.Type.Asset, File(assetsAssetsDir, "logo.png"), assetsAssetsDir),
            CompileFile(CompileFile.Type.Asset, File(assetsAssetsDir, "git/index"), assetsAssetsDir),
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
            listOf(CompileOutput(CompileOutput.Type.Overlay, outputFile, task.outputDir))
        }
        assertCompileResult(task, result, mapper)
    }
}