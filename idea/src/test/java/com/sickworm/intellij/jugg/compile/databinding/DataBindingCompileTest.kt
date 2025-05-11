package com.sickworm.intellij.jugg.compile.databinding

import com.sickworm.intellij.jugg.compiler.*
import com.sickworm.intellij.jugg.compiler.databinding.DataBindingGenBaseClassesCompiler
import com.sickworm.intellij.jugg.compiler.databinding.DataBindingGenMapperCompiler
import com.sickworm.intellij.jugg.mock.*
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DataBindingCompileTest {

    private val outputDir = File(buildDir, "output")
    private val javaOutputDir = File(outputDir, "java")
    private val layoutOutputDir = File(outputDir, "res")

    @Test
    fun test() {
        buildDir.deleteRecursively()
        val compileTask = CompileTask(
            listOf(CompileFile(
                CompileFile.Type.Resource,
                File(assetsAndroidDir, "app/src/main/res/layout/activity_data_binding_java_demo.xml"),
                File(assetsAndroidDir, "app/src/main/res"),
                context.modules.values.first()
            )),
            outputDir,
            CompileStatusHolder.DEFAULT,
        )

        val baseClassCompiler = DataBindingGenBaseClassesCompiler(context, mockParentDisposable)
        val result = baseClassCompiler.compile(compileTask)
        assertTrue(result.isAllSuccess)
        checkOutputFiles(result, listOf(
            "com/example/myapplication/databinding/ActivityDataBindingJavaDemoBinding.java",
            "layout/activity_data_binding_java_demo.xml",
        ))

        val mapperCompiler = DataBindingGenMapperCompiler(context, mockParentDisposable)
        val result2 = mapperCompiler.compile(compileTask)
        assertTrue(result2.isAllSuccess)
        checkOutputFiles(result2, listOf(
            "androidx/databinding/DataBinderMapperImpl.java",
            "androidx/databinding/DataBindingComponent.java",
            "com/example/myapplication/databinding/ActivityDataBindingJavaDemoBinding.java",
            "com/example/myapplication/databinding/ActivityDataBindingJavaDemoBindingImpl.java",
            "com/example/myapplication/BR.java",
            "com/example/myapplication/DataBinderMapper_IncrementalHolder.java",
            "com/example/myapplication/DataBinderMapperImpl.java",
            "com/example/myapplication/DataBinderMapperImpl_Full.java",
            "com/example/myapplication/DataBinderMapperImpl_Inc_1.java",
            "layout/activity_data_binding_java_demo.xml",
        ))
    }

    private fun checkOutputFiles(compileResult: CompileResult, expect: List<String>) {
        expect.forEach { expectFilePath ->
            val isJava = expectFilePath.endsWith(".java")
            val outputDir = if (isJava) javaOutputDir else layoutOutputDir
            val outputType = if (isJava) CompileOutput.Type.Java else CompileOutput.Type.ResXml
            val expectFile = File(outputDir, expectFilePath)
            val outputFile = compileResult.outputs.find { it.file == expectFile }
            assertTrue(outputFile != null, "File $expectFile does not exist in output")
            assertTrue(outputFile.file.exists(), "File $expectFile does not exist")
            assertEquals(outputDir, outputFile.baseDir, "File $expectFile is not in correct baseDir")
            assertEquals(outputType, outputFile.type, "File $expectFile has incorrect type")
        }
        assertEquals(expect.size, compileResult.outputs.size, "Expect ${expect.size} files, but got ${compileResult.outputs.size}")
    }
}