package com.sickworm.intellij.jugg.compiler

import com.sickworm.intellij.jugg.compiler.databinding.DataBindingArgsManager
import com.sickworm.intellij.jugg.compiler.source.SourceCompiler
import com.sickworm.intellij.jugg.mock.*
import com.sickworm.intellij.jugg.mock.TestGlobal.assetsAndroidDir
import com.sickworm.intellij.jugg.project.ChangedFile
import com.sickworm.intellij.jugg.project.ProjectInfoSerializer
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SourceCompileDataBindingTest {

    private val sourceCompiler = SourceCompiler(TestGlobal.context, TestGlobal.mockParentDisposable)

    @Before
    fun init() {
        TestGlobal.clearBuild()
        DataBindingArgsManager.isForceUseAptInTest = null
    }

    @After
    fun tearDown() {
        DataBindingArgsManager.isForceUseAptInTest = null
    }

    @Test
    fun dataBindingAptRetry_shouldSucceedWhenDataBindingFileHasError() {
        DataBindingArgsManager.isForceUseAptInTest = true

        val module = TestGlobal.applicationModule
        val sourceBaseDir = module.sourceDirs.first()

        // Create a broken DataBinding file that references non-existent class
        val brokenBindingFile = File(sourceBaseDir, "BrokenBinding.java").apply {
            parentFile.mkdirs()
            writeText("""
                package com.example.myapplication;

                // This simulates a DataBinding generated file with error
                public class BrokenBinding {
                    public void bind(NonExistentClass obj) {
                        // This will cause compilation error
                    }
                }
            """.trimIndent())
        }

        try {
            val task = CompileTask(
                files = listOf(
                    CompileFile(
                        type = CompileFile.Type.Java,
                        file = brokenBindingFile,
                        baseDir = sourceBaseDir,
                        module = module,
                    ),
                ),
                outputDir = File(TestGlobal.buildDir, "staging_databinding_retry"),
                compileStatusHolder = CompileStatusHolder.DEFAULT,
            )

            val result = sourceCompiler.compile(task)
            result.printCompileErrors()

            // Without retry strategy, this would fail
            // With retry strategy, it should detect "databinding" in error and retry
            assertTrue(!result.isAllSuccess, "Broken DataBinding file should cause compile failure")
        } finally {
            brokenBindingFile.delete()
        }
    }

    @Test
    fun dataBindingAptRetry_shouldRetryWithoutDataBindingThenRecompile() {
        // isFallbackApt is always true, retry strategy is always active
        DataBindingArgsManager.isForceUseAptInTest = true

        val module = TestGlobal.applicationModule
        val sourceBaseDir = module.sourceDirs.first()

        // Create a valid Kotlin source file (represents user's normal code change)
        val validKotlinFile = File(sourceBaseDir, "DataBindingRetryTestHelper.kt").apply {
            parentFile.mkdirs()
            writeText(
                """
                package com.example.myapplication

                class DataBindingRetryTestHelper {
                    fun doWork(): String = "hello"
                }
                """.trimIndent()
            )
        }

        // Create a databinding-generated Java file with a databinding-related error.
        // This simulates the case where databinding generates code referencing a new accessor
        // that doesn't exist yet (e.g. "Could not find accessor ... name2").
        val brokenDataBindingFile = File(sourceBaseDir, "DataBindingRetryBroken.java").apply {
            parentFile.mkdirs()
            writeText(
                """
                package com.example.myapplication;

                // [databinding] simulated databinding generated file
                public class DataBindingRetryBroken {
                    // references DataBindingRetryTestHelper which compiles fine standalone
                    public String getValue(DataBindingRetryTestHelper helper) {
                        return helper.doWork();
                    }
                    // references non-existent accessor, simulating databinding stale reference
                    public Object getStaleAccessor() {
                        return DataBindingNonExistentAccessor.name2;
                    }
                }
                """.trimIndent()
            )
        }

        try {
            val task = CompileTask(
                files = listOf(
                    CompileFile(
                        type = CompileFile.Type.Kotlin,
                        file = validKotlinFile,
                        baseDir = sourceBaseDir,
                        module = module,
                    ),
                    CompileFile(
                        type = CompileFile.Type.Java,
                        file = brokenDataBindingFile,
                        baseDir = sourceBaseDir,
                        module = module,
                    ),
                ),
                outputDir = File(TestGlobal.buildDir, "staging_databinding_apt_retry"),
                compileStatusHolder = CompileStatusHolder.DEFAULT,
            )

            val result = sourceCompiler.compile(task)
            result.printCompileErrors()

            // The Kotlin file should compile successfully even when databinding file is broken,
            // because the retry strategy should:
            // 1. Detect databinding-related error after initial compile failure
            // 2. Retry compile without databinding files -> succeeds (Kotlin file compiles)
            // 3. Recompile databinding files alone -> still fails (references non-existent class)
            // 4. Final result: only the databinding file fails, the Kotlin file succeeds
            // Overall: not all success, but the valid file is compiled
            val successPaths = result.successFiles.map { it.file.file.name }
            assertTrue(
                "DataBindingRetryTestHelper.kt" in successPaths,
                "Valid Kotlin file should compile successfully through databinding apt retry. " +
                    "Success files: $successPaths"
            )
        } finally {
            validKotlinFile.delete()
            brokenDataBindingFile.delete()
        }
    }

    @Test
    fun dataBindingAptRetry_shouldGiveUpWhenNonDataBindingFilesAlsoFail() {
        // isFallbackApt is always true, retry strategy is always active
        DataBindingArgsManager.isForceUseAptInTest = true

        val module = TestGlobal.applicationModule
        val sourceBaseDir = module.sourceDirs.first()

        // Create a broken non-databinding Kotlin file (has syntax error)
        val brokenKotlinFile = File(sourceBaseDir, "DataBindingRetryBrokenKotlin.kt").apply {
            parentFile.mkdirs()
            writeText(
                """
                package com.example.myapplication

                class DataBindingRetryBrokenKotlin {
                    fun broken() {
                        val x: NonExistentType = null
                    }
                }
                """.trimIndent()
            )
        }

        // Create a databinding file with error
        val brokenDataBindingFile = File(sourceBaseDir, "DataBindingRetryGiveUp.java").apply {
            parentFile.mkdirs()
            writeText(
                """
                package com.example.myapplication;

                // [databinding] simulated
                public class DataBindingRetryGiveUp {
                    public Object getAccessor() {
                        return DataBindingGiveUpNonExistent.name;
                    }
                }
                """.trimIndent()
            )
        }

        try {
            val task = CompileTask(
                files = listOf(
                    CompileFile(
                        type = CompileFile.Type.Kotlin,
                        file = brokenKotlinFile,
                        baseDir = sourceBaseDir,
                        module = module,
                    ),
                    CompileFile(
                        type = CompileFile.Type.Java,
                        file = brokenDataBindingFile,
                        baseDir = sourceBaseDir,
                        module = module,
                    ),
                ),
                outputDir = File(TestGlobal.buildDir, "staging_databinding_giveup"),
                compileStatusHolder = CompileStatusHolder.DEFAULT,
            )

            val result = sourceCompiler.compile(task)
            result.printCompileErrors()

            // Both files are broken, retry without databinding should still fail (Kotlin file is broken)
            // Strategy should give up and return original error
            assertTrue(!result.isAllSuccess, "Should fail when non-databinding files also have errors")
        } finally {
            brokenKotlinFile.delete()
            brokenDataBindingFile.delete()
        }
    }

    /**
     * Verifies that prepareSourceCompile-level DataBinding failure triggers retry.
     * isFallbackApt is always true, so the retry strategy is always active.
     *
     * Bug scenario (commit d4e58febe):
     * When DataBinding mapper generation fails in prepareSourceCompile (because apt
     * cannot resolve Kotlin class fields without .class on classpath), doModuleCompile
     * should NOT return immediately. Instead it should:
     * 1. Skip DataBinding mapper generation
     * 2. Compile Kotlin/Java sources to produce .class files
     * 3. Retry DataBinding mapper generation with .class available
     *
     * This test covers the shouldRetryPrepareSourceCompile path in DataBindingAptRetryStrategy.
     */
    @Test
    fun dataBindingAptRetry_shouldRetryPrepareSourceCompileWhenMapperGenerationFails() {
        DataBindingArgsManager.isForceUseAptInTest = true

        val module = TestGlobal.applicationModule
        val sourceBaseDir = module.sourceDirs.first()
        val argsManager = DataBindingArgsManager(TestGlobal.context, module)

        // Create a valid Kotlin file (this must compile successfully in the retry path)
        val validKotlinFile = File(sourceBaseDir, "PrepareRetryKotlinHelper.kt").apply {
            parentFile.mkdirs()
            writeText(
                """
                package com.example.myapplication

                class PrepareRetryKotlinHelper {
                    fun doWork(): String = "prepare-retry"
                }
                """.trimIndent()
            )
        }

        // Create DataBinding trigger file so processDataBindingMapper is activated
        DataBindingArgsManager.isForceUseAptInTest = true
        val triggerFile = argsManager.dataBindingAptSourceTrigger
        triggerFile.parentFile.mkdirs()
        triggerFile.writeText(
            """
            package ${argsManager.packageName};
            @androidx.databinding.BindingBuildInfo
            public class DataBindingInfo {}
            """.trimIndent()
        )

        // Create a layout info XML to trigger DataBinding mapper generation
        val layoutInfoDir = argsManager.tempDataBindingLayoutXmlDir
        layoutInfoDir.mkdirs()
        val layoutInfoFile = File(layoutInfoDir, "activity_prepare_retry_test-layout.xml").apply {
            writeText(
                """
                <?xml version="1.0" encoding="utf-8" standalone="yes"?>
                <Layout directory="layout" filePath="res/layout/activity_prepare_retry_test.xml"
                    isBindingData="true" isMerge="false" modulePackage="com.example.myapplication">
                    <Variables name="helper"
                        declared="true"
                        type="com.example.myapplication.PrepareRetryKotlinHelper">
                    </Variables>
                    <Targets>
                        <Target tag="layout/activity_prepare_retry_test_0"
                            view="LinearLayout">
                        </Target>
                    </Targets>
                </Layout>
                """.trimIndent()
            )
        }

        try {
            val task = CompileTask(
                files = listOf(
                    CompileFile(
                        type = CompileFile.Type.Kotlin,
                        file = validKotlinFile,
                        baseDir = sourceBaseDir,
                        module = module,
                    ),
                    CompileFile(
                        type = CompileFile.Type.Java,
                        file = triggerFile,
                        baseDir = argsManager.dataBindingPreProcessorSources,
                        module = module,
                    ),
                ),
                outputDir = File(TestGlobal.buildDir, "staging_prepare_retry"),
                compileStatusHolder = CompileStatusHolder.DEFAULT,
            )

            val result = sourceCompiler.compile(task)
            result.printCompileErrors()

            // Before fix: prepareSourceCompile would fail on DataBinding mapper generation
            // (apt can't resolve Kotlin class without .class on classpath)
            // and doModuleCompile would return the error immediately.
            //
            // After fix: doModuleCompile should detect this condition, skip DataBinding,
            // compile Kotlin first, then retry DataBinding mapper generation.
            //
            // Note: even if the full DataBinding mapper pipeline fails in this unit test
            // (due to missing gradle intermediates), the Kotlin file should still compile.
            // The key verification is that the code path does NOT short-circuit at
            // prepareSourceCompile failure.
            val kotlinSucceeded = result.details.any {
                it.isSuccess && it.file.file.name == "PrepareRetryKotlinHelper.kt"
            }
            assertTrue(
                kotlinSucceeded,
                "Kotlin file should compile successfully even when DataBinding mapper generation " +
                    "initially fails in prepareSourceCompile. The retry should skip DataBinding " +
                    "and compile language sources first."
            )

            // Verify no duplicate class outputs exist in the result.
            // Bug scenario: in the retry path, the second compileLanguageStages call
            // re-compiled Kotlin files that were already compiled in the first call,
            // causing duplicate .class outputs that crash D8 with
            // "Type ... is defined multiple times".
            val classOutputPaths = result.outputs
                .filter { it.type == CompileOutput.Type.Class }
                .map { it.file.absolutePath }
            val duplicateClasses = classOutputPaths.groupBy { it }
                .filter { it.value.size > 1 }
                .keys
            assertTrue(
                duplicateClasses.isEmpty(),
                "Compile result should not contain duplicate class outputs. " +
                    "Duplicates: $duplicateClasses"
            )
        } finally {
            validKotlinFile.delete()
            layoutInfoFile.delete()
        }
    }

}
