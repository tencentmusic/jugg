package com.sickworm.intellij.aidp

import com.sickworm.intellij.aidp.compiler.*
import org.junit.Before
import org.junit.Test
import java.io.File

class ResourceCompileTest {

    val flatDir = File(buildDir, "flat")
    val stableIds: File = File("src/test/build/stableIds.txt").absoluteFile
    val manifest: File = File("src/test/assets/android/build/intermediates/merged_manifests/debug/AndroidManifest.xml").absoluteFile

    @Before
    fun init() {
        clearBuild()
        val sourceFlatDir = File(assetsAndroidDir, "build/intermediates/res/merged/debug")
        sourceFlatDir.copyRecursively(flatDir)

        val sourceStableIds = File("src/test/assets/android/stableIds.txt").absoluteFile
        sourceStableIds.copyTo(stableIds)
    }

    @Test
    fun compileResLayout() {
        val file = File(assetsAndroidDir, "src/main/res/layout/activity_main.xml")
        val baseDir = File(assetsAndroidDir, "src/main/res/")
        compileRes(listOf(file), baseDir)
    }

    @Test
    fun compileResAll() {
        val baseDir = File(assetsAndroidDir, "src/main/res/")
        val files = baseDir.listFilesRecursively()
        compileRes(files, baseDir)
    }

    private fun compileRes(files: List<File>, baseDir: File) {
        val resCompiler = ResourceCompiler(logger)
        val task = CompileTask(
            files.map { CompileFile(CompileFile.Type.Resource, it, baseDir) },
            stagingDir
        )
        val result = resCompiler.compile(task)
        assert(result.details.size == files.size)
        assert(result.isAllSuccess)
        assert(result.outputs.size == files.size)
        result.outputs.forEach {
            assert(it.type == CompileOutput.Type.Flat)
            assert(it.file.exists())
            assert(it.file.length() > 0)
        }
    }

    @Test
    fun compileArsc() {
        val arscCompiler = ArscCompiler(stableIds, manifest, androidJar, logger)
        val task = CompileTask(
            listOf(CompileFile(CompileFile.Type.FlatDir, flatDir, flatDir)),
            stagingDir
        )
        val result = arscCompiler.compile(task)
        checkArscResult(task, result)
    }

    private val baseDir = File(assetsAndroidDir, "src/main/res/")
    val cachedArscTask = CompileTask(
        listOf(
            CompileFile(CompileFile.Type.Resource, File(assetsAndroidDir, "src/main/res/layout/activity_main2.xml"), baseDir),
            CompileFile(CompileFile.Type.Resource, File(assetsAndroidDir, "src/main/res/layout/activity_main3.xml"), baseDir),
            CompileFile(CompileFile.Type.Resource, File(assetsAndroidDir, "src/main/res/drawable/ic_launcher_background.xml"), baseDir),
            CompileFile(CompileFile.Type.Resource, File(assetsAndroidDir, "src/main/res/drawable/ic_launcher_background2.xml"), baseDir),
        ),
        stagingDir
    )
    @Test
    fun compileCachedArsc() {
        val task = cachedArscTask
        val arscCompiler = CachedArscCompiler(
            flatDir,
            stableIds,
            manifest,
            androidJar,
            logger
        )

        val result = arscCompiler.compile(task)
        checkArscResult(task, result)
    }

    private fun checkArscResult(task: CompileTask, result: CompileResult) {
        assert(result.details.size == task.files.size)
        assert(result.isAllSuccess)
        assert(result.outputs.size == 2)
        assert(result.outputs[0].type == CompileOutput.Type.Overlay)
        assert(result.outputs[1].type == CompileOutput.Type.Java)
        result.outputs.forEach {
            assert(it.file.exists())
            assert(it.file.length() > 0)
        }
    }
}