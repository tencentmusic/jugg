package com.sickworm.intellij.jugg.ai.mcp.actions

import com.intellij.execution.ExecutionResult
import com.intellij.openapi.project.Project
import com.sickworm.intellij.jugg.compiler.CompileUiHandler
import com.sickworm.intellij.jugg.compiler.ForceGradleCompileHelper
import com.sickworm.intellij.jugg.compiler.GradleCompileExecutionResult
import com.sickworm.intellij.jugg.compiler.RemoteSshInfoResult
import com.sickworm.intellij.jugg.compiler.ui.RunResult
import com.sickworm.intellij.jugg.deploy.IDeployTargetManager
import com.sickworm.intellij.jugg.ide.bean.JuggGradleCompileOptions
import com.sickworm.intellij.jugg.ide.logic.IJuggConfigurationRunner
import com.sickworm.intellij.jugg.ide.logic.JuggRunInvocationResult
import com.sickworm.intellij.jugg.ai.mcp.IMcpRuntime
import com.sickworm.intellij.jugg.ai.mcp.McpToolStatus
import org.junit.After
import org.junit.Assert
import org.junit.Before
import org.junit.Test

/**
 * Tests for [CompileOnlyMcpToolAction] to verify compile-only behavior.
 * Key invariant: compile should succeed regardless of app/device readiness,
 * because it does not deploy.
 */
class CompileOnlyMcpToolActionTest {

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

    /**
     * When compile succeeds and app is not ready (device offline / app not started),
     * the compile MCP tool should return OK — it does not deploy, so app readiness is irrelevant.
     */
    @Test
    fun testCompileOnlySuccessWhenAppNotReady() {
        McpAppReadyGuard.postTimeoutOverrideForTest = 5L
        McpAppReadyGuard.postPollIntervalOverrideForTest = 1L
        val action = CompileOnlyMcpToolAction()
        val runtime = runtimeWithCompileResult(
            invocationResult = JuggRunInvocationResult(
                isSuccess = true,
                runResult = RunResult(
                    isGradleCompile = false,
                    isCompileSuccess = true,
                    isDeploySuccess = false,
                    isCancel = false,
                ),
                detail = "",
            ),
            isAppReadyProvider = { false },
        )

        val result = action.execute(mapOf("projectDir" to "/fake/project"), runtime)

        Assert.assertEquals(McpToolStatus.OK, result.status)
        @Suppress("UNCHECKED_CAST")
        val data = result.data as Map<String, Any>
        Assert.assertEquals("success", data["status"])
    }

    /**
     * When compile fails (isCompileSuccess=false), the result should be ERROR
     * regardless of app readiness.
     */
    @Test
    fun testCompileOnlyFailureReturnsError() {
        val action = CompileOnlyMcpToolAction()
        val runtime = runtimeWithCompileResult(
            invocationResult = JuggRunInvocationResult(
                isSuccess = false,
                errorMessage = "compile failed: unresolved reference",
                detail = "error detail",
            ),
            isAppReadyProvider = { true },
        )

        val result = action.execute(mapOf("projectDir" to "/fake/project"), runtime)

        Assert.assertEquals(McpToolStatus.ERROR, result.status)
        @Suppress("UNCHECKED_CAST")
        val data = result.data as Map<String, Any>
        Assert.assertEquals("failed", data["status"])
    }

    private fun runtimeWithCompileResult(
        invocationResult: JuggRunInvocationResult,
        isAppReadyProvider: () -> Boolean = { true },
    ): IMcpRuntime {
        return object : IMcpRuntime {
            override val logger: com.intellij.openapi.diagnostic.Logger
                get() = com.intellij.openapi.diagnostic.Logger.getInstance("TestCompileOnlyRuntime")

            override val project: Project
                get() = throw UnsupportedOperationException("not used in this test")

            override val deployTargetManager: IDeployTargetManager
                get() = throw UnsupportedOperationException("not used in this test")

            override val forceGradleCompileHelper: ForceGradleCompileHelper = object : ForceGradleCompileHelper() {
                override fun executeGradleCompile(autoConfirm: Boolean, useCleanAndReinstall: Boolean) {
                    throw UnsupportedOperationException("not used in this test")
                }

                override fun executeGradleCompileBlocking(autoConfirm: Boolean, useCleanAndReinstall: Boolean): GradleCompileExecutionResult {
                    throw UnsupportedOperationException("not used in this test")
                }

                override fun resolveExecutionType(): String = "local"

                override fun requestRemoteSshInfo(requestedBy: String, reason: String): RemoteSshInfoResult {
                    return RemoteSshInfoResult(approved = false, message = "not used in this test")
                }
            }

            override val juggConfigurationRunner: IJuggConfigurationRunner = object : IJuggConfigurationRunner {
                override val isCompiling: Boolean = false

                override fun runTask(options: JuggGradleCompileOptions, compileUiHandler: CompileUiHandler): ExecutionResult {
                    throw UnsupportedOperationException("not used in this test")
                }

                override fun forceReInstallNextTime() {
                    throw UnsupportedOperationException("not used in this test")
                }

                override fun runFirstConfiguration(isRpcMode: Boolean, isSkipDeploy: Boolean, isAlwaysRestartApp: Boolean): JuggRunInvocationResult {
                    return invocationResult
                }
            }

            override fun isAppReadyDeploy(): Boolean = isAppReadyProvider()
        }
    }
}
