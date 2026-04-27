package com.sickworm.intellij.jugg.ai.mcp.actions

import com.intellij.execution.ExecutionResult
import com.intellij.openapi.project.Project
import com.sickworm.intellij.jugg.ai.mcp.IMcpRuntime
import com.sickworm.intellij.jugg.ai.mcp.McpToolStatus
import com.sickworm.intellij.jugg.ai.mcp.util.LastCompileTimestampRegistry
import com.sickworm.intellij.jugg.compiler.CompileUiHandler
import com.sickworm.intellij.jugg.compiler.ForceGradleCompileHelper
import com.sickworm.intellij.jugg.compiler.GradleCompileExecutionResult
import com.sickworm.intellij.jugg.compiler.RemoteSshInfoResult
import com.sickworm.intellij.jugg.ide.bean.JuggGradleCompileOptions
import com.sickworm.intellij.jugg.ide.logic.IJuggConfigurationRunner
import com.sickworm.intellij.jugg.ide.logic.JuggRunInvocationResult
import org.junit.After
import org.junit.Assert
import org.junit.Before
import org.junit.Test

/**
 * ForceGradleCompileMcpToolActionTest verifies gradle-build updates compile timestamp baseline.
 */
class ForceGradleCompileMcpToolActionTest {

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
    fun testGradleBuildDoesNotRecordLastCompileTimeDirectly() {
        val projectDir = "/fake/project/gradle-build"
        val registry = LastCompileTimestampRegistry.INSTANCE
        registry.setTimestamp(projectDir, "2000-01-01 00:00:00")
        val action = ForceGradleCompileMcpToolAction()
        val runtime = runtimeWithGradleCompileResult(
            GradleCompileExecutionResult(
                status = "success",
                message = "ok",
            ),
        )

        val result = action.execute(mapOf("projectDir" to projectDir), runtime)

        Assert.assertEquals(McpToolStatus.OK, result.status)
        Assert.assertEquals("2000-01-01 00:00:00", registry.getTimestamp(projectDir))
    }

    private fun runtimeWithGradleCompileResult(result: GradleCompileExecutionResult): IMcpRuntime {
        return object : IMcpRuntime {
            override val logger: com.intellij.openapi.diagnostic.Logger
                get() = com.intellij.openapi.diagnostic.Logger.getInstance("TestForceGradleRuntime")

            override val project: Project
                get() = throw UnsupportedOperationException("not used in this test")

            override val deployTargetManager: com.sickworm.intellij.jugg.deploy.IDeployTargetManager
                get() = throw UnsupportedOperationException("not used in this test")

            override val forceGradleCompileHelper: ForceGradleCompileHelper = object : ForceGradleCompileHelper() {
                override fun executeGradleCompile(autoConfirm: Boolean, useCleanAndReinstall: Boolean) {
                    throw UnsupportedOperationException("not used in this test")
                }

                override fun executeGradleCompileBlocking(
                    autoConfirm: Boolean,
                    useCleanAndReinstall: Boolean,
                ): GradleCompileExecutionResult {
                    return result
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

                override fun runFirstConfiguration(
                    isRpcMode: Boolean,
                    isSkipDeploy: Boolean,
                    isAlwaysRestartApp: Boolean,
                ): JuggRunInvocationResult {
                    throw UnsupportedOperationException("not used in this test")
                }
            }
        }
    }
}
