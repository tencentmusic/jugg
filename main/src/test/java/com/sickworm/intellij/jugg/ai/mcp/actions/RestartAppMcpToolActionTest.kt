package com.sickworm.intellij.jugg.ai.mcp.actions

import com.android.ddmlib.IDevice
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.sickworm.intellij.jugg.compiler.CompileUiHandler
import com.sickworm.intellij.jugg.compiler.ForceGradleCompileHelper
import com.sickworm.intellij.jugg.compiler.GradleCompileExecutionResult
import com.sickworm.intellij.jugg.compiler.RemoteSshInfoResult
import com.sickworm.intellij.jugg.deploy.IDeviceAdb
import com.sickworm.intellij.jugg.deploy.IDeployTargetManager
import com.sickworm.intellij.jugg.ide.bean.JuggGradleCompileOptions
import com.sickworm.intellij.jugg.ide.logic.IJuggConfigurationRunner
import com.sickworm.intellij.jugg.ide.logic.JuggRunInvocationResult
import com.sickworm.intellij.jugg.ai.mcp.IMcpRuntime
import com.sickworm.intellij.jugg.ai.mcp.McpToolStatus
import com.sickworm.intellij.jugg.platform.IPlatformApi
import com.sickworm.intellij.jugg.platform.PlatformApi
import org.junit.After
import org.junit.Assert
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito
import java.io.File

/**
 * RestartAppMcpToolActionTest verifies restart basic behavior.
 */
class RestartAppMcpToolActionTest {
    @Before
    fun setUp() {
        McpAppReadyGuard.resetForTest()
    }

    @After
    fun tearDown() {
        McpAppReadyGuard.resetForTest()
    }

    @Test
    fun testInputSchemaShouldNotExposeTapActions() {
        val properties = RestartAppMcpToolAction().definition.inputSchema.properties
        Assert.assertNull("tap_actions should not be in schema", properties["tap_actions"])
    }

    @Test
    fun testRestartAppSucceeds() {
        val (runtime, _) = runtimeWithMocks()
        val action = RestartAppMcpToolAction()
        val result = action.execute(
            mapOf("projectDir" to "/tmp/test"),
            runtime,
        )
        Assert.assertEquals(McpToolStatus.OK, result.status)
        Assert.assertTrue(result.message.contains("restart executed successfully"))
    }

    @Test
    fun testRestartDoesNotWaitForAppReadyByDefault() {
        var readyChecks = 0
        val (runtime, _) = runtimeWithMocks(isAppReadyProvider = {
            readyChecks += 1
            false
        })
        val action = RestartAppMcpToolAction()

        val result = action.execute(
            mapOf("projectDir" to "/tmp/test"),
            runtime,
        )

        Assert.assertEquals(McpToolStatus.OK, result.status)
        Assert.assertEquals(0, readyChecks)
    }

    @Test
    fun testRestartFailsWhenExplicitAppReadyWaitTimesOut() {
        McpAppReadyGuard.postTimeoutOverrideForTest = 5L
        McpAppReadyGuard.postPollIntervalOverrideForTest = 1L
        val (runtime, _) = runtimeWithMocks(isAppReadyProvider = { false })
        val action = RestartAppMcpToolAction()

        val result = action.execute(
            mapOf("projectDir" to "/tmp/test", "waitAppReadyAfterSuccess" to true),
            runtime,
        )

        Assert.assertEquals(McpToolStatus.ERROR, result.status)
    }

    private fun runtimeWithMocks(
        isAppReadyProvider: () -> Boolean = { true },
    ): Pair<IMcpRuntime, IDeployTargetManager> {
        val device = Mockito.mock(IDevice::class.java)
        val adb = FakeDeviceAdb()
        PlatformApi.impl = FakePlatformApi(mapOf(device to adb))

        val deployTargetManager = Mockito.mock(IDeployTargetManager::class.java)
        Mockito.`when`(deployTargetManager.getSelectedDevices()).thenReturn(listOf(device))
        Mockito.`when`(deployTargetManager.getConnectedDevices()).thenReturn(listOf(device))
        Mockito.`when`(deployTargetManager.getPackageName()).thenReturn("com.example.app")
        Mockito.`when`(deployTargetManager.restartApp(device)).thenReturn(true)

        val runtime = object : com.sickworm.intellij.jugg.ai.mcp.TestMcpRuntime() {
            override val logger: com.intellij.openapi.diagnostic.Logger
                get() = com.intellij.openapi.diagnostic.Logger.getInstance("RestartAppMcpToolActionTest")
            override val projectDir: String = "/tmp/test"
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

        return runtime to deployTargetManager
    }

    private class FakeDeviceAdb : IDeviceAdb {
        val executedCommands = mutableListOf<String>()
        override val displayName: String? = "fake_device"
        override val api: Int = 34
        override val serial: String = "emulator-5554"
        override val isOnline: Boolean = true

        override fun execAdbShellCmd(cmd: String): String {
            executedCommands.add(cmd)
            if (cmd == "dumpsys activity activities") {
                return "topResumedActivity=ActivityRecord{100 com.example.app/.MainActivity t10}"
            }
            return ""
        }

        override fun push(from: File, to: String): Boolean = true
        override fun pull(from: String, to: File): Boolean = true
        override fun getDefaultLaunchActivity(apkFile: File): String? = null
        override fun getArch(packageName: String): String = "ARCH_64_BIT"
        override fun getProperty(name: String): String? = null
    }

    private class FakePlatformApi(
        private val adbByDevice: Map<IDevice, IDeviceAdb>,
    ) : IPlatformApi {
        override fun showDialog(
            title: String,
            content: String,
            okButtonText: String?,
            cancelButtonText: String?,
            isShowCancelButton: Boolean,
        ): Boolean = false

        override fun showUserAndPasswordInputDialog(
            content: String,
            subTitle: String?,
            isPassword: Boolean,
            defaultInputText: String?,
            title: String?,
        ): String? = null

        override fun allAvailableJavaHomes(): List<String> = emptyList()

        override fun getGradleJdkPath(project: Project, logger: Logger): String? = null

        override fun getAndroidHomePath(logger: Logger): String? = null

        override fun getIdeVersion(): String = "test"
        override fun getRuntimeInfo() = com.sickworm.intellij.jugg.project.runtime.RuntimeInfo("test", "test", "test", "")

        override fun toDeviceAdb(device: IDevice): IDeviceAdb? = adbByDevice[device]

        override fun isHasRelaunchActivityIssues(device: IDeviceAdb, logger: Logger): Boolean = false

        override fun invokeMcp(request: com.sickworm.intellij.jugg.ai.mcp.McpJsonRpcRequest): com.sickworm.intellij.jugg.ai.mcp.McpJsonRpcResponse {
            throw UnsupportedOperationException("not used")
        }

        override fun getInitializedProjectDirs(): List<File> = emptyList()

        override fun executeGradleCompile(autoConfirm: Boolean, useCleanAndReinstall: Boolean) {
            throw UnsupportedOperationException("not used")
        }
    }
}
