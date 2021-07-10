package com.sickworm.intellij.aidp

import com.sickworm.intellij.aidp.compiler.CompileFile
import com.sickworm.intellij.aidp.compiler.CompileTask
import com.sickworm.intellij.aidp.compiler.OverlayCompiler
import org.junit.Before
import org.junit.Test
import java.io.File

class OverlayCompileTest {

    @Before
    fun init() {
        clearBuild()
    }

    @Test
    fun singleOverlayFileCompile() {
        val originFile = File("$assetsAssetsDir/logo.png")
        val task = CompileTask(
            listOf(
                CompileFile(
                    originFile,
                    CompileFile.Type.Overlay,
                    assetsAssetsDir)
            ),
            stagingDir
        )
        val result = OverlayCompiler(logger).compile(task)
        result.printCompileErrors()

        assert(result.details.size == 1)
        assert(result.isAllSuccess)

        val destFile = originFile.changeBaseDir(assetsDir, task.outputDir)
        assert(destFile.exists() && destFile.length() > 0)
        assert(originFile.exists() && originFile.length() > 0)
    }

    @Test
    fun multiOverlayFileCompile() {
        val originFile1 = File(assetsAssetsDir, "logo.png")
        val originFile2 = File(assetsAssetsDir, "git/index")
        val task = CompileTask(
            listOf(
                CompileFile(originFile1, CompileFile.Type.Overlay, assetsAssetsDir),
                CompileFile(originFile2, CompileFile.Type.Overlay, assetsAssetsDir),
            ),
            stagingDir
        )
        val result = OverlayCompiler(logger).compile(task)
        result.printCompileErrors()

        assert(result.details.size == 2)
        assert(result.isAllSuccess)

        val destFile1 = originFile1.changeBaseDir(assetsDir, task.outputDir)
        val destFile2 = originFile2.changeBaseDir(assetsDir, task.outputDir)
        assert(destFile1.exists() && destFile1.length() > 0)
        assert(destFile2.exists() && destFile2.length() > 0)
        assert(originFile1.exists() && originFile1.length() > 0)
        assert(originFile2.exists() && originFile2.length() > 0)
    }
}