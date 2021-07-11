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
    private val aidpCompiler = AidpCompiler(project, tempCompileDir, classPathDir)

    @Before
    fun init() {
        clearBuild()
    }

    private val singleJavaSourceTask = JavaCompileTest().helloWorldTask
    @Test
    fun compileSingleJavaDex() {
        val result = aidpCompiler.compile(singleJavaSourceTask)
        assertCompileResultAidp(singleJavaSourceTask, result)
    }

    private val multiJavaSourcesTask = JavaCompileTest().multiFilesTask
    @Test
    fun compileMultiJavaDex() {
        val result = aidpCompiler.compile(multiJavaSourcesTask)
        assert(result.details.size == multiJavaSourcesTask.files.size)
        assertCompileResultAidp(multiJavaSourcesTask, result)
    }

    private val multiJavaSourcesWithErrorTask = JavaCompileTest().multiFilesWithErrorTask
    @Test
    fun compileMultiJavaWithErrorDex() {
        val result = aidpCompiler.compile(multiJavaSourcesWithErrorTask)
        assertCompileResultFailed(multiJavaSourcesWithErrorTask, result, mapOf(JavaCompileTest().errorTask.files[0] to 2))
    }

    private val multiAssetsTask = AssetCompileTest().multiFilesTask
    @Test
    fun compileMultiAsset() {
        val result = aidpCompiler.compile(multiAssetsTask)
        // TODO
    }

    private fun assertCompileResultAidp(task: CompileTask, result: CompileResult) {
        val mapper: OutputFileMapper = {
            if (it.type == CompileFile.Type.Java || it.type == CompileFile.Type.Kotlin) {
                val outputBaseDir = File(task.outputDir, "classes")
                val outputFile = it.file.changeBaseDir(it.baseDir, outputBaseDir, "dex")
                listOf(CompileOutput(outputFile, outputBaseDir, CompileOutput.Type.Dex))
            } else if (it.type == CompileFile.Type.Overlay) {
                val outputBaseDir = File(task.outputDir, "overlays/assets")
                val outputFile = it.file.changeBaseDir(it.baseDir, outputBaseDir)
                listOf(CompileOutput(outputFile, outputBaseDir, CompileOutput.Type.Overlay))
            } else if (it.type == CompileFile.Type.Res) {
               TODO()
            } else {
                throw IllegalStateException("not supported")
            }
        }
        assertCompileResult(task, result, mapper)
    }
}