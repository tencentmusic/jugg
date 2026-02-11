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

    private val javaBaseDir get() = File(assetsAndroidDir, "app/src/main/java")
    private val resBaseDir get() = File(assetsAndroidDir, "app/src/main/res")

    private val javaActivityFile get() = File(javaBaseDir,
        "com/sickworm/jugg/demo/testcase/databinding/DataBindingJavaDemoActivity.java")
    private val kotlinActivityFile get() = File(javaBaseDir,
        "com/sickworm/jugg/demo/testcase/databinding/DataBindingKotlinDemoActivity.kt")
    private val javaLayoutFile get() = File(resBaseDir, "layout/activity_data_binding_java_demo.xml")
    private val kotlinLayoutFile get() = File(resBaseDir, "layout/activity_data_binding_kotlin_demo.xml")

    @Before
    fun setUp() {
        AssembleAndroidProjectOnce.forceRecompile()
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
            CompileHelper.outputDir.clearDir()
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
            CompileHelper.outputDir.clearDir()
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
            checkDataBindingOutputs(compileTask, result2, 1)
            context.deployedFiles.addAll(result2.outputs)
        }

        fun compileXmlIncludeNewXmlDataBinding() {
            CompileHelper.outputDir.clearDir()
            val compileTask = makeTask(
                File(assetsAndroidModifySourceDir, "app/src/main/res/layout/activity_data_binding_old_include.xml"),
            )
            val baseClassCompiler = DataBindingGenBaseClassesCompiler(context, mockParentDisposable)
            val result = baseClassCompiler.compile(compileTask)
            assertTrue(result.isAllSuccess)
            checkOutputFiles(result, listOf(
                "com/example/myapplication/databinding/ActivityDataBindingOldIncludeBinding.java",
                "layout/activity_data_binding_old_include.xml",
            ))
            checkInclude(
                "com/example/myapplication/databinding/ActivityDataBindingOldIncludeBinding.java",
                "ActivityDataBindingIncludeBinding",
                "includeTestLayout",
            )

            val mapperCompiler = DataBindingGenMapperCompiler(context, mockParentDisposable)
            val result2 = mapperCompiler.compile(compileTask)
            assertTrue(result2.isAllSuccess)
            checkDataBindingOutputs(compileTask, result2, 1, listOf(
                "com/example/myapplication/databinding/ActivityDataBindingIncludeBindingImpl.java" // include node
            ))
            context.deployedFiles.addAll(result2.outputs)
        }

        compileNewXmlDataBinding()
        compileNewXml2DataBinding()
        compileXmlIncludeNewXmlDataBinding()
    }

    @Test
    fun reproduceReportCaseE_javaRenameClassWithoutXmlTypeShouldCompileFailed() {
        clearBuild()
        CompileHelper.outputDir.clearDir()

        withPatchedFiles(
            javaActivityFile to javaActivityFile.readText()
                .replace("binding.setUser(new User(\"Jugg User\", 25));", "binding.setUser(new Profile(\"Jugg User\", 25));")
                .replace("public static class User {", "public static class Profile {")
                .replace("public User(String name, int age)", "public Profile(String name, int age)")
        ) {
            val task = CompileTask(
                listOf(CompileFile(CompileFile.Type.Java, javaActivityFile, javaBaseDir, context.modules.values.first())),
                CompileHelper.outputDir,
                CompileStatusHolder.DEFAULT,
            )
            val result = JuggCompiler(context, mockParentDisposable).compile(task)
            assertFailed(result, "DataBindingJavaDemoActivity.java")
        }
    }

    @Test
    fun reproduceReportCaseF_kotlinRenameClassWithoutXmlTypeShouldCompileFailed() {
        clearBuild()
        CompileHelper.outputDir.clearDir()

        withPatchedFiles(
            kotlinActivityFile to kotlinActivityFile.readText()
                .replace("binding.user = User(\"John\", 44)", "binding.user = Profile(\"John\", 44)")
                .replace("data class User(", "data class Profile(")
        ) {
            val task = CompileTask(
                listOf(CompileFile(CompileFile.Type.Kotlin, kotlinActivityFile, javaBaseDir, context.modules.values.first())),
                CompileHelper.outputDir,
                CompileStatusHolder.DEFAULT,
            )
            val result = JuggCompiler(context, mockParentDisposable).compile(task)
            assertFailed(result, "DataBindingKotlinDemoActivity.kt")
        }
    }

    @Test
    fun reproduceReportCaseG_javaAndKotlinRenameClassWithXmlTypeStillCompileFailed() {
        clearBuild()
        CompileHelper.outputDir.clearDir()

        withPatchedFiles(
            javaActivityFile to javaActivityFile.readText()
                .replace("binding.setUser(new User(\"Jugg User\", 25));", "binding.setUser(new Profile(\"Jugg User\", 25));")
                .replace("public static class User {", "public static class Profile {")
                .replace("public User(String name, int age)", "public Profile(String name, int age)"),
            kotlinActivityFile to kotlinActivityFile.readText()
                .replace("binding.user = User(\"John\", 44)", "binding.user = Profile(\"John\", 44)")
                .replace("data class User(", "data class Profile("),
            javaLayoutFile to javaLayoutFile.readText()
                .replace("DataBindingJavaDemoActivity.User", "DataBindingJavaDemoActivity.Profile"),
            kotlinLayoutFile to kotlinLayoutFile.readText()
                .replace("DataBindingKotlinDemoActivity.User", "DataBindingKotlinDemoActivity.Profile"),
        ) {
            val module = context.modules.values.first()
            val task = CompileTask(
                listOf(
                    CompileFile(CompileFile.Type.Java, javaActivityFile, javaBaseDir, module),
                    CompileFile(CompileFile.Type.Kotlin, kotlinActivityFile, javaBaseDir, module),
                    CompileFile(CompileFile.Type.Resource, javaLayoutFile, resBaseDir, module),
                    CompileFile(CompileFile.Type.Resource, kotlinLayoutFile, resBaseDir, module),
                ),
                CompileHelper.outputDir,
                CompileStatusHolder.DEFAULT,
            )
            val result = JuggCompiler(context, mockParentDisposable).compile(task)
            assertFailed(result, "DataBinding")
        }
    }

    @Test
    fun reproduceReportCaseA_javaRenameFieldOnlyCompileSuccessButBindingImplStale() {
        clearBuild()
        CompileHelper.outputDir.clearDir()

        val stableContext = context
        val module = stableContext.modules.values.first()

        val resourceTask = makeTask(javaLayoutFile)
        val baseClassCompiler = DataBindingGenBaseClassesCompiler(stableContext, mockParentDisposable)
        val baseResult = baseClassCompiler.compile(resourceTask)
        assertTrue(baseResult.isAllSuccess)
        val mapperCompiler = DataBindingGenMapperCompiler(stableContext, mockParentDisposable)
        val mapperResult = mapperCompiler.compile(resourceTask)
        assertTrue(mapperResult.isAllSuccess)

        val bindingImplPath = "com/example/myapplication/databinding/ActivityDataBindingJavaDemoBindingImpl.java"
        val bindingImplFile = CompileHelper.javaOutputDir.resolve(bindingImplPath)
        val beforeContent = bindingImplFile.readText()
        assertTrue(beforeContent.contains("name"), "unexpected baseline binding impl: $bindingImplPath")

        withPatchedFiles(
            javaActivityFile to javaActivityFile.readText()
                .replace("public final String name;", "public final String displayName;")
                .replace("public User(String name, int age)", "public User(String displayName, int age)")
                .replace("this.name = name;", "this.displayName = displayName;")
        ) {
            val sourceTask = CompileTask(
                listOf(CompileFile(CompileFile.Type.Java, javaActivityFile, javaBaseDir, module)),
                CompileHelper.outputDir,
                CompileStatusHolder.DEFAULT,
            )
            val result = JuggCompiler(stableContext, mockParentDisposable).compile(sourceTask)
            assertTrue(result.isAllSuccess, "expected source-only incremental compile success")
            assertTrue(result.outputs.none { it.file.name.contains("ActivityDataBindingJavaDemoBindingImpl") },
                "unexpected databinding impl regenerated: ${result.outputs.joinToString { it.file.name }}")

            val afterContent = bindingImplFile.readText()
            assertTrue(afterContent.contains("name"), "binding impl should still reference old name")
            assertTrue(!afterContent.contains("displayName"), "binding impl should not contain displayName")
        }
    }

    @Test
    fun reproduceReportCaseC_kotlinRenameFieldOnlyCompileSuccessButBindingImplStale() {
        clearBuild()
        CompileHelper.outputDir.clearDir()

        val stableContext = context
        val module = stableContext.modules.values.first()

        val resourceTask = makeTask(kotlinLayoutFile)
        val baseClassCompiler = DataBindingGenBaseClassesCompiler(stableContext, mockParentDisposable)
        val baseResult = baseClassCompiler.compile(resourceTask)
        assertTrue(baseResult.isAllSuccess)
        val mapperCompiler = DataBindingGenMapperCompiler(stableContext, mockParentDisposable)
        val mapperResult = mapperCompiler.compile(resourceTask)
        assertTrue(mapperResult.isAllSuccess)

        val bindingImplPath = "com/example/myapplication/databinding/ActivityDataBindingKotlinDemoBindingImpl.java"
        val bindingImplFile = CompileHelper.javaOutputDir.resolve(bindingImplPath)
        val beforeContent = bindingImplFile.readText()
        assertTrue(beforeContent.contains("getName()") || beforeContent.contains("name"),
            "unexpected baseline binding impl: $bindingImplPath")

        withPatchedFiles(
            kotlinActivityFile to kotlinActivityFile.readText()
                .replace("val name: String,", "val displayName: String,")
        ) {
            val sourceTask = CompileTask(
                listOf(CompileFile(CompileFile.Type.Kotlin, kotlinActivityFile, javaBaseDir, module)),
                CompileHelper.outputDir,
                CompileStatusHolder.DEFAULT,
            )
            val result = JuggCompiler(stableContext, mockParentDisposable).compile(sourceTask)
            assertTrue(result.isAllSuccess, "expected source-only incremental compile success")
            assertTrue(result.outputs.none { it.file.name.contains("ActivityDataBindingKotlinDemoBindingImpl") },
                "unexpected databinding impl regenerated: ${result.outputs.joinToString { it.file.name }}")

            val afterContent = bindingImplFile.readText()
            assertTrue(afterContent.contains("getName()") || afterContent.contains("name"),
                "binding impl should still reference old name")
            assertTrue(!afterContent.contains("displayName"), "binding impl should not contain displayName")
        }
    }

    companion object {

        private fun withPatchedFiles(vararg patches: Pair<File, String>, block: () -> Unit) {
            val backup = patches.associate { (file, _) -> file to if (file.exists()) file.readText() else null }
            try {
                patches.forEach { (file, newContent) ->
                    file.parentFile?.mkdirs()
                    file.writeText(newContent)
                }
                block()
            } finally {
                backup.forEach { (file, oldContent) ->
                    when (oldContent) {
                        null -> if (file.exists()) file.delete()
                        else -> file.writeText(oldContent)
                    }
                }
            }
        }

        private fun assertFailed(result: CompileResult, keyword: String) {
            assertTrue(!result.isAllSuccess, "expected compile failed, but success")
            val allErrors = result.failedFiles.joinToString("\n") {
                "${it.file.file.name}: ${it.getFailure().errorMessages}"
            }
            assertTrue(allErrors.contains(keyword), "expected error contains '$keyword', actual:\n$allErrors")
        }

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
            assertEquals(expect.size, compileResult.outputs.size, "Expect ${expect.size} files, " +
                    "but got ${compileResult.outputs.size}: ${compileResult.outputs.joinToString("\n") { it.file.path }}")
        }

        private fun checkDataBindingOutputs(compileTask: CompileTask, compileResult: CompileResult, incTimes: Int, additionalOutput: List<String> = listOf()) {
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
            checkOutputFiles(compileResult, base + outputFiles + additionalOutput)
        }
    }
}
