package com.sickworm.intellij.jugg.compile.databinding

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
    fun test() {
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

    @Test
    fun testXmlIncludeNode() {
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
    fun testNewNode() {
        compileNewXml()
        compileXmlIncludeNewXml()
    }

    @Test
    fun testMultipleNewXml() {
        compileNewXml()
        compileNewXml2()
        compileXmlIncludeNewXml()
    }

    private fun compileXmlIncludeNewXml(isRunDataBinding: Boolean = false) {
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

        if (isRunDataBinding) {
            val mapperCompiler = DataBindingGenMapperCompiler(context, mockParentDisposable)
            val result2 = mapperCompiler.compile(compileTask)
            assertTrue(result2.isAllSuccess)
            assertTrue(result2.outputs.isNotEmpty())
        }
    }

    private fun compileNewXml(isRunDataBinding: Boolean = false) {
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

        if (isRunDataBinding) {
            val mapperCompiler = DataBindingGenMapperCompiler(context, mockParentDisposable)
            val result2 = mapperCompiler.compile(compileTask)
            assertTrue(result2.isAllSuccess)
            assertTrue(result2.outputs.isNotEmpty())
        }
    }

    private fun compileNewXml2(isRunDataBinding: Boolean = false) {
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

        if (isRunDataBinding) {
            val mapperCompiler = DataBindingGenMapperCompiler(context, mockParentDisposable)
            val result2 = mapperCompiler.compile(compileTask)
            assertTrue(result2.isAllSuccess)
            assertTrue(result2.outputs.isNotEmpty())
        }
    }


    @Test
    fun testMultipleNewXmlDataBinding() {
        compileNewXml(isRunDataBinding = true)
        compileNewXml2(isRunDataBinding = true)
        compileXmlIncludeNewXml(isRunDataBinding = true)
    }

    companion object {

        private val outputDir = File(buildDir, "output")
        private val javaOutputDir = File(outputDir, "java")
        private val layoutOutputDir = File(outputDir, "res")

        private fun makeTask(vararg files: File): CompileTask {
            return CompileTask(
                files.map {
                    CompileFile(
                        CompileFile.Type.Resource,
                        it,
                        it.parentFile.parentFile,
                        context.modules.values.first()
                    )
                },
                outputDir,
                CompileStatusHolder.DEFAULT,
            )
        }

        private fun checkInclude(file: String, type: String, name: String) {
            val javaContent = javaOutputDir.resolve(file).readLines()
            val includeField = javaContent.find { it.contains("$name;") }
            assertNotNull(includeField)
            assertTrue(includeField.contains("$type $name;"), "include field is not generated correct type, actual: \"$includeField\"")
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
}