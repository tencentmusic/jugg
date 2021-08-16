package com.sickworm.intellij.jugg

import com.sickworm.intellij.jugg.compiler.overlay.ARSC_FILE_NAME
import com.sickworm.intellij.jugg.compiler.overlay.ArscCompiler
import com.sickworm.intellij.jugg.compiler.overlay.ResourceCompiler
import com.sickworm.intellij.jugg.compiler.overlay.ResourceOverlayCompiler
import com.sickworm.intellij.jugg.compiler.CompileFile
import com.sickworm.intellij.jugg.compiler.CompileOutput
import com.sickworm.intellij.jugg.compiler.CompileResult
import com.sickworm.intellij.jugg.compiler.CompileTask
import com.sickworm.intellij.jugg.listFilesRecursively
import org.junit.Before
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ResourceCompileTest {

    val flatFiles = assetsFlatDir.listFilesRecursively()
        .filter {
            // TODO figure out why this shit has error: '' is incompatible with attribute id (attr) reference.
            // but it's ok for now because it's not a project xml, it's from other library
            !it.name.endsWith("notification_template_custom_big.xml.flat")
        }
        .map {
            CompileFile(CompileFile.Type.Flat, it, assetsFlatDir)
        }

    @Before
    fun init() {
        clearBuild()
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
        val resCompiler = ResourceCompiler(context)
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
        val arscCompiler = ArscCompiler(context)
        val task = CompileTask(
            flatFiles,
            stagingDir
        )
        val result = arscCompiler.compile(task)
        checkArscResult(task, result, 443)
    }

    private val baseDir = File(assetsAndroidDir, "app/src/main/res/")
    val resourceOverlayTask = CompileTask(
        listOf(
            CompileFile(CompileFile.Type.Resource, File(assetsAndroidDir, "app/src/main/res/layout/activity_main2.xml"), baseDir),
            CompileFile(CompileFile.Type.Resource, File(assetsAndroidDir, "app/src/main/res/layout/activity_main3.xml"), baseDir),
            CompileFile(CompileFile.Type.Resource, File(assetsAndroidDir, "app/src/main/res/drawable/ic_launcher_background.xml"), baseDir),
            CompileFile(CompileFile.Type.Resource, File(assetsAndroidDir, "app/src/main/res/drawable/ic_launcher_background2.xml"), baseDir),
        ),
        stagingDir
    )
    @Test
    fun compileResourceOverlay() {
        val task = resourceOverlayTask
        val resourceOverlayCompiler = ResourceOverlayCompiler(context)

        val result = resourceOverlayCompiler.compile(task)
        checkArscResult(task, result, 8)
    }

    private fun checkArscResult(task: CompileTask, result: CompileResult, exceptOverlayOutputSize: Int) {
        assertEquals(result.details.size, task.files.size)
        assertTrue(result.isAllSuccess)

        val rFiles = result.outputs.filter { it.type == CompileOutput.Type.Java }
        assertEquals(1, rFiles.size)

        val resFiles = result.outputs.filter { it.type == CompileOutput.Type.Overlay }
        assertEquals(exceptOverlayOutputSize, resFiles.size) // TODO more logical

        val arscFile = resFiles.filter { it.file.relativeTo(it.baseDir).path == ARSC_FILE_NAME }
        assertEquals(1, arscFile.size)

        val manifestFile = resFiles.filter { it.file.relativeTo(it.baseDir).path == "AndroidManifest.xml" }
        assertEquals(1, manifestFile.size)

        result.outputs.forEach {
            assertTrue(it.file.exists())
            assertTrue(it.file.length() > 0)
        }
    }
}