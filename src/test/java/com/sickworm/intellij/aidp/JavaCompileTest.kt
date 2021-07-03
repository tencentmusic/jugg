package com.sickworm.intellij.aidp

import org.junit.Before
import org.junit.Test
import java.io.File
import java.lang.IllegalStateException


class JavaCompileTest {

    private val javaCompiler = JavaCompiler(logger)

    @Before
    fun init() {
        clearBuild()
    }

    private val helloWorldTask = CompileTask.singleFile("$assetsJavaDir/com/sickworm/intellij/aidp/test/HelloWorldJavaFile.java", classPathDir)
    @Test
    fun javaCompile() {
        val results = javaCompiler.compile(helloWorldTask)
        assert(results.size == 1)
        assertCompileResult(assetsJavaDir, results.first(), true)
    }

    private val errorTask = CompileTask.singleFile("$assetsJavaDir/com/sickworm/intellij/aidp/test/ErrorJavaFile.java", classPathDir)
    @Test
    fun javaCompileError() {
        val results = javaCompiler.compile(errorTask)
        assert(results.size == 1)
        assertCompileResult(assetsJavaDir, results.first(), false, 2)
    }

    private val externalDepTask = CompileTask.singleFile("$assetsJavaDir/com/sickworm/intellij/aidp/test/JavaFileWithExternalDep.java",
        classPathDir,
        dependencies = listOf(
            "$assetsLibDir/rxjava-3.0.12.jar",
            "$assetsLibDir/reactive-streams-1.0.3.jar"
        )
    )
    @Test
    fun javaCompileWithExternalDep() {
        val results = javaCompiler.compile(externalDepTask)
        assert(results.size == 1)
        assertCompileResult(assetsJavaDir, results.first(), true)
    }

    private val classDepTask = CompileTask.singleFile("$assetsJavaDir/com/sickworm/intellij/aidp/test/JavaFileWithClassDep.java",
        classPathDir,
        dependencies = listOf(assetsClassDir)
    )
    @Test
    fun javaCompileWithClassDep() {
        val results = javaCompiler.compile(classDepTask)
        assert(results.size == 1)
        assertCompileResult(assetsJavaDir, results.first(), true)
    }

    private val androidHome = System.getenv("ANDROID_HOME")
    private val androidJar = "$androidHome/platforms/android-30/android.jar"
    private val intellijLibraryDir = "$assetsAndroidDir/.idea/libraries"
    private val activityTask = CompileTask.singleFile("$assetsJavaDir/com/example/myapplication/MainActivity2.java",
        classPathDir,
        dependencies = listOf(androidJar)
                + "$assetsAndroidDir/build/intermediates/javac/debug/classes"
                + IntellijLibraryConfigParser(File(intellijLibraryDir)).parse()!!
    )
    @Test
    fun javaCompileAndroidActivity() {
        if (!File(androidJar).exists()) {
            throw IllegalStateException("android sdk not found, search ANDROID_HOME: $androidHome, Android jar file: $androidJar")
        }
        val results = javaCompiler.compile(activityTask)
        assert(results.size == 1)
        assertCompileResult(assetsJavaDir, results.first(), true)
    }

    private val interdependenceTask = CompileTask(
        listOf(
            CompileFileInfo(File("$assetsJavaDir/com/sickworm/intellij/aidp/test/JavaFileWithInterdependence.java")),
            CompileFileInfo(File("$assetsJavaDir/com/sickworm/intellij/aidp/test/NewDep.java"))
        ),
        File(classPathDir))

    @Test
    fun javaCompileMultiFilesWithDep() {
        val results = javaCompiler.compile(interdependenceTask)
        assert(results.size == 2)
        results.forEach {
            assertCompileResult(assetsJavaDir, it, true)
        }
    }

    @Test
    fun javaCompileMultiFiles() {
        val compileTask = helloWorldTask + externalDepTask + classDepTask + activityTask + interdependenceTask
        val results = javaCompiler.compile(compileTask)

        assert(results.size == compileTask.files.size)

        results.forEach {
            assertCompileResult(assetsJavaDir, it, true)
        }
    }

    @Test
    fun javaCompileMultiFilesError() {
        val compileTask = helloWorldTask + errorTask + externalDepTask + classDepTask + activityTask + interdependenceTask
        val results = javaCompiler.compile(compileTask)

        assert(results.size == compileTask.files.size)

        results.forEach {
            if (errorTask.files[0] == it.file) {
                assertCompileResult(assetsJavaDir, it, false, 2)
            } else {
                assertCompileResult(assetsJavaDir, it, false, 0)
            }
        }
    }
}