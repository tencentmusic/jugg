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
 * TapMcpToolActionTest covers all three tap modes (coordinate, percent, element) and edge cases.
 */
class TapMcpToolActionTest {

    @Test
    fun testTapCoordinateMode() {
        val (action, _) = setup()
        val result = action.execute(
            mapOf("projectDir" to "/tmp/test", "x" to 540, "y" to 960),
            runtime()
        )
        Assert.assertEquals(McpToolStatus.OK, result.status)
        @Suppress("UNCHECKED_CAST")
        val data = result.data as Map<String, Any>
        Assert.assertEquals(540, data["x"])
        Assert.assertEquals(960, data["y"])
        Assert.assertEquals("coordinate", data["mode"])
    }

    @Test
    fun testTapPercentMode() {
        val (action, _) = setup(
            shellOutputs = mapOf(
                "wm size" to "Physical size: 1080x2400",
            )
        )
        val result = action.execute(
            mapOf("projectDir" to "/tmp/test", "xPercent" to 50.0, "yPercent" to 50.0),
            runtime()
        )
        Assert.assertEquals(McpToolStatus.OK, result.status)
        @Suppress("UNCHECKED_CAST")
        val data = result.data as Map<String, Any>
        Assert.assertEquals(540, data["x"])
        Assert.assertEquals(1200, data["y"])
        Assert.assertEquals("percent", data["mode"])
        Assert.assertEquals(1080, data["screenWidth"])
        Assert.assertEquals(2400, data["screenHeight"])
    }

    @Test
    fun testTapPercentModeOverrideSize() {
        val (action, _) = setup(
            shellOutputs = mapOf(
                "wm size" to "Physical size: 1080x2400\nOverride size: 720x1280",
            )
        )
        val result = action.execute(
            mapOf("projectDir" to "/tmp/test", "xPercent" to 50.0, "yPercent" to 50.0),
            runtime()
        )
        Assert.assertEquals(McpToolStatus.OK, result.status)
        @Suppress("UNCHECKED_CAST")
        val data = result.data as Map<String, Any>
        Assert.assertEquals(360, data["x"])
        Assert.assertEquals(640, data["y"])
        Assert.assertEquals(720, data["screenWidth"])
        Assert.assertEquals(1280, data["screenHeight"])
    }

    @Test
    fun testTapPercentModeScreenSizeFail() {
        val (action, _) = setup(
            shellOutputs = mapOf(
                "wm size" to "error: no device",
            )
        )
        val result = action.execute(
            mapOf("projectDir" to "/tmp/test", "xPercent" to 50.0, "yPercent" to 50.0),
            runtime()
        )
        Assert.assertEquals(McpToolStatus.ERROR, result.status)
        Assert.assertTrue(result.message.contains("Unable to determine screen size"))
    }

    @Test
    fun testTapElementModeByText() {
        val xml = buildUiXml(
            node(text = "Login", bounds = "[100,200][300,400]", clickable = true)
        )
        val (action, _) = setup(
            shellOutputs = mapOf(
                "cat /sdcard/Download/jugg_mcp/tap_layout.xml" to xml,
            )
        )
        val result = action.execute(
            mapOf("projectDir" to "/tmp/test", "text" to "Login"),
            runtime()
        )
        Assert.assertEquals(McpToolStatus.OK, result.status)
        @Suppress("UNCHECKED_CAST")
        val data = result.data as Map<String, Any>
        Assert.assertEquals(200, data["x"])
        Assert.assertEquals(300, data["y"])
        Assert.assertEquals("element", data["mode"])
        Assert.assertEquals(1, data["matchCount"])
    }

    @Test
    fun testTapElementModeByResourceId() {
        val xml = buildUiXml(
            node(resourceId = "com.example:id/btn_ok", bounds = "[0,0][200,100]")
        )
        val (action, _) = setup(
            shellOutputs = mapOf(
                "cat /sdcard/Download/jugg_mcp/tap_layout.xml" to xml,
            )
        )
        val result = action.execute(
            mapOf("projectDir" to "/tmp/test", "resourceId" to "com.example:id/btn_ok"),
            runtime()
        )
        Assert.assertEquals(McpToolStatus.OK, result.status)
        @Suppress("UNCHECKED_CAST")
        val data = result.data as Map<String, Any>
        Assert.assertEquals(100, data["x"])
        Assert.assertEquals(50, data["y"])
        Assert.assertEquals("element", data["mode"])
    }

    @Test
    fun testTapElementModeByContentDesc() {
        val xml = buildUiXml(
            node(contentDesc = "Play button", bounds = "[400,500][600,700]")
        )
        val (action, _) = setup(
            shellOutputs = mapOf(
                "cat /sdcard/Download/jugg_mcp/tap_layout.xml" to xml,
            )
        )
        val result = action.execute(
            mapOf("projectDir" to "/tmp/test", "contentDesc" to "Play button"),
            runtime()
        )
        Assert.assertEquals(McpToolStatus.OK, result.status)
        @Suppress("UNCHECKED_CAST")
        val data = result.data as Map<String, Any>
        Assert.assertEquals(500, data["x"])
        Assert.assertEquals(600, data["y"])
        Assert.assertEquals("element", data["mode"])
    }

    @Test
    fun testTapElementModeWithClassNameFilter() {
        val xml = buildUiXml(
            node(text = "Submit", className = "android.widget.Button", bounds = "[10,20][110,120]"),
            node(text = "Submit", className = "android.widget.TextView", bounds = "[200,300][400,500]"),
        )
        val (action, _) = setup(
            shellOutputs = mapOf(
                "cat /sdcard/Download/jugg_mcp/tap_layout.xml" to xml,
            )
        )
        val result = action.execute(
            mapOf("projectDir" to "/tmp/test", "text" to "Submit", "className" to "android.widget.Button"),
            runtime()
        )
        Assert.assertEquals(McpToolStatus.OK, result.status)
        @Suppress("UNCHECKED_CAST")
        val data = result.data as Map<String, Any>
        Assert.assertEquals(60, data["x"])
        Assert.assertEquals(70, data["y"])
        Assert.assertEquals(1, data["matchCount"])
    }

    @Test
    fun testTapElementModeMultipleMatchesReturnsError() {
        val xml = buildUiXml(
            node(text = "Item", bounds = "[0,0][100,100]"),
            node(text = "Item", bounds = "[0,200][100,300]"),
            node(text = "Item", bounds = "[0,400][100,500]"),
        )
        val (action, _) = setup(
            shellOutputs = mapOf(
                "cat /sdcard/Download/jugg_mcp/tap_layout.xml" to xml,
            )
        )
        val result = action.execute(
            mapOf("projectDir" to "/tmp/test", "text" to "Item"),
            runtime()
        )
        Assert.assertEquals(McpToolStatus.ERROR, result.status)
        Assert.assertEquals(McpErrorCode.MCP_INVALID_PARAMS, result.errorCode)
        Assert.assertTrue(result.message.contains("3 elements matched"))
        Assert.assertTrue(result.message.contains("coordinate mode"))
        @Suppress("UNCHECKED_CAST")
        val data = result.data as Map<String, Any>
        Assert.assertEquals(3, data["matchCount"])
        @Suppress("UNCHECKED_CAST")
        val matches = data["matches"] as List<Map<String, Any>>
        Assert.assertEquals(3, matches.size)
        Assert.assertEquals(50, matches[0]["centerX"])
        Assert.assertEquals(50, matches[0]["centerY"])
        Assert.assertEquals(50, matches[1]["centerX"])
        Assert.assertEquals(250, matches[1]["centerY"])
    }

    @Test
    fun testTapElementModeExactMatchRejectsSubstring() {
        val xml = buildUiXml(
            node(text = "Login to account", bounds = "[100,200][300,400]", clickable = true),
        )
        val (action, _) = setup(
            shellOutputs = mapOf(
                "cat /sdcard/Download/jugg_mcp/tap_layout.xml" to xml,
            )
        )
        val result = action.execute(
            mapOf("projectDir" to "/tmp/test", "text" to "Login"),
            runtime()
        )
        Assert.assertEquals(McpToolStatus.ERROR, result.status)
        Assert.assertTrue(result.message.contains("No matching UI element found"))
    }

    @Test
    fun testTapElementModeNoMatch() {
        val xml = buildUiXml(
            node(text = "OK", bounds = "[0,0][100,100]", clickable = true),
        )
        val (action, _) = setup(
            shellOutputs = mapOf(
                "cat /sdcard/Download/jugg_mcp/tap_layout.xml" to xml,
            )
        )
        val result = action.execute(
            mapOf("projectDir" to "/tmp/test", "text" to "NonExistent"),
            runtime()
        )
        Assert.assertEquals(McpToolStatus.ERROR, result.status)
        Assert.assertTrue(result.message.contains("No matching UI element found"))
        Assert.assertTrue(result.message.contains("OK"))
    }

    @Test
    fun testTapElementModeDumpFail() {
        val (action, _) = setup(
            shellOutputs = mapOf(
                "cat /sdcard/Download/jugg_mcp/tap_layout.xml" to "",
            )
        )
        val result = action.execute(
            mapOf("projectDir" to "/tmp/test", "text" to "Something"),
            runtime()
        )
        Assert.assertEquals(McpToolStatus.ERROR, result.status)
        Assert.assertTrue(result.message.contains("UI hierarchy dump returned empty"))
    }

    @Test
    fun testTapPriorityCoordinateOverPercent() {
        val (action, _) = setup(
            shellOutputs = mapOf(
                "wm size" to "Physical size: 1080x2400",
            )
        )
        val result = action.execute(
            mapOf("projectDir" to "/tmp/test", "x" to 100, "y" to 200, "xPercent" to 50.0, "yPercent" to 50.0),
            runtime()
        )
        Assert.assertEquals(McpToolStatus.OK, result.status)
        @Suppress("UNCHECKED_CAST")
        val data = result.data as Map<String, Any>
        Assert.assertEquals("coordinate", data["mode"])
        Assert.assertEquals(100, data["x"])
        Assert.assertEquals(200, data["y"])
    }

    @Test
    fun testTapPriorityPercentOverElement() {
        val xml = buildUiXml(
            node(text = "Login", bounds = "[100,200][300,400]"),
        )
        val (action, _) = setup(
            shellOutputs = mapOf(
                "wm size" to "Physical size: 1080x2400",
                "cat /sdcard/Download/jugg_mcp/tap_layout.xml" to xml,
            )
        )
        val result = action.execute(
            mapOf("projectDir" to "/tmp/test", "xPercent" to 10.0, "yPercent" to 20.0, "text" to "Login"),
            runtime()
        )
        Assert.assertEquals(McpToolStatus.OK, result.status)
        @Suppress("UNCHECKED_CAST")
        val data = result.data as Map<String, Any>
        Assert.assertEquals("percent", data["mode"])
    }

    @Test
    fun testTapNoDeviceReturnsNoDevice() {
        PlatformApi.impl = FakePlatformApi(emptyMap())
        val deployTargetManager = Mockito.mock(IDeployTargetManager::class.java)
        Mockito.`when`(deployTargetManager.getSelectedDevices()).thenReturn(emptyList())
        Mockito.`when`(deployTargetManager.getConnectedDevices()).thenReturn(emptyList())

        val action = TapMcpToolAction()
        val result = action.execute(
            mapOf("projectDir" to "/tmp/test", "x" to 100, "y" to 200),
            runtime(deployTargetManager)
        )
        Assert.assertEquals(McpToolStatus.ERROR, result.status)
        Assert.assertEquals(McpErrorCode.MCP_NO_DEVICE, result.errorCode)
    }

    @Test
    fun testTapNoParametersReturnsError() {
        val (action, _) = setup()
        val result = action.execute(
            mapOf("projectDir" to "/tmp/test"),
            runtime()
        )
        Assert.assertEquals(McpToolStatus.ERROR, result.status)
        Assert.assertEquals(McpErrorCode.MCP_INVALID_PARAMS, result.errorCode)
        Assert.assertTrue(result.message.contains("No valid tap mode"))
    }

    // --- Test helpers ---

    private fun setup(
        shellOutputs: Map<String, String> = emptyMap(),
    ): Pair<TapMcpToolAction, FakeDeviceAdb> {
        val device = Mockito.mock(IDevice::class.java)
        val adb = FakeDeviceAdb(shellOutputs)
        PlatformApi.impl = FakePlatformApi(mapOf(device to adb))

        val deployTargetManager = Mockito.mock(IDeployTargetManager::class.java)
        Mockito.`when`(deployTargetManager.getSelectedDevices()).thenReturn(listOf(device))
        Mockito.`when`(deployTargetManager.getConnectedDevices()).thenReturn(listOf(device))
        currentDeployTargetManager = deployTargetManager
        return TapMcpToolAction() to adb
    }

    private var currentDeployTargetManager: IDeployTargetManager? = null

    private fun runtime(dtm: IDeployTargetManager? = null): IMcpRuntime {
        val deployTargetManager = dtm ?: currentDeployTargetManager!!
        val project = Mockito.mock(Project::class.java)
        Mockito.`when`(project.basePath).thenReturn("/tmp/test")
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

    private fun node(
        text: String = "",
        resourceId: String = "",
        contentDesc: String = "",
        className: String = "android.widget.View",
        bounds: String = "[0,0][0,0]",
        clickable: Boolean = false,
    ): String {
        return """<node text="$text" resource-id="$resourceId" content-desc="$contentDesc" """ +
            """class="$className" bounds="$bounds" clickable="$clickable" />"""
    }

    private fun buildUiXml(vararg nodes: String): String {
        return """<?xml version="1.0" encoding="UTF-8"?><hierarchy rotation="0">""" +
            nodes.joinToString("") +
            """</hierarchy>"""
    }

    private class FakeDeviceAdb(
        private val shellOutputs: Map<String, String> = emptyMap(),
    ) : IDeviceAdb {
        override val displayName: String? = "fake_device"
        override val api: Int = 34
        override val serial: String = "emulator-5554"
        override val isOnline: Boolean = true

        val tappedCommands = mutableListOf<String>()

        override fun execAdbShellCmd(cmd: String): String {
            if (cmd.startsWith("input tap ")) {
                tappedCommands.add(cmd)
                return ""
            }
            if (cmd.startsWith("uiautomator dump ")) {
                return "UI hierchary dumped"
            }
            return shellOutputs[cmd].orEmpty()
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
            title: String, content: String, okButtonText: String?,
            cancelButtonText: String?, isShowCancelButton: Boolean,
        ): Boolean = false

        override fun showChangeConfirmDialog(
            diffResult: DependencyDiffResult?, isRunLater: Boolean, logger: Logger,
        ): ConfirmResult {
            throw UnsupportedOperationException("not used")
        }

        override fun showUserAndPasswordInputDialog(
            content: String, subTitle: String?, isPassword: Boolean,
            defaultInputText: String?, title: String?,
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
