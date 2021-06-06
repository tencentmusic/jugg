package com.sickworm.intellij.aidp

import org.junit.Test
import java.io.File


class JavaCompileTest {

    private val buildDir = "src/test/build"
    private val assetsDir = "src/test/assets"
    private val assetsJavaDir = "$assetsDir/java"
    private val assetsLibDir = "$assetsDir/lib"
    private val assetsClassDir = "$assetsDir/class"
    private val packagePath = "/com/sickworm/intellij/aidp/test/"

    private val helloWorldTask = CompileTask.singleFile("$assetsJavaDir/HelloWorldJavaFile.java", buildDir)
    @Test
    fun javaCompile() {
        val results = JavaCompiler().compile(helloWorldTask)
        assert(results.size == 1)
        assertCompileResult(results.first(), true)
    }

    private val errorTask = CompileTask.singleFile("$assetsJavaDir/ErrorJavaFile.java", buildDir)
    @Test
    fun javaCompileError() {
        val results = JavaCompiler().compile(errorTask)
        assert(results.size == 1)
        assertCompileResult(results.first(), false, 2)
    }

    private val internalDepTask = CompileTask.singleFile("$assetsJavaDir/JavaFileWithInternalDep.java", buildDir)
    @Test
    fun javaCompileWithInternalDep() {
        val results = JavaCompiler().compile(internalDepTask)
        assert(results.size == 1)
        assertCompileResult(results.first(), true)
    }

    private val externalDepTask = CompileTask.singleFile("$assetsJavaDir/JavaFileWithExternalDep.java",
        buildDir,
        dependencies = listOf(
            "$assetsLibDir/rxjava-3.0.12.jar",
            "$assetsLibDir/reactive-streams-1.0.3.jar"
        )
    )
    @Test
    fun javaCompileWithExternalDep() {
        val results = JavaCompiler().compile(externalDepTask)
        assert(results.size == 1)
        assertCompileResult(results.first(), true)
    }

    @Test
    fun javaCompileMulti() {
        val javaFile1 = File("$assetsJavaDir/HelloWorldJavaFile.java")
        val javaFile2 = File("$assetsJavaDir/ErrorJavaFile.java")

        val compileTask = CompileTask(listOf(CompileFileInfo(javaFile1), CompileFileInfo(javaFile2)), File(buildDir))
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
        val classFile = File(buildDir + packagePath + className)
        if (isSuccess) {
            assert(classFile.exists() && classFile.length() > 0)
        } else {
            assert(!classFile.exists())
        }
    }
}