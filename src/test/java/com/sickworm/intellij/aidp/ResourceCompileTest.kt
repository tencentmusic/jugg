package com.sickworm.intellij.aidp

import com.sickworm.intellij.aidp.compiler.*
import org.junit.Before
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ResourceCompileTest {

    val flatDir = File(buildDir, "flat")
    val stableIds: File = File("src/test/build/stableIds.txt").absoluteFile
    val manifest: File = File("$assetsAndroidDir/app/build/intermediates/merged_manifests/debug/AndroidManifest.xml").absoluteFile

    @Before
    fun init() {
        clearBuild()
        val sourceFlatDir = File(assetsAndroidDir, "app/build/intermediates/res/merged/debug")
        sourceFlatDir.copyRecursively(flatDir)

        val sourceStableIds = File("src/test/assets/android/stableIds.txt").absoluteFile
        sourceStableIds.copyTo(stableIds)
    }

    @Test
    fun compileResLayout() {
        val file = File(assetsAndroidDir, "app/src/main/res/layout/activity_main.xml")
        val baseDir = File(assetsAndroidDir, "app/src/main/res/")
        compileRes(listOf(file), baseDir)
    }

    @Test
    fun compileResAll() {
        val baseDir = File(assetsAndroidDir, "app/src/main/res/")
        val files = baseDir.listFilesRecursively()
        compileRes(files, baseDir)
    }

    private fun compileRes(files: List<File>, baseDir: File) {
        val resCompiler = ResourceCompiler(androidBuildTools, logger)
        val task = CompileTask(
            files.map { CompileFile(CompileFile.Type.Resource, it, baseDir) },
            stagingDir
        )
        val result = resCompiler.compile(task)
        assertEquals(result.details.size, files.size)
        assertTrue(result.isAllSuccess)
        assertEquals(result.outputs.size, files.size)
        result.outputs.forEach {
            assertEquals(it.type, CompileOutput.Type.Overlay)
            assertTrue(it.file.exists())
            assertTrue(it.file.length() > 0)
        }
    }

    @Test
    fun compileArsc() {
        val arscCompiler = ArscCompiler(stableIds, manifest, androidJar, androidBuildTools, logger)
        val task = CompileTask(
            listOf(CompileFile(CompileFile.Type.FlatDir, flatDir, flatDir)),
            stagingDir
        )
        val result = arscCompiler.compile(task)
        checkArscResult(task, result)
    }

    private fun checkArscResult(task: CompileTask, result: CompileResult) {
        assertEquals(result.details.size, task.files.size)
        assertTrue(result.isAllSuccess)
        assertTrue(result.outputs.size == 2)
        assertEquals(result.outputs[0].type, CompileOutput.Type.Overlay)
        assertEquals(result.outputs[1].type, CompileOutput.Type.Java)
        result.outputs.forEach {
            assertTrue(it.file.exists())
            assertTrue(it.file.length() > 0)
        }
    }

    private val baseDir = File(assetsAndroidDir, "app/src/main/res/")
    val cachedArscTask = CompileTask(
        listOf(
            CompileFile(CompileFile.Type.Resource, File(assetsAndroidDir, "app/src/main/res/layout/activity_main2.xml"), baseDir),
            CompileFile(CompileFile.Type.Resource, File(assetsAndroidDir, "app/src/main/res/layout/activity_main3.xml"), baseDir),
            CompileFile(CompileFile.Type.Resource, File(assetsAndroidDir, "app/src/main/res/drawable/ic_launcher_background.xml"), baseDir),
            CompileFile(CompileFile.Type.Resource, File(assetsAndroidDir, "app/src/main/res/drawable/ic_launcher_background2.xml"), baseDir),
        ),
        stagingDir
    )
    @Test
    fun compileCachedArsc() {
        val task = cachedArscTask
        val arscCompiler = ResourceOverlayCompiler(
            flatDir,
            stableIds,
            manifest,
            androidJar,
            androidBuildTools,
            logger
        )

        val result = arscCompiler.compile(task)
        checkResourceOverlayResult(task, result)
    }

    private fun checkResourceOverlayResult(task: CompileTask, result: CompileResult) {
        assertEquals(result.details.size, task.files.size)
        assertTrue(result.isAllSuccess)
        assertEquals(result.outputs.size, 2 + task.files.size)

        val arscFile = result.outputs.find { it.file.name == "resources.arsc" }
        assertEquals(
            CompileOutput(
                CompileOutput.Type.Overlay,
                File(task.outputDir, "resources.arsc"),
                task.outputDir
            ),
            arscFile
        )

        val rFile = result.outputs.find { it.file.name == "R.java" }
        assertEquals(
            CompileOutput(
                CompileOutput.Type.Java,
                File(task.outputDir, "rjava/com/example/myapplication/R.java"),
                task.outputDir
            ),
            rFile
        )

        task.files.forEach { compileFile ->
            val source = compileFile.file
            val outputFile = File(task.outputDir, "res/${source.parentFile.name}_${source.name}")
            val compileOutput = CompileOutput(
                CompileOutput.Type.Overlay,
                outputFile,
                task.outputDir
            )
            val relativeOutput = result.outputs.find { it.file.name == outputFile.name }
            assertEquals(compileOutput, relativeOutput)
        }
    }
}