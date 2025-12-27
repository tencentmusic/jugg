package com.sickworm.intellij.jugg.compiler

import com.sickworm.intellij.jugg.compiler.source.SourceCompiler
import com.sickworm.intellij.jugg.mock.*
import com.sickworm.intellij.jugg.mock.TestGlobal.androidJar
import com.sickworm.intellij.jugg.mock.TestGlobal.assetsAndroidDir
import com.sickworm.intellij.jugg.mock.TestGlobal.mockModule
import org.junit.Before
import org.junit.Test
import java.io.File

class SourceCompileTest {

    private val sourceCompiler = SourceCompiler(TestGlobal.context, TestGlobal.mockParentDisposable)

    @Before
    fun init() {
        TestGlobal.clearBuild()
    }

    private val twoActivityTask = CompileTask(
        listOf(
            CompileFile(
                CompileFile.Type.Kotlin,
                File(assetsAndroidDir, "app/src/main/java/com/example/myapplication/MainActivity.kt"),
                File(assetsAndroidDir, "app/src/main/java/"),
                mockModule,
                dependencyPaths = listOf(androidJar.absolutePath)
                        + "$assetsAndroidDir/app/build/intermediates/javac/debug/classes"
                        + "$assetsAndroidDir/app/build/tmp/kotlin-classes/debug"
                        + "$assetsAndroidDir/app/build/intermediates/compile_and_runtime_not_namespaced_r_class_jar/debug/R.jar"
                        + LibraryParser().loadInTest()
            ),
            CompileFile(
                CompileFile.Type.Java,
                File(assetsAndroidDir, "app/src/main/java/com/example/myapplication/MainActivity2.java"),
                File(assetsAndroidDir, "app/src/main/java/"),
                mockModule,
                dependencyPaths = listOf(androidJar.absolutePath)
                        + "$assetsAndroidDir/app/build/intermediates/javac/debug/classes"
                        + "$assetsAndroidDir/app/build/tmp/kotlin-classes/debug"
                        + "$assetsAndroidDir/app/build/intermediates/compile_and_runtime_not_namespaced_r_class_jar/debug/R.jar"
                        + LibraryParser().loadInTest()
            )
        ),
        TestGlobal.stagingDir,
        CompileStatusHolder.DEFAULT,
    )

    @Test
    fun kotlinAndJavaCompile() {
        val task = twoActivityTask
        val result = sourceCompiler.compile(task)
        assertCompileResult(task, result)
    }


    @Test
    fun kotlinAndJavaCoreRefrenceCompile() {
        // test cross-reference case (kotlinc -java-source-roots)

        changeAndRevert(
            "MainActivity2.crossReference.java" to "MainActivity2.java",
            "MainActivity.crossReference.kt" to "MainActivity.kt",
        ) { _ ->
            val task = twoActivityTask
            val result = sourceCompiler.compile(task)
            assertCompileResult(task, result)
        }
    }

    private fun assertCompileResult(task: CompileTask, result: CompileResult) {
        val mapper: OutputFileMapper = { _ ->
            emptyList()
        }
        assertCompileResult(task, result, mapper)
    }
}