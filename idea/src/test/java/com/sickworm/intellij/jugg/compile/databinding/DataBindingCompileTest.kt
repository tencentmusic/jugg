package com.sickworm.intellij.jugg.compile.databinding

import android.databinding.tool.ext.toCamelCase
import com.jetbrains.rd.util.first
import com.sickworm.intellij.jugg.compile.CompileHelper
import com.sickworm.intellij.jugg.compiler.*
import com.sickworm.intellij.jugg.compiler.databinding.DataBindingArgsManager
import com.sickworm.intellij.jugg.compiler.databinding.DataBindingGenBaseClassesCompiler
import com.sickworm.intellij.jugg.compiler.databinding.DataBindingGenMapperCompiler
import com.sickworm.intellij.jugg.mock.*
import org.junit.Before
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

open class DataBindingCompileTest {

    private val javaBaseDir get() = File(assetsAndroidDir, "app/src/main/java")
    private val resBaseDir get() = File(assetsAndroidDir, "app/src/main/res")
    private val library1JavaBaseDir get() = File(assetsAndroidDir, "library1/src/main/java")
    private val library1ResBaseDir get() = File(assetsAndroidDir, "library1/src/main/res")

    private val javaActivityFile get() = File(javaBaseDir,
        "com/sickworm/jugg/demo/testcase/databinding/DataBindingJavaDemoActivity.java")
    private val kotlinActivityFile get() = File(javaBaseDir,
        "com/sickworm/jugg/demo/testcase/databinding/DataBindingKotlinDemoActivity.kt")
    private val javaLayoutFile get() = File(resBaseDir, "layout/activity_data_binding_java_demo.xml")
    private val kotlinLayoutFile get() = File(resBaseDir, "layout/activity_data_binding_kotlin_demo.xml")
    private val booleanVisibilityLayoutFile get() =
        File(resBaseDir, "layout/activity_data_binding_boolean_visibility_demo.xml")
    private val library1JavaLayoutFile get() = File(library1ResBaseDir, "layout/activity_data_binding_java_demo_library1.xml")
    private val library1KotlinActivityFile get() = File(
        library1JavaBaseDir,
        "com/sickworm/jugg/demo/testcase/databinding/library1/DataBindingKotlinDemoActivityLibrary1.kt")
    private val library1KotlinLayoutFile get() = File(library1ResBaseDir, "layout/activity_data_binding_kotlin_demo_library1.xml")

    @Before
    fun setUp() {
        AssembleAndroidProjectOnce.forceRecompile(isNeedClean = false)
        buildDir.deleteRecursively()
        context.tempModule.buildPathInfo.buildDir.deleteRecursively()
        CompileHelper.outputDir.deleteRecursively()
    }

    @Test
    open fun testDataBinding() {
        val compileTask = makeTask(
            File(assetsAndroidDir, "app/src/main/res/layout/activity_data_binding_java_demo.xml")
        )

        val baseClassCompiler = DataBindingGenBaseClassesCompiler(context, mockParentDisposable)
        val result = baseClassCompiler.compile(compileTask)
        assertTrue(result.isAllSuccess)
        checkOutputFiles(result, listOf(
//            "com/example/myapplication/databinding/ActivityDataBindingJavaDemoBinding.java",
            "com/example/myapplication/DataBindingInfo.kt",
            "layout/activity_data_binding_java_demo.xml",
        ))

        val bindingTask = createBindingTask(compileTask, result)
        val mapperCompiler = DataBindingGenMapperCompiler(context, mockParentDisposable)
        val result2 = mapperCompiler.compile(bindingTask)
        assertTrue(result2.isAllSuccess)
        checkDataBindingOutputs(compileTask, result2, 1)
        assertFallback()
    }

    @Test
    fun customBindingAdapterFromGradleSetterStoreShouldCompile() {
        val compileTask = makeTask(booleanVisibilityLayoutFile)

        val baseClassCompiler = DataBindingGenBaseClassesCompiler(context, mockParentDisposable)
        val baseResult = baseClassCompiler.compile(compileTask)
        assertTrue(baseResult.isAllSuccess)

        val mapperCompiler = DataBindingGenMapperCompiler(context, mockParentDisposable)
        val mapperResult = mapperCompiler.compile(createBindingTask(compileTask, baseResult))
        assertTrue(mapperResult.isAllSuccess, "expected Gradle setter store adapter to be available")
        checkDataBindingOutputs(compileTask, mapperResult, 1)
        assertFallback()
    }

    @Test
    fun reproduceReportCaseH_libraryModuleDataBindingLayoutCompileShouldSuccess() {
        clearBuild()
        CompileHelper.outputDir.clearDir()

        val compileTask = makeTask("library1", library1ResBaseDir, library1JavaLayoutFile)

        val baseClassCompiler = DataBindingGenBaseClassesCompiler(context, mockParentDisposable)
        val result = baseClassCompiler.compile(compileTask)
        assertTrue(result.isAllSuccess)
        checkOutputFiles(result, listOf(
            "com/example/library1/DataBindingInfo.kt",
            "layout/activity_data_binding_java_demo_library1.xml",
        ))

        val bindingTask = createBindingTask(compileTask, result)
        val mapperCompiler = DataBindingGenMapperCompiler(context, mockParentDisposable)
        val result2 = mapperCompiler.compile(bindingTask)
        assertTrue(result2.isAllSuccess)
        checkDataBindingOutputs(compileTask, result2, 1)
        assertFallback()
    }

    @Test
    fun reproduceReportCaseI_libraryModuleKotlinSourceWithDataBindingShouldCompileSuccess() {
        clearBuild()
        CompileHelper.outputDir.clearDir()

        val module = context.modules["library1"]
            ?: throw IllegalStateException("module not found: library1, all: ${context.modules.keys}")
        val compileTask = CompileTask(
            files = listOf(
                CompileFile(CompileFile.Type.Kotlin, library1KotlinActivityFile, library1JavaBaseDir, module),
                CompileFile(CompileFile.Type.Resource, library1KotlinLayoutFile, library1ResBaseDir, module),
            ),
            CompileHelper.outputDir,
            CompileStatusHolder.DEFAULT,
        )

        val result = JuggCompiler(context, mockParentDisposable).compile(compileTask)
        assertTrue(result.isAllSuccess, "expected library module kotlin databinding compile success")
        assertTrue(result.outputs.any { it.relativeFile.path.endsWith("DataBindingKotlinDemoActivityLibrary1.dex") },
            "expected kotlin source dex output, actual: ${result.outputs.joinToString { it.relativeFile.path }}")
        assertTrue(result.outputs.any { it.relativeFile.path.endsWith("activity_data_binding_kotlin_demo_library1.xml") },
            "expected databinding layout output, actual: ${result.outputs.joinToString { it.relativeFile.path }}")
        assertFallback()
    }

    @Test
    fun testXmlIncludeNodeViewBinding() {
        val compileTask = makeTask(
            File(assetsAndroidDir, "app/src/main/res/layout/activity_view_binding_include.xml"),
        )

        val baseClassCompiler = DataBindingGenBaseClassesCompiler(context, mockParentDisposable)
        val result = baseClassCompiler.compile(compileTask)
        assertTrue(result.isAllSuccess)
        checkOutputFiles(result, listOf(
            "com/example/myapplication/databinding/ActivityViewBindingIncludeBinding.java",
            "layout/activity_view_binding_include.xml",
        ))

        checkInclude(
            "com/example/myapplication/databinding/ActivityViewBindingIncludeBinding.java",
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
//                "com/example/myapplication/databinding/ActivityDataBindingNewBinding.java",
                "com/example/myapplication/DataBindingInfo.kt",
                "layout/activity_data_binding_new.xml",
            ))

            val mapperCompiler = DataBindingGenMapperCompiler(context, mockParentDisposable)
            val result2 = mapperCompiler.compile(createBindingTask(compileTask, result))
            assertTrue(result2.isAllSuccess)
            checkDataBindingOutputs(compileTask, result2, 1)
            context.deployedFiles.addAll(result2.outputs)
            assertFallback()
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
//                "com/example/myapplication/databinding/ActivityDataBindingNew2Binding.java",
                "com/example/myapplication/DataBindingInfo.kt",
                "layout/activity_data_binding_new2.xml",
            ))

            val mapperCompiler = DataBindingGenMapperCompiler(context, mockParentDisposable)
            val result2 = mapperCompiler.compile(createBindingTask(compileTask, result))
            assertTrue(result2.isAllSuccess)
            checkDataBindingOutputs(compileTask, result2, 1)
            context.deployedFiles.addAll(result2.outputs)
            assertFallback()
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
//                "com/example/myapplication/databinding/ActivityDataBindingOldIncludeBinding.java",
                "com/example/myapplication/DataBindingInfo.kt",
                "layout/activity_data_binding_old_include.xml",
            ))

            val mapperCompiler = DataBindingGenMapperCompiler(context, mockParentDisposable)
            val result2 = mapperCompiler.compile(createBindingTask(compileTask, result))
            assertTrue(result2.isAllSuccess)
            checkDataBindingOutputs(compileTask, result2, 1, listOf(
                "com/example/myapplication/databinding/ActivityDataBindingIncludeBindingImpl.java" // include node
            ))
            checkInclude(
                "com/example/myapplication/databinding/ActivityDataBindingOldIncludeBinding.java",
                "ActivityDataBindingIncludeBinding",
                "includeTestLayout",
            )
            context.deployedFiles.addAll(result2.outputs)
            assertFallback()
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
            assertFallback()
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
            assertFallback()
        }
    }

    // assumed failed on this case. databinding is a thing of the past.
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
            assertFallback()
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
        val bindingTask = createBindingTask(resourceTask, baseResult)
        val mapperResult = mapperCompiler.compile(bindingTask)
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
            if (!result.isAllSuccess) {
                result.failedFiles.forEach { f ->
                    println("FAILED FILE: ${f.file.file.name}")
                    println("ERROR MESSAGES: ${f.getFailure().errorMessages}")
                }
            }
            assertTrue(result.isAllSuccess, "expected source-only incremental compile success")
            assertTrue(result.outputs.none { it.file.name.contains("ActivityDataBindingJavaDemoBindingImpl") },
                "unexpected databinding impl regenerated: ${result.outputs.joinToString { it.file.name }}")

            val afterContent = bindingImplFile.readText()
            assertTrue(afterContent.contains("name"), "binding impl should still reference old name")
            assertTrue(!afterContent.contains("displayName"), "binding impl should not contain displayName")
            assertFallback()
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

        val bindingTask = createBindingTask(resourceTask, baseResult)
        val mapperCompiler = DataBindingGenMapperCompiler(stableContext, mockParentDisposable)
        val mapperResult = mapperCompiler.compile(bindingTask)
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
            if (!result.isAllSuccess) {
                result.failedFiles.forEach { f ->
                    println("FAILED FILE: ${f.file.file.name}")
                    println("ERROR MESSAGES: ${f.getFailure().errorMessages}")
                }
            }
            assertTrue(result.isAllSuccess, "expected source-only incremental compile success")
            assertTrue(result.outputs.none { it.file.name.contains("ActivityDataBindingKotlinDemoBindingImpl") },
                "unexpected databinding impl regenerated: ${result.outputs.joinToString { it.file.name }}")

            val afterContent = bindingImplFile.readText()
            assertTrue(afterContent.contains("getName()") || afterContent.contains("name"),
                "binding impl should still reference old name")
            assertTrue(!afterContent.contains("displayName"), "binding impl should not contain displayName")
            assertFallback()
        }
    }

    open fun assertFallback() {
        assertFalse(DataBindingArgsManager.isKaAptRetryAptSuccess)
    }

    open fun checkOutputFiles(compileResult: CompileResult, expect: List<String>) {
        expect.forEach { expectFilePath ->
            val isJava = expectFilePath.endsWith(".java")
            val isKotlin = expectFilePath.endsWith(".kt")
            val outputType = if (isJava) CompileOutput.Type.Java else if (isKotlin) CompileOutput.Type.Kotlin else CompileOutput.Type.ResXml
            val outputFile = compileResult.outputs.find { it.relativeFile.path == expectFilePath }
            assertTrue(outputFile != null,
                "File $expectFilePath does not exist in output, all outputs: ${compileResult.outputs.joinToString("\n") { it.file.path }}")
            assertTrue(outputFile.file.exists(), "File $expectFilePath does not exist")
            assertEquals(outputType, outputFile.type, "File $expectFilePath has incorrect type")
        }
        assertEquals(expect.size, compileResult.outputs.size, "Expect ${expect.size} files, " +
                "but got ${compileResult.outputs.size}: ${compileResult.outputs.joinToString("\n") { it.file.path }}")
    }

    private fun checkDataBindingOutputs(compileTask: CompileTask, compileResult: CompileResult, incTimes: Int, additionalOutput: List<String> = listOf()) {
        val module = compileTask.files.first().module
        val packagePath = (context.getModulePackageName(module)
            ?: throw IllegalStateException("module package name not found for ${module.name}"))
            .replace(".", "/")
        val commonMapperOutputs = if (module.moduleType == com.sickworm.intellij.jugg.project.data.ModuleInfo.Type.Library) {
            listOf("androidx/databinding/DataBindingComponent.java")
        } else {
            listOf(
                "androidx/databinding/DataBinderMapperImpl.java",
                "androidx/databinding/DataBindingComponent.java",
            )
        }
        val outputFiles = compileTask.files.flatMap {
            val file = it.file
            listOf(
                "$packagePath/databinding/${file.nameWithoutExtension.toCamelCase()}Binding.java",
                "$packagePath/databinding/${file.nameWithoutExtension.toCamelCase()}BindingImpl.java",
                "layout/${file.name}",
            )
        }
        val base = commonMapperOutputs + listOf(
            "$packagePath/BR.java",
            "$packagePath/DataBinderMapper_IncrementalHolder.java",
            "$packagePath/DataBinderMapperImpl.java",
            "$packagePath/DataBinderMapperImpl_Full.java",
            "$packagePath/DataBinderMapperImpl_Inc_$incTimes.java",
        )
        checkOutputFiles(compileResult, base + outputFiles + additionalOutput)
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

        private fun makeTask(moduleName: String, baseDir: File, vararg files: File): CompileTask {
            val module = context.modules[moduleName]
                ?: throw IllegalStateException("module not found: $moduleName, all: ${context.modules.keys}")
            return CompileTask(
                files.map {
                    CompileFile(
                        CompileFile.Type.Resource,
                        it,
                        baseDir,
                        module
                    )
                },
                CompileHelper.outputDir,
                CompileStatusHolder.DEFAULT,
            )
        }

        private fun checkInclude(file: String, type: String, name: String) {
            val javaContent = CompileHelper.javaOutputDir.resolve(file).readLines()
            val includeField = javaContent.find { it.contains("$name;") }
            assertNotNull(includeField)
            assertTrue(includeField.contains("$type $name;"), "include field is not generated correct type, actual: \"$includeField\"")
        }


        private fun createBindingTask(task: CompileTask, result: CompileResult): CompileTask {
            val module = task.files.firstOrNull()?.module!!
            return CompileTask(
                task.files + listOf(
                    result.outputs.find { it.type == CompileOutput.Type.Kotlin || it.type == CompileOutput.Type.Java }!!
                        .toCompileFile(module)!!
                ),
                CompileHelper.outputDir,
                CompileStatusHolder.DEFAULT,
            )
        }
    }
}
