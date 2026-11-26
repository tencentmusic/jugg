package com.sickworm.intellij.jugg.ai.mcp.actions

import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.jugg.ai.mcp.McpToolStatus
import com.sickworm.intellij.jugg.compiler.BuildTarget
import com.sickworm.intellij.jugg.compiler.ForceGradleCompileHelper
import com.sickworm.intellij.jugg.compiler.GradleCompileExecutionResult
import com.sickworm.intellij.jugg.compiler.ui.RunResult
import com.sickworm.intellij.jugg.deploy.IDeployTargetManager
import com.sickworm.intellij.jugg.deploy.instrument.AndroidTestRunSpec
import com.sickworm.intellij.jugg.ide.logic.IJuggConfigurationRunner
import com.sickworm.intellij.jugg.ide.logic.JuggRunInvocationResult
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class CompileJobDeviceSerialTest {

    @Before
    fun setUp() {
        CompileJobManager.resetForTest()
    }

    @After
    fun tearDown() {
        CompileJobManager.resetForTest()
    }

    @Test
    fun testDeployForwardsSerialToRunner() {
        var capturedSerial: String? = null
        val runner = object : FakeJuggConfigurationRunner() {
            override fun runFirstConfigurationWithSpec(
                isRpcMode: Boolean,
                isSkipDeploy: Boolean,
                isAlwaysRestartApp: Boolean,
                androidTestRunSpec: AndroidTestRunSpec?,
                buildTargetOverride: BuildTarget?,
                targetDeviceSerial: String?,
            ): JuggRunInvocationResult {
                capturedSerial = targetDeviceSerial
                return successfulInvocation()
            }
        }
        val runtime = runtime(runner, FakeForceGradleCompileHelper())

        val result = CompileAndDeployMcpToolAction().execute(
            mapOf("projectDir" to "/test-project", "serial" to "device-2"), runtime,
        )

        assertEquals(McpToolStatus.OK, result.status)
        assertEquals("device-2", capturedSerial)
    }

    @Test
    fun testGradleBuildForwardsSerialToHelper() {
        var capturedSerial: String? = null
        val helper = object : FakeForceGradleCompileHelper() {
            override fun executeGradleCompileBlockingForDevice(
                autoConfirm: Boolean,
                useCleanAndReinstall: Boolean,
                targetDeviceSerial: String?,
            ): GradleCompileExecutionResult {
                capturedSerial = targetDeviceSerial
                return GradleCompileExecutionResult(
                    status = "success",
                    message = "success",
                    isCompileSuccess = true,
                    isDeploySuccess = true,
                )
            }
        }
        val runtime = runtime(FakeJuggConfigurationRunner(), helper)

        val result = ForceGradleCompileMcpToolAction().execute(
            mapOf("projectDir" to "/test-project", "serial" to "device-2"), runtime,
        )

        assertEquals(McpToolStatus.OK, result.status)
        assertEquals("device-2", capturedSerial)
    }

    private fun runtime(
        runner: IJuggConfigurationRunner,
        helper: ForceGradleCompileHelper,
    ): com.sickworm.intellij.jugg.ai.mcp.IMcpRuntime {
        return object : com.sickworm.intellij.jugg.ai.mcp.TestMcpRuntime() {
            override val logger: Logger = Logger.getInstance("CompileJobDeviceSerialTest")
            override val deployTargetManager: IDeployTargetManager
                get() = throw UnsupportedOperationException("not used")
            override val forceGradleCompileHelper: ForceGradleCompileHelper = helper
            override val juggConfigurationRunner: IJuggConfigurationRunner = runner
        }
    }

    private fun successfulInvocation(): JuggRunInvocationResult {
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
