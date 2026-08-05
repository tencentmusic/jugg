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
import com.sickworm.intellij.jugg.ai.mcp.McpErrorCode
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
 * EvalViewMcpToolActionTest verifies parameter validation and app-ready gating
 * for the view-inspect MCP tool.
 */
class EvalViewMcpToolActionTest {

    private val action = EvalViewMcpToolAction()

    @Before
    fun setUp() {
        McpAppReadyGuard.sleepForTest = {}
        McpAppReadyGuard.preTimeoutOverrideForTest = 1L
        McpAppReadyGuard.prePollIntervalOverrideForTest = 1L
        McpAppReadyGuard.preFailureRetryIntervalOverrideForTest = 0L
    }

    @After
    fun tearDown() {
        McpAppReadyGuard.resetForTest()
    }

    @Test
    fun toolNameIsViewInspect() {
        Assert.assertEquals("view-inspect", action.toolName)
    }

    @Test
    fun definitionNameMatchesToolName() {
        Assert.assertEquals("view-inspect", action.definition.name)
    }

    @Test
    fun missingTargetReturnsInvalidParams() {
        val projectDir = createTempDir(prefix = "jugg_eval_view_")
        val setup = setup(projectDir) { true }

        val result = action.execute(
            mapOf(
                "projectDir" to projectDir.absolutePath,
                "expressions" to listOf("getText()"),
            ),
            setup.runtime,
        )

        Assert.assertEquals(McpToolStatus.ERROR, result.status)
        Assert.assertEquals(McpErrorCode.INVALID_PARAMS, result.errorCode)
        Assert.assertTrue(result.message.contains("target"))
    }

    @Test
    fun emptyTargetSelectorsReturnsInvalidParams() {
        val projectDir = createTempDir(prefix = "jugg_eval_view_")
        val setup = setup(projectDir) { true }

        val result = action.execute(
            mapOf(
                "projectDir" to projectDir.absolutePath,
                "target" to mapOf<String, Any?>(),
                "expressions" to listOf("getText()"),
            ),
            setup.runtime,
        )

        Assert.assertEquals(McpToolStatus.ERROR, result.status)
        Assert.assertEquals(McpErrorCode.INVALID_PARAMS, result.errorCode)
        Assert.assertTrue(result.message.contains("at least one selector"))
    }

    @Test
    fun missingExpressionsReturnsInvalidParams() {
        val projectDir = createTempDir(prefix = "jugg_eval_view_")
        val setup = setup(projectDir) { true }

        val result = action.execute(
            mapOf(
                "projectDir" to projectDir.absolutePath,
                "target" to mapOf("resourceId" to "tv_title"),
            ),
            setup.runtime,
        )

        Assert.assertEquals(McpToolStatus.ERROR, result.status)
        Assert.assertEquals(McpErrorCode.INVALID_PARAMS, result.errorCode)
        Assert.assertTrue(result.message.contains("expressions"))
    }

    @Test
    fun emptyExpressionsReturnsInvalidParams() {
        val projectDir = createTempDir(prefix = "jugg_eval_view_")
        val setup = setup(projectDir) { true }

        val result = action.execute(
            mapOf(
                "projectDir" to projectDir.absolutePath,
                "target" to mapOf("resourceId" to "tv_title"),
                "expressions" to emptyList<String>(),
            ),
            setup.runtime,
        )

        Assert.assertEquals(McpToolStatus.ERROR, result.status)
        Assert.assertEquals(McpErrorCode.INVALID_PARAMS, result.errorCode)
        Assert.assertTrue(result.message.contains("expressions"))
    }

    @Test
    fun tooManyExpressionsReturnsInvalidParams() {
        val projectDir = createTempDir(prefix = "jugg_eval_view_")
        val setup = setup(projectDir) { true }

        val expressions = (1..21).map { "getText()" }
        val result = action.execute(
            mapOf(
                "projectDir" to projectDir.absolutePath,
                "target" to mapOf("resourceId" to "tv_title"),
                "expressions" to expressions,
            ),
            setup.runtime,
        )

        Assert.assertEquals(McpToolStatus.ERROR, result.status)
        Assert.assertEquals(McpErrorCode.INVALID_PARAMS, result.errorCode)
        Assert.assertTrue(result.message.contains("exceeds maximum"))
    }

    @Test
    fun appNotReadyReturnsError() {
        val projectDir = createTempDir(prefix = "jugg_eval_view_")
        val setup = setup(projectDir) { false }

        val result = action.execute(
            mapOf(
                "projectDir" to projectDir.absolutePath,
                "target" to mapOf("resourceId" to "tv_title"),
                "expressions" to listOf("getText()"),
            ),
            setup.runtime,
        )

        Assert.assertEquals(McpToolStatus.ERROR, result.status)
        Assert.assertEquals(McpErrorCode.INTERNAL_ERROR, result.errorCode)
        Assert.assertTrue(result.message.contains("app is not ready"))
    }

    @Test
    fun paramValidationBeforeAppReadyCheck() {
        val projectDir = createTempDir(prefix = "jugg_eval_view_")
        var readyChecks = 0
        val setup = setup(projectDir) {
            readyChecks++
            false
        }

        val result = action.execute(
            mapOf(
                "projectDir" to projectDir.absolutePath,
                "target" to mapOf<String, Any?>(),
                "expressions" to listOf("getText()"),
            ),
            setup.runtime,
        )

        Assert.assertEquals(McpToolStatus.ERROR, result.status)
        Assert.assertEquals(McpErrorCode.INVALID_PARAMS, result.errorCode)
        Assert.assertEquals(0, readyChecks)
    }

    @Test
    fun noDeviceReturnsError() {
        val projectDir = createTempDir(prefix = "jugg_eval_view_")
        val deployTargetManager = Mockito.mock(IDeployTargetManager::class.java)
        Mockito.`when`(deployTargetManager.getSelectedDevices()).thenReturn(emptyList())
        Mockito.`when`(deployTargetManager.getConnectedDevices()).thenReturn(emptyList())
        PlatformApi.impl = FakePlatformApi(emptyMap())

        val runtime = buildRuntime(projectDir, deployTargetManager) { true }

        val result = action.execute(
            mapOf(
                "projectDir" to projectDir.absolutePath,
                "target" to mapOf("resourceId" to "tv_title"),
                "expressions" to listOf("getText()"),
            ),
            runtime,
        )

        Assert.assertEquals(McpToolStatus.ERROR, result.status)
        Assert.assertEquals(McpErrorCode.NO_DEVICE, result.errorCode)
    }

    // ---- Test infrastructure ----

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
        Mockito.`when`(deployTargetManager.getPackageName()).thenReturn("com.example.app")
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
        return object : com.sickworm.intellij.jugg.ai.mcp.TestMcpRuntime() {
            override val logger: Logger
                get() = Logger.getInstance("EvalViewMcpToolActionTest")
            override val projectDir: String = projectDir.absolutePath
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
