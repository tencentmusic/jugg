package com.sickworm.intellij.aidp

import com.sickworm.intellij.aidp.compiler.CompileFile
import com.sickworm.intellij.aidp.compiler.CompileTask
import com.sickworm.intellij.aidp.compiler.JavaCompiler
import com.sickworm.intellij.aidp.compiler.file
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

    private val helloWorldTask = CompileTask.singleJavaFile(File(assetsJavaDir, "com/sickworm/intellij/aidp/test/HelloWorldJavaFile.java"), classPathDir)
    @Test
    fun javaCompile() {
        val result = javaCompiler.compile(helloWorldTask)
        assert(result.details.size == 1)
        assertCompileResult(assetsJavaDir, result.details.first(), true)
    }

    private val errorTask = CompileTask.singleJavaFile(File(assetsJavaDir, "com/sickworm/intellij/aidp/test/ErrorJavaFile.java"), classPathDir)
    @Test
    fun javaCompileError() {
        val result = javaCompiler.compile(errorTask)
        assert(result.details.size == 1)
        assertCompileResult(assetsJavaDir, result.details.first(), false, 2)
    }

    private val externalDepTask = CompileTask.singleJavaFile(File(assetsJavaDir, "com/sickworm/intellij/aidp/test/JavaFileWithExternalDep.java"),
        classPathDir,
        dependencies = listOf(
            "$assetsLibDir/rxjava-3.0.12.jar",
            "$assetsLibDir/reactive-streams-1.0.3.jar"
        )
    )
    @Test
    fun javaCompileWithExternalDep() {
        val result = javaCompiler.compile(externalDepTask)
        assert(result.details.size == 1)
        assertCompileResult(assetsJavaDir, result.details.first(), true)
    }

    private val classDepTask = CompileTask.singleJavaFile(File(assetsJavaDir, "com/sickworm/intellij/aidp/test/JavaFileWithClassDep.java"),
        classPathDir,
        dependencies = listOf(assetsClassDir.absolutePath)
    )
    @Test
    fun javaCompileWithClassDep() {
        val result = javaCompiler.compile(classDepTask)
        assert(result.details.size == 1)
        assertCompileResult(assetsJavaDir, result.details.first(), true)
    }

    private val activityTask = CompileTask.singleJavaFile(File(assetsJavaDir, "com/example/myapplication/MainActivity2.java"),
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
        val result = javaCompiler.compile(activityTask)
        assert(result.details.size == 1)
        assertCompileResult(assetsJavaDir, result.details.first(), true)
    }

    private val interdependenceTask = CompileTask(
        listOf(
            CompileFile(File(assetsJavaDir, "com/sickworm/intellij/aidp/test/JavaFileWithInterdependence.java"), CompileFile.Type.Java, assetsJavaDir),
            CompileFile(File(assetsJavaDir, "com/sickworm/intellij/aidp/test/NewDep.java"), CompileFile.Type.Java, assetsJavaDir)
        ),
        classPathDir)

    @Test
    fun javaCompileMultiFilesWithDep() {
        val result = javaCompiler.compile(interdependenceTask)
        assert(result.details.size == 2)
        result.details.forEach {
            assertCompileResult(assetsJavaDir, it, true)
        }
    }

    @Test
    fun javaCompileMultiFiles() {
        val compileTask = helloWorldTask + externalDepTask + classDepTask + activityTask + interdependenceTask
        val result = javaCompiler.compile(compileTask)

        assert(result.details.size == compileTask.files.size)
        result.details.forEach {
            assertCompileResult(assetsJavaDir, it, true)
        }
    }

    @Test
    fun javaCompileMultiFilesError() {
        val compileTask = helloWorldTask + errorTask + externalDepTask + classDepTask + activityTask + interdependenceTask
        val result = javaCompiler.compile(compileTask)

        assert(result.details.size == compileTask.files.size)
        result.details.forEach {
            if (errorTask.files[0] == it.file) {
                assertCompileResult(assetsJavaDir, it, false, 2)
            } else {
                assertCompileResult(assetsJavaDir, it, false, 0)
            }
        }
    }
}