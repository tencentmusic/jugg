package com.sickworm.intellij.aidp

import org.junit.Test
import java.io.File

class CompileTest {

    private val buildDir = File("src/test/build")
    private val packagePath = "/com/sickworm/intellij/aidp/"

    @Test
    fun javaCompile() {
        val javaFile = File("src/test/assets/HelloWorldJavaFile.java")
        val fileInfo = CompileFileInfo(javaFile)
        val results = JavaCompiler().compile(listOf(fileInfo), buildDir)

        assert(results.size == 1)
        val result = results.first()
        assert(result.isSuccess)
        val classFile = File(buildDir.absolutePath + packagePath + "HelloWorldJavaFile.class")
        assert(classFile.exists() && classFile.length() > 0)
    }

    @Test
    fun javaCompileError() {
        val javaFile = File("src/test/assets/ErrorJavaFile.java")
        val fileInfo = CompileFileInfo(javaFile)
        val results = JavaCompiler().compile(listOf(fileInfo), buildDir)

        assert(results.size == 1)
        val result = results.first()
        assert(result.isFailure)
        assert(result.getFailureOrNull() != null)
        assert(result.getFailureOrNull()!!.errors.size == 2)

        val classFile = File(buildDir.absolutePath + packagePath + "ErrorJavaFile.class")
        assert(!classFile.exists())
    }
}