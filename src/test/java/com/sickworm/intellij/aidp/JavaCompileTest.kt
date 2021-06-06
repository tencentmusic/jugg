package com.sickworm.intellij.aidp

import org.junit.Test
import java.io.File
import java.lang.IllegalStateException


class JavaCompileTest {

    private val helloWorldTask = CompileTask.singleFile("$assetsJavaDir/com/sickworm/intellij/aidp/test/HelloWorldJavaFile.java", buildDir)
    @Test
    fun javaCompile() {
        val results = JavaCompiler().compile(helloWorldTask)
        assert(results.size == 1)
        assertCompileResult(results.first(), true)
    }

    private val errorTask = CompileTask.singleFile("$assetsJavaDir/com/sickworm/intellij/aidp/test/ErrorJavaFile.java", buildDir)
    @Test
    fun javaCompileError() {
        val results = JavaCompiler().compile(errorTask)
        assert(results.size == 1)
        assertCompileResult(results.first(), false, 2)
    }

    private val internalDepTask = CompileTask.singleFile("$assetsJavaDir/com/sickworm/intellij/aidp/test/JavaFileWithInternalDep.java", buildDir)
    @Test
    fun javaCompileWithInternalDep() {
        val results = JavaCompiler().compile(internalDepTask)
        assert(results.size == 1)
        assertCompileResult(results.first(), true)
    }

    private val externalDepTask = CompileTask.singleFile("$assetsJavaDir/com/sickworm/intellij/aidp/test/JavaFileWithExternalDep.java",
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

    private val classDepTask = CompileTask.singleFile("$assetsJavaDir/com/sickworm/intellij/aidp/test/JavaFileWithClassDep.java",
        buildDir,
        dependencies = listOf(assetsClassDir)
    )
    @Test
    fun javaCompileWithClassDep() {
        val results = JavaCompiler().compile(classDepTask)
        assert(results.size == 1)
        assertCompileResult(results.first(), true)
    }

    private val androidHome = System.getenv("ANDROID_HOME")
    private val androidJar = "$androidHome/platforms/android-30/android.jar"
    private val intellijLibraryDir = "$assetsAndroidDir/.idea/libraries"
    private val activityTask = CompileTask.singleFile("$assetsJavaDir/com/example/myapplication/MainActivity2.java",
        buildDir,
        dependencies = listOf(androidJar)
                + "$assetsAndroidDir/build/intermediates/javac/debug/classes"
                + IntellijLibraryConfigParser(intellijLibraryDir).parse()!!
    )
    @Test
    fun javaCompileAndroidActivity() {
        if (!File(androidJar).exists()) {
            throw IllegalStateException("android sdk not found, search ANDROID_HOME: $androidHome, Android jar file: $androidJar")
        }
        val results = JavaCompiler().compile(activityTask)
        assert(results.size == 1)
        assertCompileResult(results.first(), true)
    }

    @Test
    fun javaCompileMulti() {
        val compileTask = CompileTask(helloWorldTask.files + errorTask.files, File(buildDir))
        val results = JavaCompiler().compile(compileTask)

        assert(results.size == 2)
        assertCompileResult(results[0], true)
        assertCompileResult(results[1], false, 2)
    }

    private fun assertCompileResult(result: Result<CompileFileInfo, CompileError>, isSuccess: Boolean, errorCount: Int = 0) {
        if (!result.isSuccess) {
            println("assertCompileResult error count: ${result.getFailureOrNull()?.errors?.size}")
            println("assertCompileResult error messages:\n ${result.getFailureOrNull()?.errorMessages}")
        }

        assert(result.isSuccess == isSuccess)
        assert(result.isFailure == !isSuccess)
        assert(result.getFailureOrNull()?.errors?.size?: 0 == errorCount)
        val className = result.file.file.name.replace(".java", ".class")
        val packagePath = result.file.file.absolutePath.let {
            it.substring(assetsJavaDir.length, it.length - className.length + 1)
        }
        val classFile = File(buildDir + packagePath + className)
        if (isSuccess) {
            assert(classFile.exists() && classFile.length() > 0)
        } else {
            assert(!classFile.exists())
        }
    }
}