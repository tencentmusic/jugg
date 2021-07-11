package com.sickworm.intellij.aidp

import com.sickworm.intellij.aidp.compiler.CompileFile
import com.sickworm.intellij.aidp.compiler.CompileOutput
import com.sickworm.intellij.aidp.compiler.CompileTask
import com.sickworm.intellij.aidp.compiler.KotlinCompiler
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
            assetsJavaDir)
        ),
        stagingDir)
    @Test
    fun kotlinCompile() {
        val task = resultTask
        val result = kotlinCompiler.compile(task)
        assertCompileResult(task, result, CompileOutput.Type.Class, outputSize = 3)
    }
}