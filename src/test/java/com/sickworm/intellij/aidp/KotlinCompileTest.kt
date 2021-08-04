package com.sickworm.intellij.aidp

import com.sickworm.intellij.aidp.compiler.*
import com.sickworm.intellij.aidp.compiler.source.KotlinCompiler
import org.junit.Before
import org.junit.Test
import java.io.File

class KotlinCompileTest {

    private val kotlinCompiler = KotlinCompiler(logger)

    @Before
    fun init() {
        clearBuild()
    }

    private val resultTask = CompileTask(
        listOf(
            CompileFile(
                CompileFile.Type.Kotlin,
                File("$assetsKotlinDir/com/sickworm/intellij/aidp/test/Result.kt"),
                assetsKotlinDir,
                listOf("$assetsLibDir/kotlin-stdlib-1.3.72.jar")
            )
        ),
        stagingDir)
    @Test
    fun kotlinCompile() {
        val task = resultTask
        val result = kotlinCompiler.compile(task)
        assertCompileResultKotlin(task, result, "Companion")
    }

    private val activityTask = CompileTask(
        listOf(CompileFile(
            CompileFile.Type.Kotlin,
            File(assetsAndroidDir, "app/src/main/java/com/example/myapplication/MainActivity.kt"),
            File(assetsAndroidDir, "app/src/main/java/"),
            dependencyPaths = listOf(androidJar.absolutePath)
                    + "$assetsAndroidDir/app/build/intermediates/javac/debug/classes"
                    + IntellijLibraryConfigParserTest().loadLibraryConfigInTest()!!
        )),
        stagingDir,
    )
    @Test
    fun kotlinProjectCompile() {
        val task = activityTask
        val result = kotlinCompiler.compile(task)
        assertCompileResultKotlin(task, result)
    }

    private fun assertCompileResultKotlin(task: CompileTask, result: CompileResult, vararg subclassList: String) {
        val mapper: OutputFileMapper = { file ->
            (subclassList.toList() + "").map {
                val subName = if (it.isEmpty()) "" else "$$it"
                file.file.changeBaseDir(file.baseDir, task.outputDir, newName = "${file.file.nameWithoutExtension}$subName.class")
            }.map {
                CompileOutput(CompileOutput.Type.Class, it, task.outputDir)
            }
        }
        assertCompileResult(task, result, mapper)
    }
}