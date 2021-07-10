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

    private val compileSingleJavaDexTask = JavaCompileTest().helloWorldTask.copy(outputDir = stagingDir)
    @Test
    fun compileSingleJavaDex() {
        val result = aidpCompiler.compile(compileSingleJavaDexTask)
        assert(result.details.size == 1)
        assertCompileResult(assetsJavaDir, result.details.first(), true)
        assert(result.outputs.size == 1)

        result.outputs.forEach {
            assert(it.type == CompileOutput.Type.Dex)
            assert(it.file.exists() && it.file.length() > 0)
        }
    }

    private val multiFilesTask = JavaCompileTest().multiFilesTask.copy(outputDir = stagingDir)
    @Test
    fun compileMultiJavaDex() {
        val result = aidpCompiler.compile(multiFilesTask)
        assert(result.details.size == multiFilesTask.files.size)
        result.details.forEach {
            assertCompileResult(assetsJavaDir, it, true)
        }
        result.outputs.forEach {
            assert(it.type == CompileOutput.Type.Dex)
            assert(it.file.exists() && it.file.length() > 0)
        }
    }

    private val multiFilesWithErrorTask = JavaCompileTest().multiFilesWithErrorTask.copy(outputDir = stagingDir)
    @Test
    fun compileMultiJavaWithErrorDex() {
        val result = aidpCompiler.compile(multiFilesWithErrorTask)
        assert(result.details.size == multiFilesWithErrorTask.files.size)
        result.details.forEach {
            if (it.file.file.name == "ErrorJavaFile.java") {
                assertCompileResult(assetsJavaDir, it, false, 2)
            } else {
                assertCompileResult(assetsJavaDir, it, false, 0)
            }
        }
        assert(result.outputs.isEmpty())
    }

    @Test
    fun compileOverlay() {
    }
}