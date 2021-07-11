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
            CompileFile(File("$assetsKotlinDir/com/sickworm/intellij/aidp/test/Result.kt"),
            CompileFile.Type.Kotlin,
            assetsKotlinDir)
        ),
        stagingDir)
    @Test
    fun kotlinCompile() {
        val task = resultTask
        val result = kotlinCompiler.compile(task)
        assertCompileResultKotlin(task, result, outputSize = 3)
    }

    private fun assertCompileResultKotlin(task: CompileTask,
                                        result: CompileResult,
                                        outputSize: Int = task.files.size) {
        val outputType = CompileOutput.Type.Class
        val mapper: OutputFileMapper = {
            val outputFile = it.file.changeBaseDir(it.baseDir, task.outputDir, "class")
            listOf(outputFile)
        }
        assertCompileResult(task, result, outputType, mapper, outputSize)
    }
}