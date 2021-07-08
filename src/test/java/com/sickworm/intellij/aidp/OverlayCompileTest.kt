package com.sickworm.intellij.aidp

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
                    File(assetsAssetsDir))
            ),
            File(compileOverlayDir)
        )
        val result = OverlayCompiler(logger).compile(task)
        result.printCompileErrors()

        assert(result.size == 1)
        assert(result.isAllSuccess)

        val destFile = File("$compileOverlayDir/logo.png")
        assert(destFile.exists() && destFile.length() > 0)
        assert(originFile.exists() && originFile.length() > 0)
    }

    @Test
    fun multiOverlayFileCompile() {
        val originFile1 = File("$assetsAssetsDir/logo.png")
        val originFile2 = File("$assetsAssetsDir/git/index")
        val task = CompileTask(
            listOf(
                CompileFile(originFile1, CompileFile.Type.Overlay, File(assetsAssetsDir)),
                CompileFile(originFile2, CompileFile.Type.Overlay, File(assetsAssetsDir)),
            ),
            File(compileOverlayDir)
        )
        val result = OverlayCompiler(logger).compile(task)
        result.printCompileErrors()

        assert(result.size == 2)
        assert(result.isAllSuccess)

        val destFile1 = File("$compileOverlayDir/logo.png")
        val destFile2 = File("$compileOverlayDir/git/index")
        assert(destFile1.exists() && destFile1.length() > 0)
        assert(destFile2.exists() && destFile2.length() > 0)
        assert(originFile1.exists() && originFile1.length() > 0)
        assert(originFile2.exists() && originFile2.length() > 0)
    }
}