package com.sickworm.intellij.jugg.ai.mcp.actions

import com.intellij.execution.ExecutionResult
import com.intellij.execution.Executor
import com.intellij.execution.configurations.RunProfile
import com.intellij.openapi.project.Project
import com.sickworm.intellij.jugg.compiler.CompileUiHandler
import com.sickworm.intellij.jugg.deploy.instrument.AndroidTestRunSpec
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
import kotlin.system.measureTimeMillis

class GetCompileStatusMcpToolActionTest {

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
    fun testSuccessDetailIsNotReturnedByGetCompileStatus() {
        CompileJobManager.softTimeoutMillisOverrideForTest = 1L
        val successDetail = "test output line"
        val runtime = runtimeWithRunner(delayMillis = 120L, detail = successDetail)
        val deployAction = CompileAndDeployMcpToolAction()
        val triggerResult = deployAction.execute(mapOf("projectDir" to "/fake/project"), runtime)
        @Suppress("UNCHECKED_CAST")
        val triggerData = triggerResult.data as Map<String, Any>
        Assert.assertEquals(false, triggerData["isFinal"])
        val jobId = triggerData["jobId"] as String

        val finalState = waitUntilTerminal(jobId)
        Assert.assertEquals("success", finalState.status)

        val getStatusAction = GetCompileStatusMcpToolAction()
        val statusResult = getStatusAction.execute(
            mapOf("projectDir" to "/fake/project", "jobId" to jobId),
            runtime,
        )

        Assert.assertEquals(McpToolStatus.OK, statusResult.status)
        @Suppress("UNCHECKED_CAST")
        val statusData = statusResult.data as Map<String, Any>
        Assert.assertEquals("success", statusData["status"])
        Assert.assertFalse(statusData.containsKey("detail"))
        Assert.assertFalse(statusData.containsKey("detailLength"))
        Assert.assertFalse(statusData.containsKey("detailTruncated"))
    }

    @Test
    fun testWaitTimeoutBlocksUntilCompileFinishes() {
        CompileJobManager.softTimeoutMillisOverrideForTest = 1L
        val runtime = runtimeWithRunner(delayMillis = 120L)
        val deployAction = CompileAndDeployMcpToolAction()
        val triggerResult = deployAction.execute(mapOf("projectDir" to "/fake/project"), runtime)
        @Suppress("UNCHECKED_CAST")
        val triggerData = triggerResult.data as Map<String, Any>
        Assert.assertEquals(false, triggerData["isFinal"])
        val jobId = triggerData["jobId"] as String

        val action = GetCompileStatusMcpToolAction()
        lateinit var resultData: Map<String, Any>
        val elapsedMs = measureTimeMillis {
            val result = action.execute(
                mapOf(
                    "projectDir" to "/fake/project",
                    "jobId" to jobId,
                    "waitTimeoutMs" to 500,
                ),
                runtime,
            )
            Assert.assertEquals(McpToolStatus.OK, result.status)
            @Suppress("UNCHECKED_CAST")
            resultData = result.data as Map<String, Any>
        }

        Assert.assertEquals("success", resultData["status"])
        Assert.assertEquals(true, resultData["isCompileSuccess"])
        Assert.assertEquals(true, resultData["isDeploySuccess"])
        Assert.assertTrue("expected blocking wait, elapsed=$elapsedMs", elapsedMs >= 80L)
    }

    @Test
    fun testRunningStatusReturnsCurrentIndicatorText() {
        CompileJobManager.softTimeoutMillisOverrideForTest = 1L
        val runtime = runtimeWithRunner(
            delayMillis = 300L,
            indicatorText = "Compiling files (3/12)...",
        )
        val deployAction = CompileAndDeployMcpToolAction()
        val triggerResult = deployAction.execute(mapOf("projectDir" to "/fake/project"), runtime)
        @Suppress("UNCHECKED_CAST")
        val triggerData = triggerResult.data as Map<String, Any>
        Assert.assertEquals(false, triggerData["isFinal"])
        val jobId = triggerData["jobId"] as String

        val action = GetCompileStatusMcpToolAction()
        val result = action.execute(
            mapOf(
                "projectDir" to "/fake/project",
                "jobId" to jobId,
                "waitTimeoutMs" to 0,
            ),
            runtime,
        )

        Assert.assertEquals(McpToolStatus.OK, result.status)
        @Suppress("UNCHECKED_CAST")
        val data = result.data as Map<String, Any>
        Assert.assertEquals("running", data["status"])
        @Suppress("UNCHECKED_CAST")
        val indicator = data["indicator"] as Map<String, Any>
        Assert.assertEquals("Compiling files (3/12)...", indicator["text"])
    }

    private fun runtimeWithRunner(
        delayMillis: Long,
        detail: String = "",
        indicatorText: String = "",
    ): IMcpRuntime {
        return object : com.sickworm.intellij.jugg.ai.mcp.TestMcpRuntime() {
            override val logger: com.intellij.openapi.diagnostic.Logger
                get() = com.intellij.openapi.diagnostic.Logger.getInstance("GetCompileStatusTestRuntime")

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
                    return RemoteSshInfoResult(
                        approved = false,
                        message = "not used in this test",
                    )
                }
            }

            override val juggConfigurationRunner: IJuggConfigurationRunner = object : IJuggConfigurationRunner {
                override val isCompiling: Boolean = false
                override val currentIndicatorText: String
                    get() = indicatorText

                override fun runTask(options: JuggGradleCompileOptions, compileUiHandler: CompileUiHandler, executor: Executor?, runProfile: RunProfile?, androidTestRunSpec: AndroidTestRunSpec?): ExecutionResult {
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
                    Thread.sleep(delayMillis)
                    return JuggRunInvocationResult(
                        isSuccess = true,
                        runResult = RunResult(
                            isGradleCompile = false,
                            isCompileSuccess = true,
                            isDeploySuccess = true,
                            isCancel = false,
                        ),
                        detail = detail,
                    )
                }
            }

            override fun isAppReadyDeploy(): Boolean = true
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
