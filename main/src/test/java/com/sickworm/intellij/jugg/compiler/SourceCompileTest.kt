package com.sickworm.intellij.jugg.compiler

import com.sickworm.intellij.jugg.compiler.source.SourceCompiler
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
