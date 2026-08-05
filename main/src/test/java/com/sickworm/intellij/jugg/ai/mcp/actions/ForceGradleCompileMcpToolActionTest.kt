package com.sickworm.intellij.jugg.ai.mcp.actions

import com.android.ddmlib.IDevice
import com.intellij.execution.ExecutionResult
import com.intellij.execution.Executor
import com.intellij.execution.configurations.RunProfile
import com.intellij.openapi.project.Project
import com.sickworm.intellij.jugg.ai.mcp.IMcpRuntime
import com.sickworm.intellij.jugg.ai.mcp.McpToolStatus
import com.sickworm.intellij.jugg.ai.mcp.util.LastCompileTimestampRegistry
import com.sickworm.intellij.jugg.compiler.CompileUiHandler
import com.sickworm.intellij.jugg.deploy.IDeployTargetManager
import com.sickworm.intellij.jugg.deploy.instrument.AndroidTestRunSpec
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
 * ForceGradleCompileMcpToolActionTest verifies gradle-build behavior including no-device scenarios.
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
                isCompileSuccess = true,
                isDeploySuccess = true,
            ),
            hasDevice = true,
        )

        val result = action.execute(mapOf("projectDir" to projectDir), runtime)

        Assert.assertEquals(McpToolStatus.OK, result.status)
        Assert.assertEquals("2000-01-01 00:00:00", registry.getTimestamp(projectDir))
    }

    @Test
    fun testGradleBuildReturnsCompileAndDeployResult() {
        val action = ForceGradleCompileMcpToolAction()
        val runtime = runtimeWithGradleCompileResult(
            GradleCompileExecutionResult(
                status = "success",
                message = "gradle build ok",
                isCompileSuccess = true,
                isDeploySuccess = true,
            ),
            hasDevice = true,
        )

        val result = action.execute(mapOf("projectDir" to "/fake/project/gradle-build"), runtime)

        Assert.assertEquals(McpToolStatus.OK, result.status)
        @Suppress("UNCHECKED_CAST")
        val data = result.data as Map<String, Any>
        Assert.assertEquals(true, data["isCompileSuccess"])
        Assert.assertEquals(true, data["isDeploySuccess"])
    }

    @Test
    fun testGradleBuildSuccessDoesNotWaitForAppReadyByDefault() {
        val action = ForceGradleCompileMcpToolAction()
        val runtime = runtimeWithGradleCompileResult(
            GradleCompileExecutionResult(
                status = "success",
                message = "gradle build ok",
                isCompileSuccess = true,
                isDeploySuccess = true,
            ),
            hasDevice = true,
            isAppReady = false,
        )

        val result = action.execute(mapOf("projectDir" to "/fake/project/has-device"), runtime)

        Assert.assertEquals(McpToolStatus.OK, result.status)
    }

    @Test
    fun testGradleBuildSuccessTurnsFailedWhenExplicitAppReadyWaitTimesOut() {
        val action = ForceGradleCompileMcpToolAction()
        val runtime = runtimeWithGradleCompileResult(
            GradleCompileExecutionResult(
                status = "success",
                message = "gradle build ok",
                isCompileSuccess = true,
                isDeploySuccess = true,
            ),
            hasDevice = true,
            isAppReady = false,
        )

        val result = action.execute(
            mapOf("projectDir" to "/fake/project/has-device", "waitAppReadyAfterSuccess" to true),
            runtime,
        )

        Assert.assertEquals("explicit wait should report ERROR when app is not ready", McpToolStatus.ERROR, result.status)
    }

    @Test
    fun testGradleBuildSuccessWhenNoDevice() {
        val action = ForceGradleCompileMcpToolAction()
        val runtime = runtimeWithGradleCompileResult(
            GradleCompileExecutionResult(
                status = "failed",
                message = "gradle build failed",
                isCompileSuccess = true,
                isDeploySuccess = false,
            ),
            hasDevice = false,
            isAppReady = false,
        )

        val result = action.execute(mapOf("projectDir" to "/fake/project/no-device"), runtime)

        Assert.assertEquals("gradle-build with no device must report deploy failure", McpToolStatus.ERROR, result.status)
        @Suppress("UNCHECKED_CAST")
        val data = result.data as Map<String, Any>
        Assert.assertEquals(true, data["isCompileSuccess"])
        Assert.assertEquals(false, data["isDeploySuccess"])
    }

    @Test
    fun testGradleBuildStatusFollowsDeployFailure() {
        val action = ForceGradleCompileMcpToolAction()
        val runtime = runtimeWithGradleCompileResult(
            GradleCompileExecutionResult(
                status = "success",
                message = "gradle build ok",
                isCompileSuccess = true,
                isDeploySuccess = false,
            ),
            hasDevice = false,
            isAppReady = false,
        )

        val result = action.execute(mapOf("projectDir" to "/fake/project/deploy-failed"), runtime)

        Assert.assertEquals("gradle-build must report ERROR when deploy fails", McpToolStatus.ERROR, result.status)
        @Suppress("UNCHECKED_CAST")
        val data = result.data as Map<String, Any>
        Assert.assertEquals("failed", data["status"])
        Assert.assertEquals(true, data["isCompileSuccess"])
        Assert.assertEquals(false, data["isDeploySuccess"])
    }

    @Test
    fun testGradleBuildNoDeviceMessageIsPreserved() {
        val action = ForceGradleCompileMcpToolAction()
        val runtime = runtimeWithGradleCompileResult(
            GradleCompileExecutionResult(
                status = "failed",
                message = "No device found. Stop installing.",
                isCompileSuccess = true,
                isDeploySuccess = false,
            ),
            hasDevice = false,
            isAppReady = false,
        )

        val result = action.execute(mapOf("projectDir" to "/fake/project/no-device-message"), runtime)

        Assert.assertEquals(McpToolStatus.ERROR, result.status)
        Assert.assertEquals("No device found. Stop installing.", result.message)
        @Suppress("UNCHECKED_CAST")
        val data = result.data as Map<String, Any>
        Assert.assertEquals("failed", data["status"])
        Assert.assertEquals(true, data["isCompileSuccess"])
        Assert.assertEquals(false, data["isDeploySuccess"])
    }

    @Test
    fun testAsyncGradleBuildFailureExposesDetailInStatus() {
        CompileJobManager.softTimeoutMillisOverrideForTest = 10L
        val detail = """
            Compile project failed, please check the error message.
            [Jugg] Found error in logs:
            e: java.lang.IllegalAccessError: superclass access check failed
            > Task :library1:kaptGenerateStubsDebugKotlin FAILED
        """.trimIndent()
        val action = ForceGradleCompileMcpToolAction()
        val runtime = runtimeWithGradleCompileResult(
            GradleCompileExecutionResult(
                status = "failed",
                message = "Compile project failed",
                isCompileSuccess = false,
                isDeploySuccess = false,
                detail = detail,
            ),
            hasDevice = false,
            delayMs = 80L,
        )

        val triggerResult = action.execute(mapOf("projectDir" to "/fake/project/gradle-detail"), runtime)
        @Suppress("UNCHECKED_CAST")
        val jobId = (triggerResult.data as Map<String, Any>)["jobId"] as String
        waitUntilTerminal(jobId)

        val statusResult = GetCompileStatusMcpToolAction().execute(
            mapOf("projectDir" to "/fake/project/gradle-detail", "jobId" to jobId),
            runtime,
        )

        Assert.assertEquals(McpToolStatus.ERROR, statusResult.status)
        @Suppress("UNCHECKED_CAST")
        val data = statusResult.data as Map<String, Any>
        val detailPreview = data["detail"] as String
        Assert.assertTrue(detailPreview.contains("Compile project failed, please check the error message."))
        Assert.assertTrue(detailPreview.contains("[Jugg] Found error in logs:"))
        Assert.assertTrue(detailPreview.contains("java.lang.IllegalAccessError"))
        Assert.assertTrue(detailPreview.contains("> Task :library1:kaptGenerateStubsDebugKotlin FAILED"))
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

    private fun runtimeWithGradleCompileResult(
        result: GradleCompileExecutionResult,
        hasDevice: Boolean = true,
        isAppReady: Boolean = true,
        delayMs: Long = 0L,
    ): IMcpRuntime {
        return object : com.sickworm.intellij.jugg.ai.mcp.TestMcpRuntime() {
            override val logger: com.intellij.openapi.diagnostic.Logger
                get() = com.intellij.openapi.diagnostic.Logger.getInstance("TestForceGradleRuntime")


            override val deployTargetManager: IDeployTargetManager = object : IDeployTargetManager {
                override val hasDevice: Boolean = hasDevice

                override fun setApks(apks: List<com.sickworm.intellij.jugg.apk.ApkInfo>) {
                    throw UnsupportedOperationException("not used in this test")
                }

                override fun getApks(): List<com.sickworm.intellij.jugg.apk.ApkInfo> =
                    throw UnsupportedOperationException("not used in this test")

                override fun getSelectedDevices(): List<com.android.ddmlib.IDevice> = emptyList()

                override fun getConnectedDevices(): List<IDevice> =
                    throw UnsupportedOperationException("not used in this test")

                override fun startApp(device: IDevice): Boolean =
                    throw UnsupportedOperationException("not used in this test")

                override fun restartApp(device: IDevice): Boolean =
                    throw UnsupportedOperationException("not used in this test")

                override fun stopApp(device: IDevice): Boolean =
                    throw UnsupportedOperationException("not used in this test")

                override fun isAppForeground(device: IDevice): Boolean =
                    throw UnsupportedOperationException("not used in this test")

                override fun getPackageName(): String =
                    throw UnsupportedOperationException("not used in this test")
            }

            override fun isAppReadyDeploy(): Boolean = isAppReady

            override val forceGradleCompileHelper: ForceGradleCompileHelper = object : ForceGradleCompileHelper() {
                override fun executeGradleCompile(autoConfirm: Boolean, useCleanAndReinstall: Boolean) {
                    throw UnsupportedOperationException("not used in this test")
                }

                override fun executeGradleCompileBlocking(
                    autoConfirm: Boolean,
                    useCleanAndReinstall: Boolean,
                ): GradleCompileExecutionResult {
                    if (delayMs > 0) {
                        Thread.sleep(delayMs)
                    }
                    return result
                }

                override fun resolveExecutionType(): String = "local"

                override fun requestRemoteSshInfo(requestedBy: String, reason: String): RemoteSshInfoResult {
                    return RemoteSshInfoResult(approved = false, message = "not used in this test")
                }
            }

            override val juggConfigurationRunner: IJuggConfigurationRunner = object : IJuggConfigurationRunner {
                override val isCompiling: Boolean = false

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
                    throw UnsupportedOperationException("not used in this test")
                }
            }
        }
    }

}
