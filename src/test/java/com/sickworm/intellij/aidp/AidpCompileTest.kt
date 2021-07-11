package com.sickworm.intellij.aidp

import com.intellij.mock.MockProject
import com.intellij.openapi.Disposable
import com.sickworm.intellij.aidp.compiler.*
import org.junit.Before
import org.junit.Test
import java.io.File

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
        assertCompileResult(singleJavaSourceTask, result, CompileOutput.Type.Dex)
    }

    private val multiJavaSourcesTask = JavaCompileTest().multiFilesTask
    @Test
    fun compileMultiJavaDex() {
        val result = aidpCompiler.compile(multiJavaSourcesTask)
        assert(result.details.size == multiJavaSourcesTask.files.size)
        assertCompileResult(multiJavaSourcesTask, result, CompileOutput.Type.Dex)
    }

    private val multiJavaSourcesWithErrorTask = JavaCompileTest().multiFilesWithErrorTask
    @Test
    fun compileMultiJavaWithErrorDex() {
        val result = aidpCompiler.compile(multiJavaSourcesWithErrorTask)
        assertCompileResultFailed(multiJavaSourcesWithErrorTask, result, mapOf(JavaCompileTest().errorTask.files[0] to 2))
    }

    private val multiAssetsTask = AssetsCompileTest().multiFilesTask
    @Test
    fun compileMultiAsset() {
        val result = aidpCompiler.compile(multiAssetsTask)
        AssetsCompileTest().checkError(multiAssetsTask, result, outputDir = File(stagingDir, "overlays/assets"))
    }
}