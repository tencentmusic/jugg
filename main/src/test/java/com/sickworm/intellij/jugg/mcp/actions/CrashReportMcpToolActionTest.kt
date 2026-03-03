package com.sickworm.intellij.jugg.mcp.actions

import com.android.ddmlib.IDevice
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.sickworm.intellij.jugg.compiler.CompileUiHandler
import com.sickworm.intellij.jugg.compiler.ForceGradleCompileHelper
import com.sickworm.intellij.jugg.compiler.GradleCompileExecutionResult
import com.sickworm.intellij.jugg.compiler.RemoteSshInfoResult
import com.sickworm.intellij.jugg.apk.ApkInfo
import com.sickworm.intellij.jugg.deploy.IDeviceAdb
import com.sickworm.intellij.jugg.deploy.IDeployTargetManager
import com.sickworm.intellij.jugg.ide.bean.ConfirmResult
import com.sickworm.intellij.jugg.ide.bean.JuggGradleCompileOptions
import com.sickworm.intellij.jugg.ide.logic.IJuggConfigurationRunner
import com.sickworm.intellij.jugg.ide.logic.JuggRunInvocationResult
import com.sickworm.intellij.jugg.mcp.IMcpRuntime
import com.sickworm.intellij.jugg.mcp.McpErrorCode
import com.sickworm.intellij.jugg.mcp.McpToolStatus
import com.sickworm.intellij.jugg.platform.IPlatformApi
import com.sickworm.intellij.jugg.platform.PlatformApi
import com.sickworm.intellij.jugg.project.dependency.DependencyDiffResult
import org.junit.Assert
import org.junit.Test
import org.mockito.Mockito
import java.io.File

/**
 * CrashReportMcpToolActionTest verifies crash report data extraction and edge-case behavior.
 */
class CrashReportMcpToolActionTest {

    @Test
    fun testCrashReportNoCrashReturnsEmptySnippetAndArtifact() {
        val projectDir = createTempDir(prefix = "jugg_mcp_crash_report_no_crash_")
        val device = Mockito.mock(IDevice::class.java)
        val adb = FakeDeviceAdb(
            shellOutputs = mapOf(
                "dumpsys activity activities" to "",
                "pidof com.example.app" to "",
                "ps | grep com.example.app" to "",
            )
        )
        PlatformApi.impl = FakePlatformApi(mapOf(device to adb))
        val deployTargetManager = FakeDeployTargetManager(
            selected = listOf(device),
            connected = listOf(device),
            packageName = "com.example.app",
            logs = "I/AnyTag: app is healthy",
        )
        val action = CrashReportMcpToolAction()

        val result = action.execute(
            mapOf("projectDir" to projectDir.absolutePath),
            runtime(projectDir, deployTargetManager),
        )

        Assert.assertEquals(McpToolStatus.OK, result.status)
        @Suppress("UNCHECKED_CAST")
        val data = result.data as Map<String, Any>
        Assert.assertEquals(false, data["hasCrash"])
        Assert.assertEquals(false, data["isProcessAlive"])
        Assert.assertEquals("com.example.app", data["packageName"])
        Assert.assertTrue((data["crashLogs"] as List<*>).isEmpty())
        val allErrorLogPath = data["allErrorLogPath"] as String
        Assert.assertTrue(File(allErrorLogPath).exists())
        Assert.assertEquals("log", result.artifacts.firstOrNull()?.type)
    }

    @Test
    fun testCrashReportUsesRuntimePackageAndPidofAlive() {
        val projectDir = createTempDir(prefix = "jugg_mcp_crash_report_pidof_")
        val device = Mockito.mock(IDevice::class.java)
        val adb = FakeDeviceAdb(
            shellOutputs = mapOf(
                "dumpsys activity activities" to "topResumedActivity: ActivityRecord{1 t1 com.example.fallback/.MainActivity}",
                "pidof com.example.fallback" to "1234",
            )
        )
        PlatformApi.impl = FakePlatformApi(mapOf(device to adb))
        val deployTargetManager = FakeDeployTargetManager(
            selected = listOf(device),
            connected = listOf(device),
            packageName = "com.example.fallback",
            logs = "E AndroidRuntime: FATAL EXCEPTION: main\nE AndroidRuntime: Process: com.example.fallback",
        )
        val action = CrashReportMcpToolAction()

        val result = action.execute(
            mapOf("projectDir" to projectDir.absolutePath),
            runtime(projectDir, deployTargetManager),
        )

        Assert.assertEquals(McpToolStatus.OK, result.status)
        @Suppress("UNCHECKED_CAST")
        val data = result.data as Map<String, Any>
        Assert.assertEquals(true, data["isProcessAlive"])
        Assert.assertEquals("com.example.fallback", data["packageName"])
        Assert.assertEquals("com.example.fallback/.MainActivity", data["relatedActivity"])
        Assert.assertEquals(true, data["hasCrash"])
    }

    @Test
    fun testCrashReportFallsBackToPsWhenPidofIsEmpty() {
        val projectDir = createTempDir(prefix = "jugg_mcp_crash_report_ps_")
        val device = Mockito.mock(IDevice::class.java)
        val adb = FakeDeviceAdb(
            shellOutputs = mapOf(
                "dumpsys activity activities" to "",
                "pidof com.example.ps" to "",
                "ps | grep com.example.ps" to "u0_a1  2234  300 com.example.ps",
            )
        )
        PlatformApi.impl = FakePlatformApi(mapOf(device to adb))
        val deployTargetManager = FakeDeployTargetManager(
            selected = listOf(device),
            connected = listOf(device),
            packageName = "com.example.ps",
            logs = "",
        )
        val action = CrashReportMcpToolAction()

        val result = action.execute(
            mapOf("projectDir" to projectDir.absolutePath),
            runtime(projectDir, deployTargetManager),
        )

        Assert.assertEquals(McpToolStatus.OK, result.status)
        @Suppress("UNCHECKED_CAST")
        val data = result.data as Map<String, Any>
        Assert.assertEquals(true, data["isProcessAlive"])
        Assert.assertEquals(false, data["hasCrash"])
    }

    @Test
    fun testCrashReportNoDeviceReturnsNoDeviceError() {
        val projectDir = createTempDir(prefix = "jugg_mcp_crash_report_no_device_")
        PlatformApi.impl = FakePlatformApi(emptyMap())
        val deployTargetManager = FakeDeployTargetManager(
            selected = emptyList(),
            connected = emptyList(),
            packageName = "com.example.app",
            logs = "",
        )
        val action = CrashReportMcpToolAction()

        val result = action.execute(
            mapOf("projectDir" to projectDir.absolutePath),
            runtime(projectDir, deployTargetManager),
        )

        Assert.assertEquals(McpToolStatus.ERROR, result.status)
        Assert.assertEquals(McpErrorCode.MCP_NO_DEVICE, result.errorCode)
    }

    private fun runtime(projectDir: File, deployTargetManager: IDeployTargetManager): IMcpRuntime {
        val project = Mockito.mock(Project::class.java)
        Mockito.`when`(project.basePath).thenReturn(projectDir.absolutePath)
        return object : IMcpRuntime {
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
            override val juggConfigurationRunner: IJuggConfigurationRunner = object : IJuggConfigurationRunner {
                override val isCompiling: Boolean = false
                override fun runTask(options: JuggGradleCompileOptions, compileUiHandler: CompileUiHandler) =
                    throw UnsupportedOperationException("not used")

                override fun forceReInstallNextTime() {
                    throw UnsupportedOperationException("not used")
                }

                override fun runFirstConfiguration(isRpcMode: Boolean, isSkipDeploy: Boolean): JuggRunInvocationResult {
                    throw UnsupportedOperationException("not used")
                }
            }
        }
    }

    private class FakeDeviceAdb(
        private val shellOutputs: Map<String, String> = emptyMap(),
    ) : IDeviceAdb {
        override val displayName: String? = "fake_device"
        override val api: Int = 34
        override val serial: String = "emulator-5554"
        override val isOnline: Boolean = true

        override fun execAdbShellCmd(cmd: String): String = shellOutputs[cmd].orEmpty()
        override fun push(from: File, to: String): Boolean = true
        override fun pull(from: String, to: File): Boolean = true
        override fun getDefaultLaunchActivity(apkFile: File): String? = null
        override fun getArch(packageName: String): String = "ARCH_64_BIT"
        override fun getProperty(name: String): String? = null
    }

    private class FakeDeployTargetManager(
        private val selected: List<IDevice>,
        private val connected: List<IDevice>,
        private val packageName: String,
        private val logs: String,
    ) : IDeployTargetManager {
        override fun setApks(apks: List<ApkInfo>) {
            // no-op
        }

        override fun getApks(): List<ApkInfo> = emptyList()

        override fun getSelectedDevices(): List<IDevice> = selected

        override fun getConnectedDevices(): List<IDevice> = connected

        override fun startApp(device: IDevice): Boolean = true

        override fun restartApp(device: IDevice): Boolean = true

        override fun stopApp(device: IDevice): Boolean = true

        override fun isAppForeground(device: IDevice): Boolean = false

        override fun getPackageName(): String = packageName

        override fun dumpErrorLogs(): String = logs
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

        override fun showChangeConfirmDialog(
            diffResult: DependencyDiffResult?,
            isRunLater: Boolean,
            logger: Logger,
        ): ConfirmResult {
            throw UnsupportedOperationException("not used")
        }

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
        override fun toDeviceAdb(device: IDevice): IDeviceAdb? = adbByDevice[device]
        override fun isHasRelaunchActivityIssues(device: IDeviceAdb, logger: Logger): Boolean = false
        override fun invokeMcp(request: com.sickworm.intellij.jugg.mcp.McpJsonRpcRequest): com.sickworm.intellij.jugg.mcp.McpJsonRpcResponse {
            throw UnsupportedOperationException("not used")
        }

        override fun getInitializedProjectDirs(): List<File> = emptyList()

        override fun executeGradleCompile(autoConfirm: Boolean, useCleanAndReinstall: Boolean) {
            throw UnsupportedOperationException("not used")
        }
    }
}
