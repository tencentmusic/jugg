package com.sickworm.intellij.aidp

import com.intellij.mock.MockProject
import com.intellij.openapi.Disposable
import com.sickworm.intellij.aidp.compiler.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.lang.IllegalStateException

class AidpCompileTest {

    private val disposable = Disposable { }
    private val project = MockProject(null, disposable)
    private val aidpCompiler = AidpCompiler(
        project,
        tempCompileDir = tempCompileDir,
        classPathDir = classPathDir,
        androidJar = androidJar,
        androidBuildTools = androidBuildTools,
        flatDir = ResourceCompileTest().flatDir,
        manifest = ResourceCompileTest().manifest,
        stableIds = ResourceCompileTest().stableIds
    )

    @Before
    fun init() {
        clearBuild()
        ResourceCompileTest().init()
    }

    @Test
    fun compileSingleJava() {
        val task = JavaCompileTest().helloWorldTask
        val result = aidpCompiler.compile(task)
        assertCompileResultAidp(task, result)
    }

    @Test
    fun compileMultiJava() {
        val task = JavaCompileTest().multiFilesTask
        val result = aidpCompiler.compile(task)
        assertCompileResultAidp(task, result)
    }

    @Test
    fun compileMultiJavaWithError() {
        val task = JavaCompileTest().multiFilesWithErrorTask
        val result = aidpCompiler.compile(task)
        assertCompileResultFailed(task, result, mapOf(JavaCompileTest().errorTask.files[0] to 2))
    }

    @Test
    fun compileMultiAssets() {
        val task = AssetCompileTest().multiFilesTask
        val result = aidpCompiler.compile(task)
        assertCompileResultAidp(task, result)
    }

    @Test
    fun compileResource() {
        val task = ResourceCompileTest().resourceOverlayTask
        val result = aidpCompiler.compile(task)
        assertCompileResultAidp(task, result)
    }

    @Test
    fun compileMultiJavaAndAsset() {
        val task = JavaCompileTest().multiFilesTask + AssetCompileTest().multiFilesTask
        val result = aidpCompiler.compile(task)
        assertCompileResultAidp(task, result)
    }

    @Test
    fun compileMultiJavaAndAssetAndRes() {
        val task = JavaCompileTest().multiFilesTask + AssetCompileTest().multiFilesTask + ResourceCompileTest().resourceOverlayTask
        val result = aidpCompiler.compile(task)
        assertCompileResultAidp(task, result)
    }

    @Test
    fun compileMultiJavaErrorAndAsset() {
        val sourceTask = JavaCompileTest().multiFilesWithErrorTask
        val assetTask = AssetCompileTest().multiFilesTask
        val task = sourceTask + assetTask
        val result = aidpCompiler.compile(task)

        val sourceResult = CompileResult(
            sourceTask,
            result.details.filter { sourceTask.files.contains(it.file) },
            emptyList()
        )
        assertCompileResultFailed(sourceTask, sourceResult, mapOf(JavaCompileTest().errorTask.files[0] to 2))

        val assetResult = CompileResult(
            assetTask,
            result.details.filter { assetTask.files.contains(it.file) },
            result.outputs
        )
        assertCompileResultAidp(assetTask, assetResult)
    }

    @Test
    fun compileMultiJavaErrorAndAssetAndRes() {
        val sourceTask = JavaCompileTest().multiFilesWithErrorTask
        val assetTask = AssetCompileTest().multiFilesTask
        val resourceTask = ResourceCompileTest().resourceOverlayTask
        val task = sourceTask + assetTask + resourceTask
        val result = aidpCompiler.compile(task)

        val sourceResult = CompileResult(
            sourceTask,
            result.details.filter { sourceTask.files.contains(it.file) },
            emptyList()
        )
        assertCompileResultFailed(sourceTask, sourceResult, mapOf(JavaCompileTest().errorTask.files[0] to 2))

        val remainTask = assetTask + resourceTask
        val remainResult = CompileResult(
            remainTask,
            result.details.filter { !sourceTask.files.contains(it.file) },
            result.outputs
        )
        assertCompileResultAidp(remainTask, remainResult)
    }

    private fun assertCompileResultAidp(task: CompileTask, result: CompileResult) {
        val mapper: OutputFileMapper = {
            if (it.type == CompileFile.Type.Java || it.type == CompileFile.Type.Kotlin) {
                val outputBaseDir = File(task.outputDir, "classes")
                val outputFile = it.file.changeBaseDir(it.baseDir, outputBaseDir, "dex")
                listOf(CompileOutput(CompileOutput.Type.Dex, outputFile, outputBaseDir))
            } else if (it.type == CompileFile.Type.Asset) {
                val outputBaseDir = File(task.outputDir, "overlays")
                val outputFile = it.file.changeBaseDir(it.baseDir, File(outputBaseDir, "assets"))
                listOf(CompileOutput(CompileOutput.Type.Overlay, outputFile, outputBaseDir))
            } else if (it.type == CompileFile.Type.Resource) {
                val outputBaseDir = File(task.outputDir, "overlays")
                val outputFile = it.file.changeBaseDir(it.baseDir, File(outputBaseDir, "res"))
                val flatOutput = CompileOutput(
                    CompileOutput.Type.Overlay,
                    outputFile,
                    outputBaseDir
                )

                // R*.dex
                val sourceBaseDir = File(task.outputDir, "classes")
                val rOutDir = File(sourceBaseDir, "com/example/myapplication")
                val rDexList = "R\$anim.dex, R\$attr.dex, R\$bool.dex, R\$color.dex, R\$dimen.dex, R\$drawable.dex, R\$id.dex, R\$integer.dex, R\$layout.dex, R\$mipmap.dex, R\$string.dex, R\$style.dex, R\$styleable.dex, R.dex".split(", ")
                val dexOutputs = rDexList.map { name ->
                    CompileOutput(CompileOutput.Type.Dex, File(rOutDir, name), sourceBaseDir)
                }

                // resources.arsc
                val overlayBaseDir = File(task.outputDir, "overlays")
                val arscFile = File(overlayBaseDir, ARSC_FILE_NAME)
                val arscOutput = CompileOutput(CompileOutput.Type.Overlay, arscFile, overlayBaseDir)

                listOf<CompileOutput>() + flatOutput + arscOutput + dexOutputs
            } else {
                throw IllegalStateException("not supported")
            }
        }
        assertCompileResult(task, result, mapper)
    }
}