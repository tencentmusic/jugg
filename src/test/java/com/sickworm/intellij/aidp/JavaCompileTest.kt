package com.sickworm.intellij.aidp

import org.junit.Before
import org.junit.Test
import java.io.File
import java.lang.IllegalStateException


class JavaCompileTest {

    val javaCompiler = JavaCompiler()
    
    @Before
    fun init() {
        File(buildDir).listFiles()?.forEach { it.deleteRecursively() }
    }

    private val helloWorldTask = CompileTask.singleFile("$assetsJavaDir/com/sickworm/intellij/aidp/test/HelloWorldJavaFile.java", buildDir)
    @Test
    fun javaCompile() {
        val results = javaCompiler.compile(helloWorldTask)
        assert(results.size == 1)
        assertCompileResult(results.first(), true)
    }

    private val errorTask = CompileTask.singleFile("$assetsJavaDir/com/sickworm/intellij/aidp/test/ErrorJavaFile.java", buildDir)
    @Test
    fun javaCompileError() {
        val results = javaCompiler.compile(errorTask)
        assert(results.size == 1)
        assertCompileResult(results.first(), false, 2)
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
        val results = javaCompiler.compile(externalDepTask)
        assert(results.size == 1)
        assertCompileResult(results.first(), true)
    }

    private val classDepTask = CompileTask.singleFile("$assetsJavaDir/com/sickworm/intellij/aidp/test/JavaFileWithClassDep.java",
        buildDir,
        dependencies = listOf(assetsClassDir)
    )
    @Test
    fun javaCompileWithClassDep() {
        val results = javaCompiler.compile(classDepTask)
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
        val results = javaCompiler.compile(activityTask)
        assert(results.size == 1)
        assertCompileResult(results.first(), true)
    }

    @Test
    fun javaCompileMultiFiles() {
        val compileTask = helloWorldTask + externalDepTask + classDepTask + activityTask
        val results = javaCompiler.compile(compileTask)

        assert(results.size == compileTask.files.size)

        results.forEach {
            assertCompileResult(it, true)
        }
    }

    @Test
    fun javaCompileMultiFilesError() {
        val compileTask = helloWorldTask + errorTask + externalDepTask + classDepTask + activityTask
        val results = javaCompiler.compile(compileTask)

        assert(results.size == compileTask.files.size)

        results.forEach {
            if (errorTask.files[0] == it.file) {
                assertCompileResult(it, false, 2)
            } else {
                assertCompileResult(it, false, 0)
            }
        }
    }

    private fun assertCompileResult(result: Result<CompileFileInfo, CompileError>, isSuccess: Boolean, errorCount: Int? = null) {
        if (result.isFailed) {
            println("assertCompileResult error count: ${result.getFailure().errors.size}")
            println("assertCompileResult error messages:\n ${result.getFailure().errorMessages}")
        }

        assert(result.isSuccess == isSuccess)
        assert(result.isFailed == !isSuccess)
        if (isSuccess) {
            assert(result.getFailureOrNull() == null)
        } else {
            if (errorCount != null) {
                assert(result.getFailure().errors.size == errorCount)
            }
        }
        val className = result.file.file.name.replace(".java", ".class")
        val packagePath = result.file.file.absolutePath.let {
            it.substring(assetsJavaDir.length, it.length - className.length + 1)
        }
        val classFile = File(buildDir + packagePath + className)
        if (isSuccess) {
            assert(classFile.exists() && classFile.length() > 0)
        } else {
            // we don't know the generated class path so we won't delete files if failed in the middle of compilation
        }
    }
}