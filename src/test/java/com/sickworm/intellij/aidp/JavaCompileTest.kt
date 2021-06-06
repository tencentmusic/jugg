package com.sickworm.intellij.aidp

import org.junit.Test
import java.io.File


class JavaCompileTest {

    private val buildDir = File("src/test/build")
    private val assetsDir = File("src/test/assets")
    private val assetsJavaDir = File(assetsDir.absolutePath + "/java")
    private val assetsLibDir = File(assetsDir.absolutePath + "/lib")
    private val packagePath = "/com/sickworm/intellij/aidp/test/"

    @Test
    fun javaCompile() {
        val javaFile = File("$assetsJavaDir/HelloWorldJavaFile.java")
        val fileInfo = CompileFileInfo(javaFile)
        val compileTask = CompileTask(listOf(fileInfo), buildDir)
        val results = JavaCompiler().compile(compileTask)

        assert(results.size == 1)
        val result = results.first()
        assertCompileResult(result, true)
    }

    @Test
    fun javaCompileError() {
        val javaFile = File("$assetsJavaDir/ErrorJavaFile.java")
        val fileInfo = CompileFileInfo(javaFile)
        val compileTask = CompileTask(listOf(fileInfo), buildDir)
        val results = JavaCompiler().compile(compileTask)

        assert(results.size == 1)
        val result = results.first()
        assertCompileResult(result, false, 2)
    }

    @Test
    fun javaCompileWithInternalDep() {
        val javaFile = File("$assetsJavaDir/JavaFileWithInternalDep.java")
        val fileInfo = CompileFileInfo(javaFile)
        val compileTask = CompileTask(listOf(fileInfo), buildDir)
        val results = JavaCompiler().compile(compileTask)

        assert(results.size == 1)
        val result = results.first()
        assertCompileResult(result, true, 0)
    }

    @Test
    fun javaCompileWithExternalDep() {
        val javaFile = File("$assetsJavaDir/JavaFileWithExternalDep.java")
        val fileInfo = CompileFileInfo(javaFile, dependencyPaths = listOf(
            assetsLibDir.absolutePath + "/rxjava-3.0.12.jar",
            assetsLibDir.absolutePath + "/reactive-streams-1.0.3.jar"
        ))
        val compileTask = CompileTask(listOf(fileInfo), buildDir)
        val results = JavaCompiler().compile(compileTask)

        assert(results.size == 1)
        val result = results.first()
        assertCompileResult(result, true, 0)
    }

    @Test
    fun javaCompileMulti() {
        val javaFile1 = File("$assetsJavaDir/HelloWorldJavaFile.java")
        val javaFile2 = File("$assetsJavaDir/ErrorJavaFile.java")

        val compileTask = CompileTask(listOf(CompileFileInfo(javaFile1), CompileFileInfo(javaFile2)), buildDir)
        val results = JavaCompiler().compile(compileTask)

        assert(results.size == 2)
        assertCompileResult(results[0], true)
        assertCompileResult(results[1], false, 2)
    }

    private fun assertCompileResult(result: Result<CompileFileInfo, CompileError>, isSuccess: Boolean, errorCount: Int = 0) {
        if (!result.isSuccess) {
            println("errors ${result.getFailureOrNull()?.errors}")
        }

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