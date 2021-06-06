package com.sickworm.intellij.aidp

import org.junit.Test
import java.io.File

class JavaCompileTest {

    private val buildDir = File("src/test/build")
    private val packagePath = "/com/sickworm/intellij/aidp/"

    @Test
    fun javaCompile() {
        val javaFile = File("src/test/assets/HelloWorldJavaFile.java")
        val fileInfo = CompileFileInfo(javaFile)
        val results = JavaCompiler().compile(listOf(fileInfo), buildDir)

        assert(results.size == 1)
        val result = results.first()
        assertCompileResult(result, true)
    }

    @Test
    fun javaCompileError() {
        val javaFile = File("src/test/assets/ErrorJavaFile.java")
        val fileInfo = CompileFileInfo(javaFile)
        val results = JavaCompiler().compile(listOf(fileInfo), buildDir)

        assert(results.size == 1)
        val result = results.first()
        assertCompileResult(result, false, 2)
    }

    @Test
    fun javaCompileMulti() {
        val javaFile1 = File("src/test/assets/HelloWorldJavaFile.java")
        val javaFile2 = File("src/test/assets/ErrorJavaFile.java")

        val results = JavaCompiler().compile(listOf(CompileFileInfo(javaFile1), CompileFileInfo(javaFile2)), buildDir)

        assert(results.size == 2)
        assertCompileResult(results[0], true)
        assertCompileResult(results[1], false, 2)
    }

    private fun assertCompileResult(result: Result<CompileFileInfo, CompileError>, isSuccess: Boolean, errorCount: Int = 0) {
        assert(result.isSuccess == isSuccess)
        assert(result.isFailure == !isSuccess)
        assert(result.getFailureOrNull()?.errors?.size?: 0 == errorCount)
        val className = result.file.file.name.replace(".java", ".class")
        val classFile = File(buildDir.absolutePath + packagePath + className)
        if (isSuccess) {
            assert(classFile.exists() && classFile.length() > 0)
        } else {
            assert(!classFile.exists())
        }
    }
}