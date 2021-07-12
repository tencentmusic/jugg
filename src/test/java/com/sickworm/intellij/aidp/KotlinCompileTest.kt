package com.sickworm.intellij.aidp

import com.sickworm.intellij.aidp.compiler.*
import org.junit.Before
import org.junit.Test
import java.io.File

class KotlinCompileTest {

    private val kotlinCompiler = KotlinCompiler()

    @Before
    fun init() {
        clearBuild()
    }

    private val resultTask = CompileTask(
        listOf(
            CompileFile(
                CompileFile.Type.Kotlin,
                File("$assetsKotlinDir/com/sickworm/intellij/aidp/test/Result.kt"),
                assetsKotlinDir)
        ),
        stagingDir)
    @Test
    fun kotlinCompile() {
        val task = resultTask
        val result = kotlinCompiler.compile(task)
        assertCompileResultKotlin(task, result)
    }

    private fun assertCompileResultKotlin(task: CompileTask, result: CompileResult) {
        val mapper: OutputFileMapper = {
            val outputFile = it.file.changeBaseDir(it.baseDir, task.outputDir, "class")
            val companionFile = it.file.changeBaseDir(it.baseDir, task.outputDir, newName = "${it.file.nameWithoutExtension}\$Companion.class")
            listOf(
                CompileOutput(CompileOutput.Type.Class, outputFile, task.outputDir),
                CompileOutput(CompileOutput.Type.Class, companionFile, task.outputDir)
            )
        }
        assertCompileResult(task, result, mapper)
    }
}