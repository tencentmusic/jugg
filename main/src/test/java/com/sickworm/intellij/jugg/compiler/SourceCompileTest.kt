package com.sickworm.intellij.jugg.compiler

import com.sickworm.intellij.jugg.compiler.source.SourceCompiler
import com.sickworm.intellij.jugg.deploy.classSigName
import com.sickworm.intellij.jugg.deploy.data.ApkParser
import com.sickworm.intellij.jugg.deploy.toDeployItem
import com.sickworm.intellij.jugg.mock.*
import com.sickworm.intellij.jugg.mock.TestGlobal.assetsAndroidDir
import com.sickworm.intellij.jugg.project.ChangedFile
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SourceCompileTest {

    private val sourceCompiler = SourceCompiler(TestGlobal.context, TestGlobal.mockParentDisposable)

    @Before
    fun init() {
        TestGlobal.clearBuild()
    }

    @After
    fun tearDown() {
    }

    private val twoActivityTask get() = CompileTask(
        listOf(
            CompileFile(
                CompileFile.Type.Kotlin,
                File(assetsAndroidDir, "app/src/main/java/com/example/myapplication/MainActivity.kt"),
                File(assetsAndroidDir, "app/src/main/java/"),
                module = TestGlobal.applicationModule,
            ),
            CompileFile(
                CompileFile.Type.Java,
                File(assetsAndroidDir, "app/src/main/java/com/example/myapplication/MainActivity2.java"),
                File(assetsAndroidDir, "app/src/main/java/"),
                module = TestGlobal.applicationModule,
            )
        ),
        TestGlobal.stagingDir,
        CompileStatusHolder.DEFAULT,
    )

    @Test
    fun kotlinAndJavaCompile() {
        val task = twoActivityTask
        val result = sourceCompiler.compile(task)
        assertCompileResult(task, result)
    }


    @Test
    fun kotlinAndJavaCoreRefrenceCompile() {
        // test cross-reference case (kotlinc -java-source-roots)

        changeAndRevert(
            "MainActivity2.crossReference.java" to "MainActivity2.java",
            "MainActivity.crossReference.kt" to "MainActivity.kt",
        ) { _ ->
            val task = twoActivityTask
            val result = sourceCompiler.compile(task)
            assertCompileResult(task, result)
        }
    }

    @Test
    fun juggAptRetry_shouldTrackAndRollbackChangedKuiklyEntry() {
        val module = TestGlobal.applicationModule
        val baseContext = TestGlobal.context
        val trackingContext = TrackingCompileContext(baseContext)
        val trackingSourceCompiler = SourceCompiler(trackingContext, TestGlobal.mockParentDisposable)

        val sourceBaseDir = module.sourceDirs.first()
        val pageFile = File(sourceBaseDir, "TestPage.kt").apply {
            parentFile.mkdirs()
            writeText(
                """
                package com.example.kuikly
                
                // import com.tencent.kuikly.core.annotations.Page
                
                annotation class Page(val value: String)
                
                @Page("test/page")
                class TestPage
                """.trimIndent(),
            )
        }

        val entryFile = File(module.buildPathInfo.generatedKspSourcePath, "KuiklyCoreEntry.kt").apply {
            parentFile.mkdirs()
            writeText(
                """
                package com.example.kuikly
                
                object KuiklyCoreEntry {
                    fun triggerRegisterPages() {
                    }
                }
                
                broken syntax
                """.trimIndent(),
            )
        }

        try {
            val task = CompileTask(
                files = listOf(
                    CompileFile(
                        type = CompileFile.Type.Kotlin,
                        file = pageFile,
                        baseDir = sourceBaseDir,
                        module = module,
                    ),
                ),
                outputDir = File(TestGlobal.buildDir, "staging_kuikly_retry"),
                compileStatusHolder = CompileStatusHolder.DEFAULT,
            )

            val result = trackingSourceCompiler.compile(task)
            result.printCompileErrors()
            assertTrue(result.isAllSuccess, "Compile should recover by retrying once without JuggApt outputs.")

            val addedPaths = trackingContext.addedChangedFiles.map { it.file.absolutePath }
            assertTrue(
                entryFile.absolutePath in addedPaths,
                "KuiklyCoreEntry should be tracked as changed after JuggApt rewrites it.",
            )
            val removedPaths = trackingContext.removedChangedFiles.map { it.absolutePath }
            assertTrue(
                entryFile.absolutePath in removedPaths,
                "KuiklyCoreEntry should be removed from changed files when retrying without JuggApt outputs.",
            )
            assertEquals(1, removedPaths.count { it == entryFile.absolutePath })
        } finally {
            pageFile.delete()
            entryFile.delete()
        }
    }

    @Test
    fun juggAptRetry_shouldKeepChangedKuiklyEntryWhenOtherKotlinFileFails() {
        val module = TestGlobal.applicationModule
        val trackingContext = TrackingCompileContext(TestGlobal.context)
        val trackingSourceCompiler = SourceCompiler(trackingContext, TestGlobal.mockParentDisposable)
        val sourceBaseDir = module.sourceDirs.first()
        val pageFile = File(sourceBaseDir, "TestPage.kt").apply {
            parentFile.mkdirs()
            writeText(
                """
                package com.example.kuikly

                // import com.tencent.kuikly.core.annotations.Page

                annotation class Page(val value: String)

                @Page("test/page")
                class TestPage
                """.trimIndent(),
            )
        }
        val unrelatedFile = File(sourceBaseDir, "UnrelatedBroken.kt").apply {
            writeText(
                """
                package com.example.kuikly

                class UnrelatedBroken {
                    fun value(): String = missingValue
                }
                """.trimIndent(),
            )
        }
        val entryFile = File(module.buildPathInfo.generatedKspSourcePath, "KuiklyCoreEntry.kt").apply {
            parentFile.mkdirs()
            writeText(
                """
                package com.example.kuikly

                object BridgeManager {
                    fun registerPageRouter(route: String, creator: () -> Any?) = Unit
                }

                object KuiklyCoreEntry {
                    fun triggerRegisterPages() {
                    }
                }
                """.trimIndent(),
            )
        }

        try {
            val task = CompileTask(
                files = listOf(pageFile, unrelatedFile).map { file ->
                    CompileFile(
                        type = CompileFile.Type.Kotlin,
                        file = file,
                        baseDir = sourceBaseDir,
                        module = module,
                    )
                },
                outputDir = File(TestGlobal.buildDir, "staging_kuikly_unrelated_failure"),
                compileStatusHolder = CompileStatusHolder.DEFAULT,
            )

            val result = trackingSourceCompiler.compile(task)
            result.printCompileErrors()
            assertTrue(!result.isAllSuccess, "The unrelated Kotlin error should keep the compile failed.")

            val addedPaths = trackingContext.addedChangedFiles.map { it.file.absolutePath }
            assertTrue(
                entryFile.absolutePath in addedPaths,
                "KuiklyCoreEntry should be tracked after JuggApt rewrites it.",
            )
            val removedPaths = trackingContext.removedChangedFiles.map { it.absolutePath }
            assertTrue(
                entryFile.absolutePath !in removedPaths,
                "An unrelated Kotlin diagnostic must not remove KuiklyCoreEntry from changed files.",
            )
        } finally {
            pageFile.delete()
            unrelatedFile.delete()
            entryFile.delete()
        }
    }

    @Test
    fun romHiddenApi_shouldRecoverWhenSdkAndroidJarShadowsFrameworkJar() {
        val module = TestGlobal.applicationModule
        val sourceBaseDir = File(assetsAndroidDir, "app/src/main/java")
        val childFile = File(sourceBaseDir, "com/sickworm/jugg/demo/testcase/romhiddenapi/RomHiddenApiChild.kt")
        assertTrue(childFile.exists(), "The rom hidden api testcase is missing: $childFile")

        val task = CompileTask(
            files = listOf(
                CompileFile(
                    type = CompileFile.Type.Kotlin,
                    file = childFile,
                    baseDir = sourceBaseDir,
                    module = module,
                ),
            ),
            outputDir = File(TestGlobal.buildDir, "staging_rom_hidden_api"),
            compileStatusHolder = CompileStatusHolder.DEFAULT,
        )

        val result = sourceCompiler.compile(task)
        result.printCompileErrors()
        assertTrue(
            result.isAllSuccess,
            "Compile should recover by retrying once with the SDK android.jar last.",
        )
    }

    @Test
    fun hiltEntryPoint_shouldExtendGeneratedBaseBeforeDex() {
        val sourceDir = File(TestGlobal.buildDir, "hilt_entry_point_source")
        val outputDir = File(TestGlobal.buildDir, "staging_hilt_entry_point")
        val sources = writeHiltEntryPointSources(sourceDir, includeGeneratedBase = true)
        val task = CompileTask(
            files = sources.map { file ->
                CompileFile(
                    type = CompileFile.Type.Java,
                    file = file,
                    baseDir = sourceDir,
                    module = TestGlobal.applicationModule,
                )
            },
            outputDir = outputDir,
            compileStatusHolder = CompileStatusHolder.DEFAULT,
        )

        val result = sourceCompiler.compile(task)
        result.printCompileErrors()
        assertTrue(result.isAllSuccess)
        val classNode = ApkParser()
            .parseDex(result.outputs.map { it.toDeployItem() })
            .classDeployItems
            .flatMap { it.classNodes }
            .associateBy { it.className }

        assertEquals(
            "com.example.hilt.Hilt_TestActivity".classSigName,
            classNode.getValue("com.example.hilt.TestActivity".classSigName).superClass,
        )
        assertEquals(
            "com.example.hilt.Hilt_TestApplication".classSigName,
            classNode.getValue("com.example.hilt.TestApplication".classSigName).superClass,
        )
    }

    @Test
    fun hiltEntryPoint_shouldFailClearlyWhenGeneratedBaseIsMissing() {
        val sourceDir = File(TestGlobal.buildDir, "hilt_missing_base_source")
        val task = CompileTask(
            files = writeHiltEntryPointSources(sourceDir, includeGeneratedBase = false).map { file ->
                CompileFile(CompileFile.Type.Java, file, sourceDir, TestGlobal.applicationModule)
            },
            outputDir = File(TestGlobal.buildDir, "staging_hilt_missing_base"),
            compileStatusHolder = CompileStatusHolder.DEFAULT,
        )

        val result = sourceCompiler.compile(task)

        assertTrue(!result.isAllSuccess)
        assertTrue(result.outputs.isEmpty())
        assertTrue(
            result.failedFiles.any {
                val message = it.getFailure().errorMessages
                "generated base com.example.hilt.Hilt_" in message &&
                    "not found" in message &&
                    "run a full Gradle build" in message
            },
        )
    }

    private fun writeHiltEntryPointSources(sourceDir: File, includeGeneratedBase: Boolean): List<File> {
        val sources = linkedMapOf(
            "dagger/hilt/android/AndroidEntryPoint.java" to """
                package dagger.hilt.android;
                import java.lang.annotation.*;
                @Retention(RetentionPolicy.CLASS)
                @Target(ElementType.TYPE)
                public @interface AndroidEntryPoint {}
            """.trimIndent(),
            "dagger/hilt/android/HiltAndroidApp.java" to """
                package dagger.hilt.android;
                import java.lang.annotation.*;
                @Retention(RetentionPolicy.CLASS)
                @Target(ElementType.TYPE)
                public @interface HiltAndroidApp {}
            """.trimIndent(),
            "com/example/hilt/BaseActivity.java" to """
                package com.example.hilt;
                public class BaseActivity {
                    public String value() { return "base"; }
                }
            """.trimIndent(),
            "com/example/hilt/TestActivity.java" to """
                package com.example.hilt;
                import dagger.hilt.android.AndroidEntryPoint;
                @AndroidEntryPoint
                public class TestActivity extends BaseActivity {
                    @Override public String value() { return super.value(); }
                }
            """.trimIndent(),
            "com/example/hilt/BaseApplication.java" to """
                package com.example.hilt;
                public class BaseApplication {}
            """.trimIndent(),
            "com/example/hilt/TestApplication.java" to """
                package com.example.hilt;
                import dagger.hilt.android.HiltAndroidApp;
                @HiltAndroidApp
                public class TestApplication extends BaseApplication {}
            """.trimIndent(),
        )
        if (includeGeneratedBase) {
            sources["com/example/hilt/Hilt_TestActivity.java"] = """
                package com.example.hilt;
                public class Hilt_TestActivity extends BaseActivity {}
            """.trimIndent()
            sources["com/example/hilt/Hilt_TestApplication.java"] = """
                package com.example.hilt;
                public class Hilt_TestApplication extends BaseApplication {}
            """.trimIndent()
        }
        return sources.map { (relativePath, content) ->
            File(sourceDir, relativePath).apply {
                parentFile.mkdirs()
                writeText(content)
            }
        }
    }

    private fun assertCompileResult(task: CompileTask, result: CompileResult) {
        val mapper: OutputFileMapper = { _ ->
            emptyList()
        }
        assertCompileResult(task, result, mapper)
    }

    private class TrackingCompileContext(
        private val delegate: SimpleCompileContext,
    ) : ICompileContext by delegate {
        val addedChangedFiles = mutableListOf<ChangedFile>()
        val removedChangedFiles = mutableListOf<File>()

        override fun addChangedFile(files: List<ChangedFile>) {
            addedChangedFiles += files
        }

        override fun removeChangedFile(files: List<File>) {
            removedChangedFiles += files
        }
    }
}
