package com.sickworm.intellij.jugg.compile

import com.sickworm.intellij.jugg.compiler.*
import com.sickworm.intellij.jugg.compiler.overlay.ARSC_FILE_NAME
import com.sickworm.intellij.jugg.compiler.overlay.ArscCompiler
import com.sickworm.intellij.jugg.compiler.overlay.ResourceCompiler
import com.sickworm.intellij.jugg.compiler.overlay.ResourceOverlayCompiler
import com.sickworm.intellij.jugg.mock.*
import org.junit.Before
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ResourceCompileTest {

    private val flatFiles = assetsFlatDir.listFilesRecursively()
        .map {
            CompileFile(CompileFile.Type.Flat, it, assetsFlatDir, mockModule)
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
        val resCompiler = ResourceCompiler(context, mockParentDisposable)
        val task = CompileTask(
            files.map { CompileFile(CompileFile.Type.Resource, it, baseDir, mockModule) },
            stagingDir
        )
        val result = resCompiler.compile(task)
        assertEquals(result.details.size, files.size)
        assertTrue(result.isAllSuccess)
        assertEquals(result.outputs.size, files.size)
        result.outputs.forEach {
            assertEquals(it.type, CompileOutput.Type.Res)
            assertTrue(it.file.exists())
            assertTrue(it.file.length() > 0)
        }
    }

    @Test
    fun compileArsc() {
        val arscCompiler = ArscCompiler(context, mockParentDisposable)
        val task = CompileTask(
            flatFiles,
            stagingDir
        )
        val result = arscCompiler.compile(task)
        checkArscResult(task, result, 426, isRJavaChanged = false)
    }

    private val baseDir = File(assetsAndroidDir, "app/src/main/res/")
    val resourceOverlayTask = CompileTask(
        listOf(
            CompileFile(CompileFile.Type.Resource, File(assetsAndroidDir, "app/src/main/res/layout/activity_main2.xml"), baseDir, mockModule),
            CompileFile(CompileFile.Type.Resource, File(assetsAndroidDir, "app/src/main/res/layout/activity_main3.xml"), baseDir, mockModule),
            CompileFile(CompileFile.Type.Resource, File(assetsAndroidDir, "app/src/main/res/drawable/ic_launcher_background.xml"), baseDir, mockModule),
            CompileFile(CompileFile.Type.Resource, File(assetsAndroidDir, "app/src/main/res/drawable/ic_launcher_background2.xml"), baseDir, mockModule),
        ),
        stagingDir
    )
    @Test
    fun compileResourceOverlay() {
        val task = resourceOverlayTask
        val resourceOverlayCompiler = ResourceOverlayCompiler(context, mockParentDisposable)

        val result = resourceOverlayCompiler.compile(task)
        checkArscResult(task, result, 6, isRJavaChanged = false)
    }

    /**
     * 1. Since LOLLIPOP MR1, the framework can handle silently ignoring unknown public attributes.
     * So aapt2 will create a v22 config if the resource file attributes that api level < min sdk level.
     *
     * 2. Using the w600dp qualifier automatically includes the v13 qualifier
     * because the available width qualifiers are new in API level 13.
     */
    @Test
    fun compileResourceOverlayWithAttrRules() {
        val layoutFile = CompileFile(CompileFile.Type.Resource,
            File(assetsAndroidDir, "app/src/main/res/layout/test_layout.xml"),
            File(assetsAndroidDir, "app/src/main/res"), mockModule)
        val layoutV22File = CompileFile(CompileFile.Type.Resource,
            File(assetsAndroidDir, "app/src/main/res/layout-v22/test_layout.xml"),
            File(assetsAndroidDir, "app/src/main/res"), mockModule)
        val newLayoutFile = CompileFile(CompileFile.Type.Resource,
            File(assetsAndroidModifySourceDir, "app/src/main/res/layout/test_layout2.xml"),
            File(assetsAndroidModifySourceDir, "app/src/main/res"), mockModule)
        val layoutW600DpFile = CompileFile(CompileFile.Type.Resource,
            File(assetsAndroidDir, "app/src/main/res/layout-w600dp/test_layout.xml"),
            File(assetsAndroidDir, "app/src/main/res"), mockModule)

        val resourceOverlayCompiler = ResourceOverlayCompiler(context, mockParentDisposable)
        var task = CompileTask(
            listOf(layoutFile),
            stagingDir
        )
        stagingDir.clearDir()
        var result = resourceOverlayCompiler.compile(task)
        checkArscResult(task, result, 1, isRJavaChanged = false)

        task = CompileTask(
            listOf(layoutV22File),
            stagingDir
        )
        stagingDir.clearDir()
        result = resourceOverlayCompiler.compile(task)
        checkArscResult(task, result, 1, isRJavaChanged = false)

        task = CompileTask(
            listOf(layoutFile, layoutV22File),
            stagingDir
        )
        stagingDir.clearDir()
        result = resourceOverlayCompiler.compile(task)
        checkArscResult(task, result, 2, isRJavaChanged = false)

        task = CompileTask(
            listOf(newLayoutFile),
            stagingDir
        )
        stagingDir.clearDir()
        result = resourceOverlayCompiler.compile(task)
        checkArscResult(task, result, 2, isRJavaChanged = true)

        task = CompileTask(
            listOf(layoutW600DpFile),
            stagingDir
        )
        stagingDir.clearDir()
        result = resourceOverlayCompiler.compile(task)
        checkArscResult(task, result, 1, isRJavaChanged = false)

        task = CompileTask(
            listOf(layoutFile, layoutW600DpFile),
            stagingDir
        )
        stagingDir.clearDir()
        result = resourceOverlayCompiler.compile(task)
        checkArscResult(task, result, 2, isRJavaChanged = false)
    }


    /**
     * 3. If the resource file contains high level attr before and deleted now, we should still create v22 config
     * to override the old v22 config. Because appt2-inclink can not delete entry.
     */
    @Test
    fun compileResourceOverlayWithAttrRules2() {
        val layoutNoHighLevelAttrFile = CompileFile(CompileFile.Type.Resource,
            File(assetsAndroidModifySourceDir, "app/src/main/res/layout/test_layout3.xml"),
            File(assetsAndroidModifySourceDir, "app/src/main/res"), mockModule)

        val resourceOverlayCompiler = ResourceOverlayCompiler(context, mockParentDisposable)
        val task = CompileTask(
            listOf(layoutNoHighLevelAttrFile),
            stagingDir
        )
        stagingDir.clearDir()
        val result = resourceOverlayCompiler.compile(task)
        checkArscResult(task, result, 2, isRJavaChanged = false)
    }

    /**
     * 4. A bug for aapt-inclink: .../app/src/main/res/layout/test_layout2.xml: error: file not found.
     * Happens when compile a new layout xml at the second time.
     */
    @Test
    fun compileResourceOverlayWithAttrRules3() {
        val newLayoutFile = CompileFile(CompileFile.Type.Resource,
            File(assetsAndroidModifySourceDir, "app/src/main/res/layout/test_layout2.xml"),
            File(assetsAndroidModifySourceDir, "app/src/main/res"), mockModule)

        val resourceOverlayCompiler = ResourceOverlayCompiler(context, mockParentDisposable)
        var task = CompileTask(
            listOf(newLayoutFile),
            stagingDir
        )
        stagingDir.clearDir()
        var result = resourceOverlayCompiler.compile(task)
        checkArscResult(task, result, 2, isRJavaChanged = true)

        task = CompileTask(
            listOf(newLayoutFile),
            stagingDir
        )
        stagingDir.clearDir()
        result = resourceOverlayCompiler.compile(task)
        checkArscResult(task, result, 2, isRJavaChanged = false)
    }

    val resourceOverlayAddIdsTask = CompileTask(
        listOf(
            CompileFile(CompileFile.Type.Resource,
                File(assetsAndroidModifySourceDir, "app/src/main/res/layout/activity_main.xml"),
                File(assetsAndroidModifySourceDir, "app/src/main/res"),
                mockModule),
        ),
        stagingDir
    )

    @Test
    fun compileAddIdsLayout() {
        val resourceOverlayCompiler = ResourceOverlayCompiler(context, mockParentDisposable)

        val result = resourceOverlayCompiler.compile(resourceOverlayAddIdsTask)

        checkArscResult(resourceOverlayAddIdsTask, result, 1, isRJavaChanged = true)

        val rFiles = result.outputs.filter { it.type == CompileOutput.Type.Java }
        assertTrue(rFiles.first().file.readText().contains("button999"))
    }

    @Test
    fun compileAddValues() {
        val task = CompileTask(
            listOf("attrs.xml", "arrays.xml", "colors.xml", "ids.xml", "strings.xml", "styles.xml").map {
                CompileFile(CompileFile.Type.Resource,
                    File(assetsAndroidModifySourceDir, "app/src/main/res/values/$it"),
                    File(assetsAndroidModifySourceDir, "app/src/main/res"),
                    mockModule)
            },
            stagingDir
        )
        val resourceOverlayCompiler = ResourceOverlayCompiler(context, mockParentDisposable)

        val result = resourceOverlayCompiler.compile(task)

        checkArscResult(task, result, 0, isRJavaChanged = true)

        val containsIds = task.files.flatMap { file ->
            file.file.readLines().mapNotNull {
                // matches name="$1"
                Regex("name=\"([^\"]+)\"").find(it)?.groupValues?.get(1)
            }
        }
        println(containsIds)

        val rFiles = result.outputs.filter { it.type == CompileOutput.Type.Java }
        val rFileText = rFiles.first().file.readText()
        containsIds.forEach {
            assertTrue(rFileText.contains(it))
        }
    }

    private fun checkArscResult(task: CompileTask, result: CompileResult, exceptOverlayOutputSize: Int, isRJavaChanged: Boolean) {
        assertEquals(result.details.size, task.files.size)
        assertTrue(result.isAllSuccess)

        val rFiles = result.outputs.filter { it.type == CompileOutput.Type.Java }
        if (isRJavaChanged) {
            assertEquals(1, rFiles.size)
        } else {
            assertEquals(0, rFiles.size)
        }

        val resFiles = result.outputs.filter { it.type == CompileOutput.Type.Res }
        assertEquals(exceptOverlayOutputSize, resFiles.size - 2) // arsc and manifest are not included

        val arscFile = resFiles.filter { it.relativeFile.path == ARSC_FILE_NAME }
        assertEquals(1, arscFile.size)

        val manifestFile = resFiles.filter { it.relativeFile.path == "AndroidManifest.xml" }
        assertEquals(1, manifestFile.size)

        result.outputs.forEach {
            assertTrue(it.file.exists())
            assertTrue(it.file.length() > 0)
        }
    }
}