package com.sickworm.intellij.jugg.ai.mcp.actions

import com.intellij.execution.ExecutionResult
import com.intellij.execution.Executor
import com.intellij.execution.configurations.RunProfile
import com.intellij.openapi.project.Project
import com.sickworm.intellij.jugg.ai.mcp.IMcpRuntime
import com.sickworm.intellij.jugg.ai.mcp.McpToolStatus
import com.sickworm.intellij.jugg.compiler.CompileUiHandler
import com.sickworm.intellij.jugg.compiler.ForceGradleCompileHelper
import com.sickworm.intellij.jugg.compiler.GradleCompileExecutionResult
import com.sickworm.intellij.jugg.compiler.RemoteSshInfoResult
import com.sickworm.intellij.jugg.compiler.ui.RunResult
import com.sickworm.intellij.jugg.deploy.IDeployTargetManager
import com.sickworm.intellij.jugg.deploy.instrument.AndroidTestRunSpec
import com.sickworm.intellij.jugg.ide.bean.JuggGradleCompileOptions
import com.sickworm.intellij.jugg.ide.logic.IJuggConfigurationRunner
import com.sickworm.intellij.jugg.ide.logic.JuggRunInvocationResult
import org.junit.After
import org.junit.Assert
import org.junit.Before
import org.junit.Test

/**
 * CleanReinstallApkMcpToolActionTest verifies clean-reinstall MCP wait behavior.
 */
class CleanReinstallApkMcpToolActionTest {

    @Before
    fun setUp() {
        CompileJobManager.resetForTest()
        McpAppReadyGuard.resetForTest()
        ForceGradleCompileHelper.isCleanAndReinstallNextTime = false
    }

    @After
    fun tearDown() {
        CompileJobManager.resetForTest()
        McpAppReadyGuard.resetForTest()
        ForceGradleCompileHelper.isCleanAndReinstallNextTime = false
    }

    @Test
    fun testCleanReinstallDoesNotWaitForAppReadyByDefault() {
        val action = CleanReinstallApkMcpToolAction()
        val runtime = runtimeWithAppReady(false)

        val result = action.execute(mapOf("projectDir" to "/fake/project"), runtime)

        Assert.assertEquals(McpToolStatus.OK, result.status)
    }

    @Test
    fun testCleanReinstallFailsWhenExplicitAppReadyWaitTimesOut() {
        McpAppReadyGuard.postTimeoutOverrideForTest = 5L
        McpAppReadyGuard.postPollIntervalOverrideForTest = 1L
        val action = CleanReinstallApkMcpToolAction()
        val runtime = runtimeWithAppReady(false)

        val result = action.execute(
            mapOf("projectDir" to "/fake/project", "waitAppReadyAfterSuccess" to true),
            runtime,
        )

        Assert.assertEquals(McpToolStatus.ERROR, result.status)
    }

    private fun runtimeWithAppReady(isAppReady: Boolean): IMcpRuntime {
        return object : com.sickworm.intellij.jugg.ai.mcp.TestMcpRuntime() {
            override val logger: com.intellij.openapi.diagnostic.Logger
                get() = com.intellij.openapi.diagnostic.Logger.getInstance("CleanReinstallApkMcpToolActionTest")
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
                    return JuggRunInvocationResult(
                        isSuccess = true,
                        runResult = RunResult(
                            isGradleCompile = false,
                            isCompileSuccess = true,
                            isDeploySuccess = true,
                            isCancel = false,
                        ),
                    )
                }
            }

            override fun isAppReadyDeploy(): Boolean = isAppReady
        }
    }
}
