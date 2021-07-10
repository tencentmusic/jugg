package com.sickworm.intellij.aidp

import org.junit.Before
import org.junit.Test
import java.io.File

class ResCompileTest {

    private val resCompiler = ResCompiler(logger)
    private val arscCompiler = ArscCompiler(logger)

    @Before
    fun init() {
        clearBuild()
    }

    @Test
    fun compileResLayout() {
        val file = File(assetsAndroidDir, "src/main/res/layout/activity_main.xml")
        val baseDir = File(assetsAndroidDir, "src/main/res/")
        val task = CompileTask(
            listOf(CompileFile(file, CompileFile.Type.Res, baseDir)),
            compileOverlayDir
        )
        val result = resCompiler.compile(task)
        assert(result.details.size == 1)
        assert(result.isAllSuccess)
        assert(result.outputs.size == 1)
        assert(result.outputs.first().type == CompileOutput.Type.Flat)
        result.outputs.first().file.let {
            assert(it.exists())
            assert(it.length() > 0)
        }
    }

    @Test
    fun compileArsc() {
        val resDir = File(assetsAndroidDir, "build/intermediates/res/merged/debug")
        val task = CompileTask(
            listOf(CompileFile(resDir, CompileFile.Type.FlatDir, resDir)),
            compileOverlayDir
        )
        val result = arscCompiler.compile(task)
        assert(result.details.size == 1)
        assert(result.isAllSuccess)
        assert(result.outputs.size == 1)
        assert(result.outputs.first().type == CompileOutput.Type.Overlay)
        result.outputs.first().file.let {
            assert(it.exists())
            assert(it.length() > 0)
        }
    }
}