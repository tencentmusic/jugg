package com.sickworm.intellij.jugg.compile.databinding

import com.sickworm.intellij.jugg.compiler.CompileFile
import com.sickworm.intellij.jugg.compiler.CompileResult
import com.sickworm.intellij.jugg.compiler.CompileStatusHolder
import com.sickworm.intellij.jugg.compiler.CompileTask
import com.sickworm.intellij.jugg.compiler.databinding.DataBindingGenBaseClassesCompiler
import com.sickworm.intellij.jugg.compiler.databinding.DataBindingGenMapperCompiler
import com.sickworm.intellij.jugg.mock.*
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DataBindingCompileTest {

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
            File(buildDir, "output"),
            CompileStatusHolder.DEFAULT,
        )

        val baseClassCompiler = DataBindingGenBaseClassesCompiler(context, mockParentDisposable)
        val result = baseClassCompiler.compile(compileTask)
        assertTrue(result.isAllSuccess)
        checkOutputFiles(result, listOf(
            File(compileTask.outputDir, "com/example/myapplication/databinding/ActivityDataBindingJavaDemoBinding.java")
        ))

        val mapperCompiler = DataBindingGenMapperCompiler(context, mockParentDisposable)
        val result2 = mapperCompiler.compile(compileTask)
        assertTrue(result2.isAllSuccess)
        checkOutputFiles(result2, listOf(
            File(compileTask.outputDir, "androidx/databinding/DataBinderMapperImpl.java"),
            File(compileTask.outputDir, "androidx/databinding/DataBindingComponent.java"),
            File(compileTask.outputDir, "com/example/myapplication/databinding/ActivityDataBindingJavaDemoBinding.java"),
            File(compileTask.outputDir, "com/example/myapplication/databinding/ActivityDataBindingJavaDemoBindingImpl.java"),
            File(compileTask.outputDir, "com/example/myapplication/BR.java"),
            File(compileTask.outputDir, "com/example/myapplication/DataBinderMapper_IncrementalHolder.java"),
            File(compileTask.outputDir, "com/example/myapplication/DataBinderMapperImpl.java"),
            File(compileTask.outputDir, "com/example/myapplication/DataBinderMapperImpl_Full.java"),
            File(compileTask.outputDir, "com/example/myapplication/DataBinderMapperImpl_Inc_1.java"),
        ))
    }

    private fun checkOutputFiles(compileResult: CompileResult, expect: List<File>) {
        expect.forEach { expectFile ->
            val outputFile = compileResult.outputs.find { it.file == expectFile }
            assertTrue(outputFile != null, "File $expectFile does not exist in output")
            assertTrue(outputFile.file.exists(), "File $expectFile does not exist")
            assertEquals(compileResult.task.outputDir, outputFile.baseDir)
        }
        assertEquals(expect.size, compileResult.outputs.size, "Expect ${expect.size} files, but got ${compileResult.outputs.size}")
    }
}