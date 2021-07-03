package com.sickworm.intellij.aidp

import com.intellij.mock.MockProject
import com.intellij.openapi.Disposable
import org.junit.Before
import org.junit.Test
import java.io.File

class AidpCompileTest {

    private val disposable = Disposable { }
    private val project = MockProject(null, disposable)
    private val aidpCompiler = AidpCompiler(project, File(compileClassDir), File(classPathDir))

    @Before
    fun init() {
        clearBuild()
    }

    private val helloWorldTask = CompileTask.singleFile(
        filePath = "$assetsJavaDir/com/sickworm/intellij/aidp/test/HelloWorldJavaFile.java",
        outputDir = compileDexDir)
    @Test
    fun compileJavaDex() {
        val results = aidpCompiler.compile(helloWorldTask)
        assert(results.size == 1)
        assertCompileResult(assetsJavaDir, results.first(), true, isCheckDexExist = true)
    }
}