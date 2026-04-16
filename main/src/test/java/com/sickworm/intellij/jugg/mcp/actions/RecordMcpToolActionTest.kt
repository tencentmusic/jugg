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
 * RecordMcpToolActionTest verifies start_record/stop_record validation order and app-ready gating.
 */
class RecordMcpToolActionTest {
    @Test
    fun testStartRecordReturnsAppNotReadyError() {
        val projectDir = createTempDir(prefix = "jugg_mcp_start_record_not_ready_")
        val setup = setup(projectDir) { false }
        val action = StartRecordMcpToolAction()

        try {
            val result = action.execute(
                mapOf("projectDir" to projectDir.absolutePath),
                setup.runtime,
            )

            Assert.assertEquals(McpToolStatus.ERROR, result.status)
            Assert.assertEquals(McpErrorCode.INTERNAL_ERROR, result.errorCode)
            Assert.assertTrue(result.message.contains("app is not ready"))
            Assert.assertNull(RecordSessionRegistry.findBySerial(setup.adb.serial))
        } finally {
            val existing = RecordSessionRegistry.findBySerial(setup.adb.serial)
            if (existing != null) {
                RecordSessionRegistry.remove(existing.sessionId)
            }
        }
    }

    @Test
    fun testStopRecordBlankSessionIdReturnsInvalidParamsBeforeAppReady() {
        val projectDir = createTempDir(prefix = "jugg_mcp_stop_record_invalid_params_")
        var readyChecks = 0
        val setup = setup(projectDir) {
            readyChecks += 1
            false
        }
        val action = StopRecordMcpToolAction()

        val result = action.execute(
            mapOf("projectDir" to projectDir.absolutePath, "sessionId" to " "),
            setup.runtime,
        )

        Assert.assertEquals(McpToolStatus.ERROR, result.status)
        Assert.assertEquals(McpErrorCode.INVALID_PARAMS, result.errorCode)
        Assert.assertTrue(result.message.contains("sessionId is required"))
        Assert.assertEquals(0, readyChecks)
    }

    @Test
    fun testStopRecordReturnsAppNotReadyErrorWhenSessionExists() {
        val projectDir = createTempDir(prefix = "jugg_mcp_stop_record_not_ready_")
        var readyChecks = 0
        val setup = setup(projectDir) {
            readyChecks += 1
            false
        }
        val action = StopRecordMcpToolAction()
        val session = RecordSessionRegistry.RecordSession(
            sessionId = "rec_test",
            serial = setup.adb.serial,
            pid = "1234",
            remoteFile = "/sdcard/Download/jugg_mcp/rec_test.mp4",
            localFilePath = File(projectDir, "rec_test.mp4").absolutePath,
            startedAtMs = System.currentTimeMillis(),
            launchMode = "HOST_ADB",
            hostProcess = null,
        )
        RecordSessionRegistry.registerIfAbsent(session)

        try {
            val result = action.execute(
                mapOf("projectDir" to projectDir.absolutePath, "sessionId" to session.sessionId),
                setup.runtime,
            )

            Assert.assertEquals(McpToolStatus.ERROR, result.status)
            Assert.assertEquals(McpErrorCode.INTERNAL_ERROR, result.errorCode)
            Assert.assertTrue(result.message.contains("app is not ready"))
            Assert.assertTrue(readyChecks > 0)
        } finally {
            RecordSessionRegistry.remove(session.sessionId)
        }
    }

    private fun setup(
        projectDir: File,
        isAppReadyProvider: () -> Boolean,
    ): SetupResult {
        val device = Mockito.mock(IDevice::class.java)
        Mockito.`when`(device.serialNumber).thenReturn("emulator-5554")
        val adb = FakeDeviceAdb(serial = "emulator-5554")
        PlatformApi.impl = FakePlatformApi(mapOf(device to adb))

        val deployTargetManager = Mockito.mock(IDeployTargetManager::class.java)
        Mockito.`when`(deployTargetManager.getSelectedDevices()).thenReturn(listOf(device))
        Mockito.`when`(deployTargetManager.getConnectedDevices()).thenReturn(listOf(device))
        return SetupResult(
            runtime = buildRuntime(projectDir, deployTargetManager, isAppReadyProvider),
            adb = adb,
        )
    }

    private fun buildRuntime(
        projectDir: File,
        deployTargetManager: IDeployTargetManager,
        isAppReadyProvider: () -> Boolean,
    ): IMcpRuntime {
        val project = Mockito.mock(Project::class.java)
        Mockito.`when`(project.basePath).thenReturn(projectDir.absolutePath)
        return object : IMcpRuntime {
            override val logger: com.intellij.openapi.diagnostic.Logger
                get() = com.intellij.openapi.diagnostic.Logger.getInstance("RecordMcpToolActionTest")
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

            override fun isAppReadyDeploy(): Boolean {
                return isAppReadyProvider()
            }
        }
    }

    private data class SetupResult(
        val runtime: IMcpRuntime,
        val adb: FakeDeviceAdb,
    )

    private class FakeDeviceAdb(
        override val serial: String,
    ) : IDeviceAdb {
        override val displayName: String? = "fake_device"
        override val api: Int = 34
        override val isOnline: Boolean = true

        override fun execAdbShellCmd(cmd: String): String = ""
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
