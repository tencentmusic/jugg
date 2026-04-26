package com.sickworm.intellij.jugg.ai.mcp.actions

import com.intellij.openapi.project.Project
import com.sickworm.intellij.jugg.compiler.CompileUiHandler
import com.sickworm.intellij.jugg.compiler.ForceGradleCompileHelper
import com.sickworm.intellij.jugg.compiler.GradleCompileExecutionResult
import com.sickworm.intellij.jugg.compiler.RemoteSshInfoResult
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
import org.mockito.Mockito

class McpAppReadyGuardTest {

    @Before
    fun setUp() {
        McpAppReadyGuard.resetForTest()
        McpAppReadyGuard.preTimeoutOverrideForTest = 8L
        McpAppReadyGuard.prePollIntervalOverrideForTest = 1L
        McpAppReadyGuard.preFailureRetryIntervalOverrideForTest = 0L
        McpAppReadyGuard.postTimeoutOverrideForTest = 5L
        McpAppReadyGuard.postPollIntervalOverrideForTest = 1L
    }

    @After
    fun tearDown() {
        McpAppReadyGuard.resetForTest()
    }

    @Test
    fun testPreCheckSucceedsAfterRetries() {
        var checks = 0
        val runtime = runtime {
            checks += 1
            checks >= 3
        }

        val result = McpAppReadyGuard.waitBeforeRuntimeObserve(runtime, "layout-dump")

        Assert.assertTrue(result.isReady)
        Assert.assertNull(result.errorResult)
        Assert.assertTrue(result.hasWaited)
        Assert.assertEquals(3, checks)
    }

    @Test
    fun testPreCheckReturnsErrorWhenNeverReady() {
        var checks = 0
        val runtime = runtime {
            checks += 1
            false
        }

        val result = McpAppReadyGuard.waitBeforeRuntimeObserve(runtime, "screenshot")

        Assert.assertFalse(result.isReady)
        Assert.assertNotNull(result.errorResult)
        Assert.assertEquals(McpToolStatus.ERROR, result.errorResult?.status)
        Assert.assertTrue(result.errorResult?.message?.contains("app is not ready") == true)
        Assert.assertTrue(checks >= 2)
    }

    @Test
    fun testPostCheckFailsWhenTimeoutReached() {
        val runtime = runtime { false }

        val result = McpAppReadyGuard.waitAfterMutating(runtime, "deploy")

        Assert.assertFalse(result.isReady)
        Assert.assertTrue(result.reason?.contains("not ready") == true)
    }

    private fun runtime(isAppReadyProvider: () -> Boolean): IMcpRuntime {
        val project = Mockito.mock(Project::class.java)
        val deployTargetManager = Mockito.mock(IDeployTargetManager::class.java)
        return object : IMcpRuntime {
            override val logger: com.intellij.openapi.diagnostic.Logger
                get() = com.intellij.openapi.diagnostic.Logger.getInstance("McpAppReadyGuardTest")
            override val project: Project = project
            override val deployTargetManager: IDeployTargetManager = deployTargetManager
            override val forceGradleCompileHelper: ForceGradleCompileHelper = object : ForceGradleCompileHelper() {
                override fun executeGradleCompile(autoConfirm: Boolean, useCleanAndReinstall: Boolean) {
                    throw UnsupportedOperationException("not used")
                }

                override fun executeGradleCompileBlocking(
                    autoConfirm: Boolean,
                    useCleanAndReinstall: Boolean,
                ): GradleCompileExecutionResult {
                    throw UnsupportedOperationException("not used")
                }

                override fun resolveExecutionType(): String = "local"

                override fun requestRemoteSshInfo(requestedBy: String, reason: String): RemoteSshInfoResult {
                    throw UnsupportedOperationException("not used")
                }
            }
            override val juggConfigurationRunner: IJuggConfigurationRunner = FakeJuggConfigurationRunner()

            override fun isAppReadyDeploy(): Boolean {
                return isAppReadyProvider()
            }
        }
    }
}
