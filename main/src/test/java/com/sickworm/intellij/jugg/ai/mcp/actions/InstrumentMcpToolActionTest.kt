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
import com.sickworm.intellij.jugg.deploy.IDeployTargetManager
import com.sickworm.intellij.jugg.deploy.instrument.AndroidTestRunSpec
import com.sickworm.intellij.jugg.deploy.instrument.TestFilter
import com.sickworm.intellij.jugg.ide.bean.JuggGradleCompileOptions
import com.sickworm.intellij.jugg.ide.logic.IJuggConfigurationRunner
import com.sickworm.intellij.jugg.ide.logic.JuggRunInvocationResult
import org.junit.After
import org.junit.Assert
import org.junit.Before
import org.junit.Test

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
    fun testExecuteBuildsRunSpecAndForcesAndroidTestTarget() {
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
                "class" to "com.example.FooTest#bar,com.example.BarTest",
                "package" to "com.example.pkg",
                "testsRegex" to "Login.*",
                "runner" to "androidx.test.runner.AndroidJUnitRunner",
                "extras" to mapOf("size" to "large", "clearPackageData" to "true"),
            ),
            runtime,
        )

        Assert.assertEquals(McpToolStatus.OK, result.status)
        Assert.assertEquals(BuildTarget.ANDROID_TEST, capturedBuildTarget)

        val spec = capturedSpec
        Assert.assertNotNull(spec)
        Assert.assertEquals(
            listOf(
                TestFilter("com.example.FooTest", "bar"),
                TestFilter("com.example.BarTest", null),
            ),
            spec?.testFilters,
        )
        Assert.assertEquals("androidx.test.runner.AndroidJUnitRunner", spec?.runnerOverride)
        Assert.assertTrue(spec?.extraArgs?.contains("package" to "com.example.pkg") == true)
        Assert.assertTrue(spec?.extraArgs?.contains("tests_regex" to "Login.*") == true)
        Assert.assertTrue(spec?.extraArgs?.contains("size" to "large") == true)
        Assert.assertTrue(spec?.extraArgs?.contains("clearPackageData" to "true") == true)
    }

    @Test
    fun testExecuteWithoutClassRunsWholeModule() {
        var capturedSpec: AndroidTestRunSpec? = null
        val runtime = runtimeWithRunner { _, _, _, androidTestRunSpec, _ ->
            capturedSpec = androidTestRunSpec
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

        val result = InstrumentMcpToolAction().execute(mapOf("projectDir" to "/fake/project"), runtime)

        Assert.assertEquals(McpToolStatus.OK, result.status)
        Assert.assertEquals(emptyList<TestFilter>(), capturedSpec?.testFilters)
        Assert.assertEquals(emptyList<Pair<String, String>>(), capturedSpec?.extraArgs)
    }

    @Test
    fun testExecuteRejectsNonStringExtrasValue() {
        val runtime = runtimeWithRunner { _, _, _, _, _ ->
            throw AssertionError("runner should not be invoked when params are invalid")
        }

        val result = InstrumentMcpToolAction().execute(
            mapOf(
                "projectDir" to "/fake/project",
                "extras" to mapOf("size" to 1),
            ),
            runtime,
        )

        Assert.assertEquals(McpToolStatus.ERROR, result.status)
        Assert.assertEquals(McpErrorCode.INVALID_PARAMS, result.errorCode)
        Assert.assertTrue(result.message.contains("extras.size"))
    }

    private fun runtimeWithRunner(
        runFirstConfiguration: (
            isRpcMode: Boolean,
            isSkipDeploy: Boolean,
            isAlwaysRestartApp: Boolean,
            androidTestRunSpec: AndroidTestRunSpec?,
            buildTargetOverride: BuildTarget?,
        ) -> JuggRunInvocationResult,
    ): IMcpRuntime {
        return object : IMcpRuntime {
            override val logger: Logger = Logger.getInstance("InstrumentMcpToolActionTest")
            override val project: Project
                get() = throw UnsupportedOperationException("not used in this test")
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

            override fun isAppReadyDeploy(): Boolean = true
        }
    }
}
