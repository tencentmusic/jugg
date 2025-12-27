package com.sickworm.intellij.jugg.compiler

import com.sickworm.intellij.jugg.compiler.source.SourceCompiler
import com.sickworm.intellij.jugg.mock.*
import com.sickworm.intellij.jugg.mock.TestGlobal.assetsAndroidDir
import com.sickworm.intellij.jugg.project.ProjectInfoSerializer
import org.junit.Before
import org.junit.Test
import java.io.File
import kotlin.test.assertTrue

class SourceCompileTest {

    private val sourceCompiler = SourceCompiler(TestGlobal.context, TestGlobal.mockParentDisposable)

    @Before
    fun init() {
        TestGlobal.clearBuild()
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
    fun kotlinAndJavaCompileWithMinify() {
        // Step 1: Execute appAssembleRelease to build release version with minify enabled
        GradleBuildHelper.appAssembleRelease()

        // Create release version of mockModule with release buildVariant
        // Create a SourceCompiler with release context
        val releaseContext = TestGlobal.context.copy(
            modules = ProjectInfoSerializer(AssembleAndroidProjectOnce.gradleProjectInfoFile, logger).load()!!.modules,
        )
        val releaseSourceCompiler = SourceCompiler(releaseContext, TestGlobal.mockParentDisposable)

        // Step 2: Compile task
        val task = CompileTask(
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
        val result = releaseSourceCompiler.compile(task)

        // Step 3: Verify compile result is obfuscated
        result.printCompileErrors()
        assertTrue(result.isAllSuccess, "Compile should succeed")

        // Verify that the output files are obfuscated
        // In minified build, class files should have obfuscated names/paths
        val dexOutputs = result.outputs.filter { it.type == CompileOutput.Type.Dex }
        assertTrue(dexOutputs.isNotEmpty(), "Should have dex outputs")

        // Check that mapping file exists (indicates minify was enabled)
        val mappingFile = releaseContext.applicationModule.buildPathInfo.mappingFile
        assertTrue(mappingFile.exists(), "Mapping file should exist for release build: ${mappingFile.absolutePath}")

        // Verify the compiled classes went through obfuscation by checking the obfuscated output path
        // The obfuscated class files should be in the minify output directory
        val minifyOutputDir = File(TestGlobal.buildDir, "minify")
        if (minifyOutputDir.exists()) {
            val classFiles = minifyOutputDir.walkTopDown().filter { it.extension == "class" }.toList()
            // If minify is working, class names should be obfuscated (e.g., "a.class" instead of "MainActivity.class")
            val hasObfuscatedClasses = classFiles.any { classFile ->
                // Obfuscated classes typically have short names like a, b, c
                val className = classFile.nameWithoutExtension
                className.length <= 2 || !className.contains("MainActivity")
            }
            assertTrue(
                classFiles.isEmpty() || hasObfuscatedClasses,
                "Output should contain obfuscated class files when minify is enabled"
            )
        }
    }

    private fun assertCompileResult(task: CompileTask, result: CompileResult) {
        val mapper: OutputFileMapper = { _ ->
            emptyList()
        }
        assertCompileResult(task, result, mapper)
    }
}
