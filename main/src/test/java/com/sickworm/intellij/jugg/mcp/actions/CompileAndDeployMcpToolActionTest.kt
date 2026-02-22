package com.sickworm.intellij.jugg.mcp.actions

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
import com.sickworm.intellij.jugg.mcp.IMcpRuntime
import com.sickworm.intellij.jugg.mcp.McpToolStatus
import org.junit.After
import org.junit.Assert
import org.junit.Before
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Paths

class CompileAndDeployMcpToolActionTest {

    @Before
    fun setUp() {
        CompileJobManager.resetForTest()
    }

    @After
    fun tearDown() {
        CompileJobManager.resetForTest()
    }

    @Test
    fun testFailureDetailIsTruncatedAndFullLogIsArtifact() {
        val fullDetail = buildString {
            repeat(5200) { append('x') }
        }
        val action = CompileAndDeployMcpToolAction()
        val runtime = runtimeWithResult(
            JuggRunInvocationResult(
                isSuccess = false,
                errorMessage = "mock run failed",
                detail = fullDetail,
            )
        )

        val result = action.execute(emptyMap(), runtime)

        Assert.assertEquals(McpToolStatus.ERROR, result.status)
        Assert.assertTrue(result.message.contains("See data.detail and artifacts for logs."))
        @Suppress("UNCHECKED_CAST")
        val data = result.data as Map<String, Any>
        val detailPreview = data["detail"] as String
        Assert.assertTrue(detailPreview.contains("[truncated"))
        Assert.assertEquals(5200.0, (data["detailLength"] as Number).toDouble(), 0.0)
        Assert.assertEquals(true, data["detailTruncated"])
        Assert.assertEquals(true, data["accepted"])
        Assert.assertEquals(true, data["isFinal"])
        Assert.assertEquals("failed", data["status"])
        Assert.assertEquals("local", data["executionType"])
        Assert.assertEquals(CompileJobManager.COMPILE_LATEST_LOG_PATH, data["logPath"])
        Assert.assertFalse(result.artifacts.isEmpty())
        val logArtifact = result.artifacts.first()
        Assert.assertEquals("log", logArtifact.type)
        Assert.assertTrue(Files.exists(Paths.get(logArtifact.path)))
    }

    @Test
    fun testSuccessDoesNotCarryDetailPayload() {
        val action = CompileAndDeployMcpToolAction()
        val runtime = runtimeWithResult(
            JuggRunInvocationResult(
                isSuccess = true,
                runResult = RunResult(isGradleCompile = false, isCompileSuccess = true, isDeploySuccess = true),
                detail = "compile logs that should not be returned on success",
            )
        )

        val result = action.execute(emptyMap(), runtime)

        Assert.assertEquals(McpToolStatus.OK, result.status)
        @Suppress("UNCHECKED_CAST")
        val data = result.data as Map<String, Any>
        Assert.assertTrue(data.containsKey("runResult"))
        Assert.assertEquals(true, data["accepted"])
        Assert.assertEquals(true, data["isFinal"])
        Assert.assertEquals("success", data["status"])
        Assert.assertEquals("local", data["executionType"])
        Assert.assertEquals(CompileJobManager.COMPILE_LATEST_LOG_PATH, data["logPath"])
        Assert.assertFalse(data.containsKey("detail"))
        Assert.assertTrue(result.artifacts.isEmpty())
    }

    @Test
    fun testTimeoutReturnsRunningThenJobCanReachFinalState() {
        CompileJobManager.softTimeoutMillisOverrideForTest = 10L
        val action = CompileAndDeployMcpToolAction()
        val runtime = runtimeWithRunner {
            Thread.sleep(80L)
            JuggRunInvocationResult(
                isSuccess = true,
                runResult = RunResult(isGradleCompile = false, isCompileSuccess = true, isDeploySuccess = true),
                detail = "",
            )
        }

        val result = action.execute(emptyMap(), runtime)

        Assert.assertEquals(McpToolStatus.OK, result.status)
        @Suppress("UNCHECKED_CAST")
        val data = result.data as Map<String, Any>
        Assert.assertEquals(true, data["accepted"])
        Assert.assertEquals(false, data["isFinal"])
        Assert.assertEquals("running", data["status"])
        Assert.assertEquals("local", data["executionType"])
        Assert.assertEquals(CompileJobManager.COMPILE_LATEST_LOG_PATH, data["logPath"])
        val jobId = data["jobId"] as String
        Assert.assertTrue(jobId.isNotBlank())
        Assert.assertTrue(result.message.contains("get_compile_status"))

        val finalState = waitUntilTerminal(jobId)
        Assert.assertEquals("success", finalState.status)
    }

    private fun runtimeWithResult(result: JuggRunInvocationResult): IMcpRuntime {
        return runtimeWithRunner { result }
    }

    private fun runtimeWithRunner(runFirstConfiguration: () -> JuggRunInvocationResult): IMcpRuntime {
        return object : IMcpRuntime {
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

                override fun resolveExecutionType(): String {
                    return "local"
                }

                override fun requestRemoteSshInfo(requestedBy: String, reason: String): RemoteSshInfoResult {
                    return RemoteSshInfoResult(
                        approved = false,
                        message = "not used in this test",
                        auditId = "test-audit-id",
                    )
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

                override fun runFirstConfiguration(isRpcMode: Boolean, isSkipDeploy: Boolean): JuggRunInvocationResult {
                    return runFirstConfiguration()
                }
            }
        }
    }

    private fun waitUntilTerminal(jobId: String): CompileJobStatus {
        val deadline = System.currentTimeMillis() + 1_500L
        while (System.currentTimeMillis() <= deadline) {
            val state = CompileJobManager.getStatus(jobId)
            if (state.status != "running") {
                return state
            }
            Thread.sleep(20L)
        }
        return CompileJobManager.getStatus(jobId)
    }
}
