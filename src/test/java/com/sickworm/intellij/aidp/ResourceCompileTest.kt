package com.sickworm.intellij.aidp

import com.sickworm.intellij.aidp.compiler.*
import org.junit.Before
import org.junit.Test
import java.io.File

class ResourceCompileTest {

    private val flatDir = tempCompileDir

    private val stableIds = File("src/test/build/stableIds.txt").absoluteFile
    private val manifest = File("src/test/assets/android/build/intermediates/merged_manifests/debug/AndroidManifest.xml").absoluteFile

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
            files.map { CompileFile(it, CompileFile.Type.Resource, baseDir) },
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
            listOf(CompileFile(flatDir, CompileFile.Type.FlatDir, flatDir)),
            stagingDir
        )
        val result = arscCompiler.compile(task)
        checkArscResult(task, result)
    }

    @Test
    fun compileCachedArsc() {
        val arscCompiler = CachedArscCompiler(
            flatDir,
            stableIds,
            manifest,
            androidJar,
            logger
        )

        val file1 = File(assetsAndroidDir, "src/main/res/layout/activity_main2.xml")
        val file2 = File(assetsAndroidDir, "src/main/res/layout/activity_main3.xml")
        val file3 = File(assetsAndroidDir, "src/main/res/drawable/ic_launcher_background.xml")
        val file4 = File(assetsAndroidDir, "src/main/res/drawable/ic_launcher_background2.xml")
        val baseDir = File(assetsAndroidDir, "src/main/res/")
        val task = CompileTask(
            listOf(
                CompileFile(file1, CompileFile.Type.Resource, baseDir),
                CompileFile(file2, CompileFile.Type.Resource, baseDir),
                CompileFile(file3, CompileFile.Type.Resource, baseDir),
                CompileFile(file4, CompileFile.Type.Resource, baseDir),
            ),
            stagingDir
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