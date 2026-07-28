package com.sickworm.intellij.jugg.compile

import com.sickworm.intellij.jugg.compiler.*
import com.sickworm.intellij.jugg.compiler.overlay.AssetOverlayCompiler
import com.sickworm.intellij.jugg.mock.*
import org.junit.Before
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AssetCompileTest {

    private val assetCompiler = AssetOverlayCompiler(context, mockParentDisposable)

    @Before
    fun init() {
        clearBuild()
    }

    val singleFileTask = CompileTask(
        listOf(
            CompileFile(CompileFile.Type.Asset, File(assetsAssetsDir, "logo.png"), assetsAssetsDir, mockModule),
        ),
        stagingDir
    )
    @Test
    fun singleFileCompile() {
        val task = singleFileTask
        val result = assetCompiler.compile(task)
        assertCompileResultAssets(task, result)
    }

    val multiFilesTask = CompileTask(
        listOf(
            CompileFile(CompileFile.Type.Asset, File(assetsAssetsDir, "logo.png"), assetsAssetsDir, mockModule),
            CompileFile(CompileFile.Type.Asset, File(assetsAssetsDir, "git/index"), assetsAssetsDir, mockModule),
        ),
        stagingDir
    )
    @Test
    fun multiFileCompile() {
        val task = multiFilesTask
        val result = assetCompiler.compile(task)
        assertCompileResultAssets(task, result)
    }

    @Test
    fun classpathResourceCompile() {
        val baseDir = File(assetsAndroidDir, "kmpCompose/src/commonMain/composeResources")
        val task = CompileTask(
            listOf(
                CompileFile(
                    CompileFile.Type.ClasspathResource,
                    File(baseDir, "values/strings.xml"),
                    baseDir,
                    mockModule,
                ),
            ),
            stagingDir,
        )

        val result = assetCompiler.compile(task)

        result.printCompileErrors()
        assertTrue(result.isAllSuccess)
        assertEquals(setOf("values/strings.xml"), result.outputs.map { it.relativeFile.path.replace('\\', '/') }.toSet())
        assertTrue(result.outputs.all { it.type == CompileOutput.Type.Asset })
    }

    private fun assertCompileResultAssets(task: CompileTask, result: CompileResult) {
        result.printCompileErrors()
        assertEquals(task, result.task)
        assertTrue(result.isAllSuccess)
        assertEquals(task.files.size, result.details.size)
        result.details.forEach { detail ->
            assertTrue(detail.isSuccess)
            assertTrue(detail.file.file.exists() && detail.file.file.length() > 0)
            val expectedOutputFile = detail.file.file.changeBaseDir(detail.file.baseDir, File(task.outputDir, "assets"))
            val output = result.outputs.find { it.file.absolutePath == expectedOutputFile.absolutePath }
            assertNotNull(output)
            assertEquals(CompileOutput.Type.Asset, output!!.type)
            assertTrue(output.file.exists())
            assertTrue(output.file.length() > 0)
        }
    }
}
