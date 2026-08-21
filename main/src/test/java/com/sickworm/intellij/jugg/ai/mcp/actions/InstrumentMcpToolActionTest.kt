package com.sickworm.intellij.jugg.ai.mcp.actions

import com.intellij.execution.ExecutionResult
import com.intellij.execution.Executor
import com.intellij.execution.configurations.RunProfile
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.sickworm.intellij.jugg.ai.mcp.IMcpRuntime
import com.sickworm.intellij.jugg.ai.mcp.McpErrorCode
import com.sickworm.intellij.jugg.ai.mcp.McpToolStatus
import com.sickworm.intellij.jugg.compiler.BuildTarget
import com.sickworm.intellij.jugg.compiler.CompileUiHandler
import com.sickworm.intellij.jugg.compiler.ForceGradleCompileHelper
import com.sickworm.intellij.jugg.compiler.GradleCompileExecutionResult
import com.sickworm.intellij.jugg.compiler.RemoteSshInfoResult
import com.sickworm.intellij.jugg.compiler.ui.RunResult
import com.sickworm.intellij.jugg.deploy.FullBuildInfo
import com.sickworm.intellij.jugg.deploy.FullBuildInfoSerializer
import com.sickworm.intellij.jugg.deploy.IDeployTargetManager
import com.sickworm.intellij.jugg.deploy.instrument.AndroidTestRunSpec
import com.sickworm.intellij.jugg.deploy.instrument.TestFilter
import com.sickworm.intellij.jugg.ide.bean.JuggGradleCompileOptions
import com.sickworm.intellij.jugg.ide.logic.IJuggConfigurationRunner
import com.sickworm.intellij.jugg.ide.logic.JuggRunInvocationResult
import com.sickworm.intellij.jugg.project.runtime.JuggPathManager
import org.junit.After
import org.junit.Assert
import org.junit.Before
import org.junit.Test
import java.nio.file.Files

class InstrumentMcpToolActionTest {

    @Before
    fun setUp() {
        CompileJobManager.resetForTest()
        McpAppReadyGuard.resetForTest()
    }

    @After
    fun tearDown() {
        CompileJobManager.resetForTest()
        McpAppReadyGuard.resetForTest()
    }

    @Test
    fun testExecuteBuildsSourceAnchoredRunSpecAndForcesAndroidTestTarget() {
        var capturedSpec: AndroidTestRunSpec? = null
        var capturedBuildTarget: BuildTarget? = null
        val runtime = runtimeWithRunner { _, _, _, androidTestRunSpec, buildTarget ->
            capturedSpec = androidTestRunSpec
            capturedBuildTarget = buildTarget
            JuggRunInvocationResult(
                isSuccess = true,
                runResult = RunResult(
                    isGradleCompile = false,
                    isCompileSuccess = true,
                    isDeploySuccess = true,
                    isCancel = false,
                ),
            )
        }

        val result = InstrumentMcpToolAction().execute(
            mapOf(
                "projectDir" to "/fake/project",
                "sourcePath" to "library1/src/androidTest/kotlin/com/example/FooTest.kt",
                "class" to "com.example.FooTest",
                "method" to "bar",
                "runner" to "androidx.test.runner.AndroidJUnitRunner",
                "extras" to mapOf("size" to "large", "clearPackageData" to "true"),
            ),
            runtime,
        )

        Assert.assertEquals(McpToolStatus.OK, result.status)
        Assert.assertEquals(BuildTarget.ANDROID_TEST, capturedBuildTarget)

        val spec = capturedSpec
        Assert.assertNotNull(spec)
        Assert.assertEquals("library1/src/androidTest/kotlin/com/example/FooTest.kt", spec?.sourcePath)
        Assert.assertEquals("com.example.FooTest", spec?.testClass)
        Assert.assertEquals("bar", spec?.testMethod)
        Assert.assertEquals(emptyList<TestFilter>(), spec?.testFilters)
        Assert.assertEquals("androidx.test.runner.AndroidJUnitRunner", spec?.runnerOverride)
        Assert.assertTrue(spec?.extraArgs?.contains("size" to "large") == true)
        Assert.assertTrue(spec?.extraArgs?.contains("clearPackageData" to "true") == true)
    }

    @Test
    fun testExecuteDoesNotRequireAppReadyAfterInstrumentationSuccess() {
        val runtime = runtimeWithRunner(
            runFirstConfiguration = { _, _, _, _, _ ->
                JuggRunInvocationResult(
                    isSuccess = true,
                    runResult = RunResult(
                        isGradleCompile = false,
                        isCompileSuccess = true,
                        isDeploySuccess = true,
                        isCancel = false,
                    ),
                )
            },
            isAppReadyProvider = { false },
        )

        val result = InstrumentMcpToolAction().execute(
            mapOf(
                "projectDir" to "/fake/project",
                "sourcePath" to "library1/src/androidTest/kotlin/com/example/FooTest.kt",
                "class" to "com.example.FooTest",
            ),
            runtime,
        )

        Assert.assertEquals(McpToolStatus.OK, result.status)
        @Suppress("UNCHECKED_CAST")
        val data = result.data as Map<String, Any>
        Assert.assertEquals("success", data["status"])
        Assert.assertEquals(true, data["isCompileSuccess"])
        Assert.assertEquals(true, data["isDeploySuccess"])
    }

    @Test
    fun testExecuteRejectsProjectWithoutAndroidTestBaselineAndExplainsHowToEnableIt() {
        val projectDir = Files.createTempDirectory("jugg-instrument-no-baseline").toFile()
        val sourceFile = projectDir.resolve("app/src/androidTest/java/com/example/FooTest.java")
        sourceFile.parentFile.mkdirs()
        sourceFile.writeText(
            """
            package com.example;

            public class FooTest {
                @org.junit.Test
                public void foo() {}
            }
            """.trimIndent()
        )
        val runtime = runtimeWithRunner { _, _, _, androidTestRunSpec, _ ->
            throw AssertionError("runner should not be invoked without AndroidTest baseline: $androidTestRunSpec")
        }

        val result = InstrumentMcpToolAction().execute(
            mapOf("projectDir" to projectDir.path, "sourcePath" to sourceFile.path),
            runtime,
        )

        Assert.assertEquals(McpToolStatus.ERROR, result.status)
        Assert.assertEquals(McpErrorCode.INVALID_PARAMS, result.errorCode)
        Assert.assertTrue(result.message.contains("enabledAndroidTest=false"))
        Assert.assertTrue(result.message.contains("Enable Android Test"))
        Assert.assertTrue(result.message.contains("Jugg App Run Configuration"))
        Assert.assertTrue(result.message.contains("gradle-build"))
    }

    @Test
    fun testExecuteRequiresSourcePath() {
        val runtime = runtimeWithRunner { _, _, _, androidTestRunSpec, _ ->
            throw AssertionError("runner should not be invoked when sourcePath is missing: $androidTestRunSpec")
        }

        val result = InstrumentMcpToolAction().execute(mapOf("projectDir" to "/fake/project"), runtime)

        Assert.assertEquals(McpToolStatus.ERROR, result.status)
        Assert.assertEquals(McpErrorCode.INVALID_PARAMS, result.errorCode)
        Assert.assertTrue(result.message.contains("sourcePath is required"))
    }

    @Test
    fun testSchemaDoesNotExposePackageOrRegexTargeting() {
        val schema = InstrumentMcpToolAction().definition.inputSchema

        Assert.assertTrue(schema.properties.containsKey("sourcePath"))
        Assert.assertFalse(schema.properties.containsKey("package"))
        Assert.assertFalse(schema.properties.containsKey("testPackage"))
        Assert.assertFalse(schema.properties.containsKey("testsRegex"))
        Assert.assertFalse(schema.properties.containsKey("regex"))
        Assert.assertTrue(schema.required.contains("sourcePath"))
    }

    @Test
    fun testSchemaDoesNotExposeLegacyAliases() {
        val schema = InstrumentMcpToolAction().definition.inputSchema

        Assert.assertFalse(schema.properties.containsKey("clazz"))
        Assert.assertFalse(schema.properties.containsKey("instrumentationRunner"))
        Assert.assertFalse(schema.properties.containsKey("e"))
        Assert.assertTrue(schema.properties.containsKey("class"))
        Assert.assertTrue(schema.properties.containsKey("runner"))
        Assert.assertTrue(schema.properties.containsKey("extras"))
    }

    @Test
    fun testExecuteRejectsRemovedPackageAndRegexArguments() {
        val runtime = runtimeWithRunner { _, _, _, androidTestRunSpec, _ ->
            throw AssertionError("runner should not be invoked for removed params: $androidTestRunSpec")
        }

        val packageResult = InstrumentMcpToolAction().execute(
            mapOf("projectDir" to "/fake/project", "sourcePath" to "FooTest.kt", "package" to "com.example.pkg"),
            runtime,
        )
        val regexResult = InstrumentMcpToolAction().execute(
            mapOf("projectDir" to "/fake/project", "sourcePath" to "FooTest.kt", "testsRegex" to "Login.*"),
            runtime,
        )

        Assert.assertEquals(McpToolStatus.ERROR, packageResult.status)
        Assert.assertEquals(McpErrorCode.INVALID_PARAMS, packageResult.errorCode)
        Assert.assertTrue(packageResult.message.contains("package is not supported"))
        Assert.assertEquals(McpToolStatus.ERROR, regexResult.status)
        Assert.assertEquals(McpErrorCode.INVALID_PARAMS, regexResult.errorCode)
        Assert.assertTrue(regexResult.message.contains("testsRegex is not supported"))
    }

    @Test
    fun testExecuteRejectsLegacyAliasArguments() {
        val runtime = runtimeWithRunner { _, _, _, androidTestRunSpec, _ ->
            throw AssertionError("runner should not be invoked for legacy aliases: $androidTestRunSpec")
        }

        for (legacyArg in listOf("clazz", "instrumentationRunner", "e")) {
            val result = InstrumentMcpToolAction().execute(
                mapOf("projectDir" to "/fake/project", "sourcePath" to "FooTest.kt", legacyArg to "legacy"),
                runtime,
            )

            Assert.assertEquals(McpToolStatus.ERROR, result.status)
            Assert.assertEquals(McpErrorCode.INVALID_PARAMS, result.errorCode)
            Assert.assertTrue(result.message.contains("$legacyArg is not supported"))
        }
    }

    @Test
    fun testExecuteRejectsNonStringExtrasValue() {
        val runtime = runtimeWithRunner { _, _, _, _, _ ->
            throw AssertionError("runner should not be invoked when params are invalid")
        }

        val result = InstrumentMcpToolAction().execute(
            mapOf(
                "projectDir" to "/fake/project",
                "sourcePath" to "library1/src/androidTest/kotlin/com/example/FooTest.kt",
                "extras" to mapOf("size" to 1),
            ),
            runtime,
        )

        Assert.assertEquals(McpToolStatus.ERROR, result.status)
        Assert.assertEquals(McpErrorCode.INVALID_PARAMS, result.errorCode)
        Assert.assertTrue(result.message.contains("extras.size"))
    }

    @Test
    fun testExecuteInfersSingleKotlinTestClassFromSourcePath() {
        val projectDir = Files.createTempDirectory("jugg-instrument").toFile()
        writeFullBuildInfo(projectDir, BuildTarget.ANDROID_TEST)
        val sourceFile = projectDir.resolve("library1/src/androidTest/kotlin/com/example/FooTest.kt")
        sourceFile.parentFile.mkdirs()
        sourceFile.writeText(
            """
            package com.example

            class FooTest {
                @org.junit.Test
                fun bar() {
                }
            }
            """.trimIndent()
        )
        var capturedSpec: AndroidTestRunSpec? = null
        val runtime = runtimeWithRunner { _, _, _, androidTestRunSpec, _ ->
            capturedSpec = androidTestRunSpec
            JuggRunInvocationResult(
                isSuccess = true,
                runResult = RunResult(false, isCompileSuccess = true, isDeploySuccess = true, isCancel = false),
            )
        }

        val result = InstrumentMcpToolAction().execute(
            mapOf(
                "projectDir" to projectDir.path,
                "sourcePath" to "library1/src/androidTest/kotlin/com/example/FooTest.kt",
                "method" to "bar",
            ),
            runtime,
        )

        Assert.assertEquals(McpToolStatus.OK, result.status)
        Assert.assertEquals("com.example.FooTest", capturedSpec?.testClass)
        Assert.assertEquals("bar", capturedSpec?.testMethod)
    }

    @Test
    fun testExecuteRequiresClassWhenSourcePathContainsMultipleTestClasses() {
        val projectDir = Files.createTempDirectory("jugg-instrument").toFile()
        val sourceFile = projectDir.resolve("library1/src/androidTest/java/com/example/FooTest.java")
        sourceFile.parentFile.mkdirs()
        sourceFile.writeText(
            """
            package com.example;

            public class FooTest {
                @org.junit.Test
                public void foo() {}
            }

            class BarTest {
                @org.junit.jupiter.api.Test
                void bar() {}
            }
            """.trimIndent()
        )
        val runtime = runtimeWithRunner { _, _, _, androidTestRunSpec, _ ->
            throw AssertionError("runner should not be invoked for ambiguous source: $androidTestRunSpec")
        }

        val result = InstrumentMcpToolAction().execute(
            mapOf("projectDir" to projectDir.path, "sourcePath" to sourceFile.path),
            runtime,
        )

        Assert.assertEquals(McpToolStatus.ERROR, result.status)
        Assert.assertEquals(McpErrorCode.INVALID_PARAMS, result.errorCode)
        Assert.assertTrue(result.message.contains("multiple test classes found in sourcePath"))
        Assert.assertTrue(result.message.contains("com.example.FooTest"))
        Assert.assertTrue(result.message.contains("com.example.BarTest"))
    }

    @Test
    fun testExecuteRejectsMissingMethodInResolvedSourceClass() {
        val projectDir = Files.createTempDirectory("jugg-instrument").toFile()
        val sourceFile = projectDir.resolve("library1/src/androidTest/kotlin/com/example/FooTest.kt")
        sourceFile.parentFile.mkdirs()
        sourceFile.writeText(
            """
            package com.example

            class FooTest {
                @Test
                fun existing() {
                }
            }
            """.trimIndent()
        )
        val runtime = runtimeWithRunner { _, _, _, androidTestRunSpec, _ ->
            throw AssertionError("runner should not be invoked for missing method: $androidTestRunSpec")
        }

        val result = InstrumentMcpToolAction().execute(
            mapOf(
                "projectDir" to projectDir.path,
                "sourcePath" to sourceFile.path,
                "class" to "com.example.FooTest",
                "method" to "missing",
            ),
            runtime,
        )

        Assert.assertEquals(McpToolStatus.ERROR, result.status)
        Assert.assertEquals(McpErrorCode.INVALID_PARAMS, result.errorCode)
        Assert.assertTrue(result.message.contains("method is not found in sourcePath"))
    }

    private fun runtimeWithRunner(
        isAppReadyProvider: () -> Boolean = { true },
        runFirstConfiguration: (
            isRpcMode: Boolean,
            isSkipDeploy: Boolean,
            isAlwaysRestartApp: Boolean,
            androidTestRunSpec: AndroidTestRunSpec?,
            buildTargetOverride: BuildTarget?,
        ) -> JuggRunInvocationResult,
    ): IMcpRuntime {
        return object : com.sickworm.intellij.jugg.ai.mcp.TestMcpRuntime() {
            override val logger: Logger = Logger.getInstance("InstrumentMcpToolActionTest")
            override val deployTargetManager: IDeployTargetManager
                get() = throw UnsupportedOperationException("not used in this test")
            override val forceGradleCompileHelper: ForceGradleCompileHelper = object : ForceGradleCompileHelper() {
                override fun executeGradleCompile(autoConfirm: Boolean, useCleanAndReinstall: Boolean) {
                    throw UnsupportedOperationException("not used in this test")
                }

                override fun executeGradleCompileBlocking(
                    autoConfirm: Boolean,
                    useCleanAndReinstall: Boolean,
                ): GradleCompileExecutionResult {
                    throw UnsupportedOperationException("not used in this test")
                }

                override fun resolveExecutionType(): String = "local"

                override fun requestRemoteSshInfo(requestedBy: String, reason: String): RemoteSshInfoResult {
                    return RemoteSshInfoResult(approved = false, message = "not used in this test")
                }
            }
            override val juggConfigurationRunner: IJuggConfigurationRunner = object : IJuggConfigurationRunner {
                override val isCompiling: Boolean = false

                override fun runTask(
                    options: JuggGradleCompileOptions,
                    compileUiHandler: CompileUiHandler,
                    executor: Executor?,
                    runProfile: RunProfile?,
                    androidTestRunSpec: AndroidTestRunSpec?,
                ): ExecutionResult {
                    throw UnsupportedOperationException("not used in this test")
                }

                override fun forceReInstallNextTime() {
                    throw UnsupportedOperationException("not used in this test")
                }

                override fun runFirstConfiguration(
                    isRpcMode: Boolean,
                    isSkipDeploy: Boolean,
                    isAlwaysRestartApp: Boolean,
                ): JuggRunInvocationResult {
                    throw UnsupportedOperationException("not used in this test")
                }

                override fun runFirstConfigurationWithSpec(
                    isRpcMode: Boolean,
                    isSkipDeploy: Boolean,
                    isAlwaysRestartApp: Boolean,
                    androidTestRunSpec: AndroidTestRunSpec?,
                    buildTargetOverride: BuildTarget?,
                    targetDeviceSerial: String?,
                ): JuggRunInvocationResult {
                    return runFirstConfiguration(
                        isRpcMode,
                        isSkipDeploy,
                        isAlwaysRestartApp,
                        androidTestRunSpec,
                        buildTargetOverride,
                    )
                }
            }

            override fun isAppReadyDeploy(): Boolean = isAppReadyProvider()
        }
    }

    private fun writeFullBuildInfo(projectDir: java.io.File, buildTarget: BuildTarget) {
        val pathManager = JuggPathManager(projectDir)
        val fullBuildInfoFile = java.io.File(pathManager.compileContextDbDir, "full_build_info.json")
        fullBuildInfoFile.parentFile?.mkdirs()
        fullBuildInfoFile.writeText(
            FullBuildInfoSerializer().serialize(
                FullBuildInfo(
                    compileCommand = "./gradlew :app:assembleDebug",
                    buildTarget = buildTarget,
                    createdAt = 123L,
                )
            ),
            Charsets.UTF_8,
        )
    }
}
