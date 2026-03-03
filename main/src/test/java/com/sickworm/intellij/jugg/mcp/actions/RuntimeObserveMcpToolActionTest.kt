package com.sickworm.intellij.jugg.mcp.actions

import com.android.ddmlib.IDevice
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.sickworm.intellij.jugg.compiler.CompileUiHandler
import com.sickworm.intellij.jugg.compiler.ForceGradleCompileHelper
import com.sickworm.intellij.jugg.compiler.GradleCompileExecutionResult
import com.sickworm.intellij.jugg.compiler.RemoteSshInfoResult
import com.sickworm.intellij.jugg.deploy.IDeviceAdb
import com.sickworm.intellij.jugg.deploy.IDeployTargetManager
import com.sickworm.intellij.jugg.ide.bean.ConfirmResult
import com.sickworm.intellij.jugg.ide.bean.JuggGradleCompileOptions
import com.sickworm.intellij.jugg.ide.logic.IJuggConfigurationRunner
import com.sickworm.intellij.jugg.ide.logic.JuggRunInvocationResult
import com.sickworm.intellij.jugg.mcp.IMcpRuntime
import com.sickworm.intellij.jugg.mcp.McpToolStatus
import com.sickworm.intellij.jugg.platform.IPlatformApi
import com.sickworm.intellij.jugg.platform.PlatformApi
import com.sickworm.intellij.jugg.project.dependency.DependencyDiffResult
import org.junit.Assert
import org.junit.Test
import org.mockito.Mockito
import java.io.File

/**
 * RuntimeObserveMcpToolActionTest covers runtime-observe MCP actions with device/adb stubs.
 */
class RuntimeObserveMcpToolActionTest {

    @Test
    fun testCrashReportReturnsCrashSummaryAndArtifact() {
        val projectDir = createTempDir(prefix = "jugg_mcp_crash_report_")
        val device = Mockito.mock(IDevice::class.java)
        val adb = FakeDeviceAdb(
            shellOutputs = mapOf(
                "dumpsys activity activities" to "topResumedActivity: ActivityRecord{123 t9 com.example.app/.MainActivity}",
                "pidof com.example.app" to "",
                "ps | grep com.example.app" to "",
            )
        )
        PlatformApi.impl = FakePlatformApi(mapOf(device to adb))

        val deployTargetManager = Mockito.mock(IDeployTargetManager::class.java)
        Mockito.`when`(deployTargetManager.getSelectedDevices()).thenReturn(listOf(device))
        Mockito.`when`(deployTargetManager.getConnectedDevices()).thenReturn(listOf(device))
        Mockito.`when`(deployTargetManager.getPackageName()).thenReturn("com.example.app")
        Mockito.`when`(deployTargetManager.dumpErrorLogs()).thenReturn(
            """
            02-28 10:00:00.000 1234 1234 I ActivityManager: Start proc
            02-28 10:00:02.000 1234 1234 E AndroidRuntime: FATAL EXCEPTION: main
            02-28 10:00:02.001 1234 1234 E AndroidRuntime: Process: com.example.app, PID: 1234
            02-28 10:00:02.002 1234 1234 E AndroidRuntime: java.lang.IllegalStateException: boom
            02-28 10:00:02.003 1234 1234 E AndroidRuntime:    at com.example.MainActivity.onCreate(MainActivity.kt:10)
            """.trimIndent()
        )

        val action = CrashReportMcpToolAction()
        val result = action.execute(
            mapOf(
                "projectDir" to projectDir.absolutePath,
                "packageName" to "com.example.app",
            ),
            runtime(projectDir, deployTargetManager)
        )

        Assert.assertEquals(McpToolStatus.OK, result.status)
        @Suppress("UNCHECKED_CAST")
        val data = result.data as Map<String, Any>
        Assert.assertEquals(true, data["hasCrash"])
        Assert.assertEquals(false, data["isProcessAlive"])
        Assert.assertEquals("com.example.app/.MainActivity", data["relatedActivity"])
        Assert.assertEquals("com.example.app", data["packageName"])
        val crashLogs = data["crashLogs"] as List<*>
        Assert.assertFalse(crashLogs.isEmpty())
        val allErrorLogPath = data["allErrorLogPath"] as String
        Assert.assertTrue(File(allErrorLogPath).exists())
        Assert.assertEquals("log", result.artifacts.firstOrNull()?.type)
    }

    @Test
    fun testLayoutDumpReturnsErrorWhenPackageMissing() {
        val projectDir = createTempDir(prefix = "jugg_mcp_layout_dump_")
        val device = Mockito.mock(IDevice::class.java)
        val adb = FakeDeviceAdb()
        PlatformApi.impl = FakePlatformApi(mapOf(device to adb))

        val deployTargetManager = Mockito.mock(IDeployTargetManager::class.java)
        Mockito.`when`(deployTargetManager.getSelectedDevices()).thenReturn(listOf(device))
        Mockito.`when`(deployTargetManager.getConnectedDevices()).thenReturn(listOf(device))

        val action = LayoutDumpMcpToolAction()
        val result = action.execute(mapOf("projectDir" to projectDir.absolutePath), runtime(projectDir, deployTargetManager))

        Assert.assertEquals(McpToolStatus.ERROR, result.status)
        Assert.assertTrue(result.message.contains("failed to resolve package name"))
    }

    private fun runtime(projectDir: File, deployTargetManager: IDeployTargetManager): IMcpRuntime {
        val project = Mockito.mock(Project::class.java)
        Mockito.`when`(project.basePath).thenReturn(projectDir.absolutePath)
        return object : IMcpRuntime {
            override val logger: com.intellij.openapi.diagnostic.Logger
                get() = com.intellij.openapi.diagnostic.Logger.getInstance("TestMcpRuntime")
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
        private val pullHandler: (from: String, to: File, attempt: Int) -> Boolean = { _, to, _ ->
            to.parentFile?.mkdirs()
            to.writeText("ok")
            true
        },
    ) : IDeviceAdb {
        override val displayName: String? = "fake_device"
        override val api: Int = 34
        override val serial: String = "emulator-5554"
        override val isOnline: Boolean = true

        var pullCount: Int = 0
            private set

        override fun execAdbShellCmd(cmd: String): String {
            if (cmd.startsWith("uiautomator dump ")) {
                return "UI hierchary dumped"
            }
            return shellOutputs[cmd].orEmpty()
        }

        override fun push(from: File, to: String): Boolean = true

        override fun pull(from: String, to: File): Boolean {
            pullCount += 1
            return pullHandler(from, to, pullCount)
        }

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
