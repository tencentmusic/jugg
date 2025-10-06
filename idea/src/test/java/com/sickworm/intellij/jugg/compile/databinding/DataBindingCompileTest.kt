package com.sickworm.intellij.jugg.compile.databinding

import android.databinding.tool.ext.toCamelCase
import com.sickworm.intellij.jugg.compile.CompileHelper
import com.sickworm.intellij.jugg.compiler.*
import com.sickworm.intellij.jugg.compiler.databinding.DataBindingGenBaseClassesCompiler
import com.sickworm.intellij.jugg.compiler.databinding.DataBindingGenMapperCompiler
import com.sickworm.intellij.jugg.mock.*
import org.junit.Before
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DataBindingCompileTest {

    @Before
    fun setUp() {
        buildDir.deleteRecursively()
    }

    @Test
    fun testDataBinding() {
        val compileTask = makeTask(
            File(assetsAndroidDir, "app/src/main/res/layout/activity_data_binding_java_demo.xml")
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
        checkDataBindingOutputs(compileTask, result2, 1)
    }

    @Test
    fun testXmlIncludeNodeViewBinding() {
        val compileTask = makeTask(
            File(assetsAndroidDir, "app/src/main/res/layout/activity_data_binding_include.xml"),
        )

        val baseClassCompiler = DataBindingGenBaseClassesCompiler(context, mockParentDisposable)
        val result = baseClassCompiler.compile(compileTask)
        assertTrue(result.isAllSuccess)
        checkOutputFiles(result, listOf(
            "com/example/myapplication/databinding/ActivityDataBindingIncludeBinding.java",
            "layout/activity_data_binding_include.xml",
        ))

        checkInclude(
            "com/example/myapplication/databinding/ActivityDataBindingIncludeBinding.java",
            "TestLayoutBinding",
            "includeTestLayout",
        )
    }

    @Test
    fun testNewNodeViewBinding() {
        compileNewXmlViewBinding()
        compileXmlIncludeNewXmlViewBinding()
    }

    @Test
    fun testMultipleNewXmlViewBinding() {
        compileNewXmlViewBinding()
        compileNewXml2ViewBinding()
        compileXmlIncludeNewXmlViewBinding()
    }

    private fun compileXmlIncludeNewXmlViewBinding() {
        val compileTask = makeTask(
            File(assetsAndroidModifySourceDir, "app/src/main/res/layout/activity_view_binding_new_include.xml"),
        )
        val baseClassCompiler = DataBindingGenBaseClassesCompiler(context, mockParentDisposable)
        val result = baseClassCompiler.compile(compileTask)
        assertTrue(result.isAllSuccess)
        checkOutputFiles(result, listOf(
            "com/example/myapplication/databinding/ActivityViewBindingNewIncludeBinding.java",
            "layout/activity_view_binding_new_include.xml",
        ))
        checkInclude(
            "com/example/myapplication/databinding/ActivityViewBindingNewIncludeBinding.java",
            "ActivityViewBindingNewBinding",
            "includeTestLayoutNew",
        )

    }

    private fun compileNewXmlViewBinding() {
        val compileTask = makeTask(
            File(assetsAndroidModifySourceDir, "app/src/main/res/layout/activity_view_binding_new.xml"),
        )
        val baseClassCompiler = DataBindingGenBaseClassesCompiler(context, mockParentDisposable)
        val result = baseClassCompiler.compile(compileTask)
        assertTrue(result.isAllSuccess)
        checkOutputFiles(result, listOf(
            "com/example/myapplication/databinding/ActivityViewBindingNewBinding.java",
            "layout/activity_view_binding_new.xml",
        ))
    }

    private fun compileNewXml2ViewBinding() {
        val compileTask = makeTask(
            File(assetsAndroidModifySourceDir, "app/src/main/res/layout/activity_view_binding_new2.xml"),
        )
        val baseClassCompiler = DataBindingGenBaseClassesCompiler(context, mockParentDisposable)
        val result = baseClassCompiler.compile(compileTask)
        assertTrue(result.isAllSuccess)
        checkOutputFiles(result, listOf(
            "com/example/myapplication/databinding/ActivityViewBindingNew2Binding.java",
            "layout/activity_view_binding_new2.xml",
        ))
    }

    @Test
    fun testMultipleNewXmlDataBinding() {
        val context = context

        fun compileNewXmlDataBinding() {
            val compileTask = makeTask(
                File(assetsAndroidModifySourceDir, "app/src/main/res/layout/activity_data_binding_new.xml"),
            )
            val baseClassCompiler = DataBindingGenBaseClassesCompiler(context, mockParentDisposable)
            val result = baseClassCompiler.compile(compileTask)
            assertTrue(result.isAllSuccess)
            checkOutputFiles(result, listOf(
                "com/example/myapplication/databinding/ActivityDataBindingNewBinding.java",
                "layout/activity_data_binding_new.xml",
            ))

            val mapperCompiler = DataBindingGenMapperCompiler(context, mockParentDisposable)
            val result2 = mapperCompiler.compile(compileTask)
            assertTrue(result2.isAllSuccess)
            checkDataBindingOutputs(compileTask, result2, 1)
            context.deployedFiles.addAll(result2.outputs)
        }

        fun compileNewXml2DataBinding() {
            val compileTask = makeTask(
                File(assetsAndroidModifySourceDir, "app/src/main/res/layout/activity_data_binding_new2.xml"),
            )
            val baseClassCompiler = DataBindingGenBaseClassesCompiler(context, mockParentDisposable)
            val result = baseClassCompiler.compile(compileTask)
            assertTrue(result.isAllSuccess)
            checkOutputFiles(result, listOf(
                "com/example/myapplication/databinding/ActivityDataBindingNew2Binding.java",
                "layout/activity_data_binding_new2.xml",
            ))

            val mapperCompiler = DataBindingGenMapperCompiler(context, mockParentDisposable)
            val result2 = mapperCompiler.compile(compileTask)
            assertTrue(result2.isAllSuccess)
            checkDataBindingOutputs(compileTask, result2, 2)
            context.deployedFiles.addAll(result2.outputs)
        }

        fun compileXmlIncludeNewXmlDataBinding() {
            val compileTask = makeTask(
                File(assetsAndroidModifySourceDir, "app/src/main/res/layout/activity_data_binding_new_include.xml"),
            )
            val baseClassCompiler = DataBindingGenBaseClassesCompiler(context, mockParentDisposable)
            val result = baseClassCompiler.compile(compileTask)
            assertTrue(result.isAllSuccess)
            checkOutputFiles(result, listOf(
                "com/example/myapplication/databinding/ActivityDataBindingNewIncludeBinding.java",
                "layout/activity_data_binding_new_include.xml",
            ))
            checkInclude(
                "com/example/myapplication/databinding/ActivityDataBindingNewIncludeBinding.java",
                "ActivityDataBindingNewBinding",
                "includeTestLayout",
            )

            val mapperCompiler = DataBindingGenMapperCompiler(context, mockParentDisposable)
            val result2 = mapperCompiler.compile(compileTask)
            assertTrue(result2.isAllSuccess)
            checkDataBindingOutputs(compileTask, result2, 3)
            context.deployedFiles.addAll(result2.outputs)
        }

        compileNewXmlDataBinding()
        compileNewXml2DataBinding()
        compileXmlIncludeNewXmlDataBinding()
    }

    companion object {

        private fun makeTask(vararg files: File): CompileTask {
            return CompileHelper.makeTask(*files)
        }

        private fun checkInclude(file: String, type: String, name: String) {
            val javaContent = CompileHelper.javaOutputDir.resolve(file).readLines()
            val includeField = javaContent.find { it.contains("$name;") }
            assertNotNull(includeField)
            assertTrue(includeField.contains("$type $name;"), "include field is not generated correct type, actual: \"$includeField\"")
        }

        private fun checkOutputFiles(compileResult: CompileResult, expect: List<String>) {
            expect.forEach { expectFilePath ->
                val isJava = expectFilePath.endsWith(".java")
                val outputDir = if (isJava) CompileHelper.javaOutputDir else CompileHelper.xmlOutputDir
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

        private fun checkDataBindingOutputs(compileTask: CompileTask, compileResult: CompileResult, incTimes: Int) {
            val outputFiles = compileTask.files.flatMap {
                val file = it.file
                listOf(
                    "com/example/myapplication/databinding/${file.nameWithoutExtension.toCamelCase()}Binding.java",
                    "com/example/myapplication/databinding/${file.nameWithoutExtension.toCamelCase()}BindingImpl.java",
                    "layout/${file.name}",
                )
            }
            val base = listOf(
                "androidx/databinding/DataBinderMapperImpl.java",
                "androidx/databinding/DataBindingComponent.java",
                "com/example/myapplication/BR.java",
                "com/example/myapplication/DataBinderMapper_IncrementalHolder.java",
                "com/example/myapplication/DataBinderMapperImpl.java",
                "com/example/myapplication/DataBinderMapperImpl_Full.java",
                "com/example/myapplication/DataBinderMapperImpl_Inc_$incTimes.java",
            )
            checkOutputFiles(compileResult, base + outputFiles)
        }
    }
}