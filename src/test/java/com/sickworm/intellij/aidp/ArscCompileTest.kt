package com.sickworm.intellij.aidp

import org.junit.Before
import org.junit.Test
import java.io.File

class ArscCompileTest {

    private val compiler = ArscCompiler(logger)

    @Before
    fun init() {
        clearBuild()
    }

    @Test
    fun compileArsc() {
        val resDir = File(assetsAndroidDir, "build/intermediates/res/merged/debug")
        val task = CompileTask(
            listOf(CompileFile(resDir, CompileFile.Type.FlatDir, resDir)),
            compileOverlayDir
        )
        val result = compiler.compile(task)
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