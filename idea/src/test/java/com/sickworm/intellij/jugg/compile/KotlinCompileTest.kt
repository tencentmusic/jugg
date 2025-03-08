package com.sickworm.intellij.jugg.compile

import com.jetbrains.rd.util.first
import com.sickworm.intellij.jugg.IntellijLibraryConfigParserTest
import com.sickworm.intellij.jugg.compiler.*
import com.sickworm.intellij.jugg.compiler.source.KotlinCompiler
import com.sickworm.intellij.jugg.compiler.source.KotlinCompilerOutputParser
import com.sickworm.intellij.jugg.mock.*
import org.junit.Before
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class KotlinCompileTest {

    private val kotlinCompiler = KotlinCompiler(context, mockParentDisposable)

    @Before
    fun init() {
        clearBuild()
    }

    private val resultTask = CompileTask(
        listOf(
            CompileFile(
                CompileFile.Type.Kotlin,
                File("$assetsKotlinDir/com/sickworm/intellij/jugg/test/Result.kt"),
                assetsKotlinDir,
                mockModule,
                dependencyPaths = listOf("$assetsLibDir/kotlin-stdlib-1.3.72.jar")
            )
        ),
        stagingDir
    )
    @Test
    fun kotlinCompile() {
        val task = resultTask
        val result = kotlinCompiler.compile(task)
        assertCompileResultKotlin(task, result, "Companion")
    }

    private val activityTask = CompileTask(
        listOf(
            CompileFile(
            CompileFile.Type.Kotlin,
            File(assetsAndroidDir, "app/src/main/java/com/example/myapplication/MainActivity.kt"),
            File(assetsAndroidDir, "app/src/main/java/"),
            mockModule,
            dependencyPaths = listOf(androidJar.absolutePath)
                    + "$assetsAndroidDir/app/build/intermediates/javac/debug/classes"
                    + "$assetsAndroidDir/app/build/intermediates/compile_and_runtime_not_namespaced_r_class_jar/debug/R.jar"
                    + IntellijLibraryConfigParserTest().loadLibraryConfigInTest()!!
        )
        ),
        stagingDir,
    )
    @Test
    fun kotlinProjectCompile() {
        val task = activityTask
        val result = kotlinCompiler.compile(task)
        assertCompileResultKotlin(task, result)
    }

    @Test
    fun kotlinSmartCastCompile() {
        val task = CompileTask(
            listOf(
                CompileFile(
                    CompileFile.Type.Kotlin,
                    File(assetsAndroidDir, "app/src/main/java/com/sickworm/jugg/demo/testcase/ktsmartcast/ImplClass1.kt"),
                    File(assetsAndroidDir, "app/src/main/java/"),
                    mockModule,
                    dependencyPaths = IntellijLibraryConfigParserTest().loadLibraryConfigInTest()!!,
                )
            ),
            stagingDir,
        )
        val result = kotlinCompiler.compile(task)
        assertCompileResultKotlin(task, result)
    }

    @Test
    fun kotlinInternalCompile() {
        val task = CompileTask(
            listOf(
                CompileFile(
                    CompileFile.Type.Kotlin,
                    File(assetsAndroidDir, "app/src/main/java/com/sickworm/jugg/demo/testcase/ktinternal/InvokeClass1.kt"),
                    File(assetsAndroidDir, "app/src/main/java/"),
                    mockModule,
                    dependencyPaths = IntellijLibraryConfigParserTest().loadLibraryConfigInTest()!!,
                )
            ),
            stagingDir,
        )
        val result = kotlinCompiler.compile(task)
        assertCompileResultKotlin(task, result)
    }

    @Test
    fun kotlinCompileWithARouter() {
        val task = CompileTask(
            listOf(
                CompileFile(CompileFile.Type.Kotlin,
                    File(assetsAndroidDir, "app/src/main/java/com/example/myapplication/MainActivity.kt"),
                    File(assetsAndroidDir, "app/src/main/java"),
                    context.modules.first().value,
                )
            ),
            stagingDir,
        )
        val result = kotlinCompiler.compile(task)

        val mapper: OutputFileMapper = {
            val outputFile = it.file.changeBaseDir(it.baseDir, task.outputDir, "class")
            listOf(CompileOutput(CompileOutput.Type.Class, outputFile, task.outputDir))
        }
        assertCompileResult(task, result, mapper)

        listOf(
            "ARouter\$\$Group\$\$app.class",
            "ARouter\$\$Root\$\$app.class",
            "ARouter\$\$Providers\$\$app.class",
            "ARouter\$\$Group\$\$app.java",
            "ARouter\$\$Root\$\$app.java",
            "ARouter\$\$Providers\$\$app.java"
        ).forEach { outputFileName ->
            assertTrue(result.outputs.any { it.file.name == outputFileName }, "missing $outputFileName, " +
                    "all are:\n${result.outputs.joinToString("\n") { it.file.name }}")
        }
    }

    @Test
    fun kotlinAnnotationParcelizeCompile() {
        val task = createTask("com/sickworm/jugg/demo/testcase/annotation/kotlin/ParcelizeData.kt")
        val result = kotlinCompiler.compile(task)
        assertCompileResult(task, result, mapper)

        val task2 = createTask("com/sickworm/jugg/demo/testcase/annotation/kotlin/ParcelizeData2.kt")
        val result2 = kotlinCompiler.compile(task2)
        assertCompileResult(task2, result2, mapper)
    }

    @Test
    fun kotCompilerWithCompose() {
        test1()
        test1()
    }

    private fun test1() {
        val task = createTask("com/sickworm/jugg/demo/testcase/compose/MainComposeActivity.kt")
        val result = kotlinCompiler.compile(task)
        assertCompileResult(task, result, mapper)
    }

    @Test
    fun testMetadataError() {
        val message = "/Users/sickworm/MyApplication/build/jugg/classpath/root/MyApplication/app/build/tmp/kotlin-classes/" +
                "debug/META-INF/app_debug.kotlin_module: error: module was compiled with an incompatible version of Kotlin." +
                "The binary version of its metadata is 1.7.0, expected version is 1.1.16."
        assertTrue(KotlinCompilerOutputParser.MetadataVersionError.isMyError(message))
        val metadataVersionError = KotlinCompilerOutputParser.MetadataVersionError.create(message)
        assertNotNull(metadataVersionError)
        assertEquals(message, metadataVersionError.message)
        assertEquals(
            "/Users/sickworm/MyApplication/build/jugg/classpath/root/MyApplication/app/build/tmp/kotlin-classes/debug/META-INF/app_debug.kotlin_module",
            metadataVersionError.metadataFile.path)
        assertEquals("1.7.0", metadataVersionError.actualVersion)
        assertEquals("1.1.16", metadataVersionError.expectVersion)
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

    companion object {

        val mapper: OutputFileMapper = {
            val outputFile = it.file.changeBaseDir(it.baseDir, stagingDir, "class")
            listOf(CompileOutput(CompileOutput.Type.Class, outputFile, stagingDir))
        }

        private fun createTask(path: String): CompileTask {
            return CompileTask(listOf(ktCompileFile(path)), stagingDir)
        }

        private fun ktCompileFile(path: String): CompileFile {
            return CompileFile(CompileFile.Type.Kotlin,
                File(assetsAndroidDir, "app/src/main/java/$path"),
                File(assetsAndroidDir, "app/src/main/java"),
                context.modules.first().value,
            )
        }
    }
}