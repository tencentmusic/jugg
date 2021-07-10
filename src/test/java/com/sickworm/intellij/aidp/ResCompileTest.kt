package com.sickworm.intellij.aidp

import com.sickworm.intellij.aidp.compiler.*
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
        compileRes(listOf(file), baseDir)
    }

    @Test
    fun compileResAll() {
        val baseDir = File(assetsAndroidDir, "src/main/res/")
        val files = baseDir.listFilesRecursively()
        compileRes(files, baseDir)
    }

    private fun compileRes(files: List<File>, baseDir: File) {
        val task = CompileTask(
            files.map { CompileFile(it, CompileFile.Type.Res, baseDir) },
            compileOverlayDir
        )
        val result = resCompiler.compile(task)
        assert(result.details.size == files.size)
        assert(result.isAllSuccess)
        assert(result.outputs.size == files.size)
        result.outputs.forEach {
            assert(it.type == CompileOutput.Type.Flat)
            assert(it.file.exists())
            assert(it.file.length() > 0)
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