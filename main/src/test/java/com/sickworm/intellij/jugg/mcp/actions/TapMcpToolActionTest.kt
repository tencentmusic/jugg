package com.sickworm.intellij.jugg.mcp.actions

import com.android.ddmlib.IDevice
import com.google.gson.JsonObject
import com.google.gson.JsonParser
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
import com.sickworm.intellij.jugg.mcp.viewhierarchy.FindAndTapResult
import com.sickworm.intellij.jugg.mcp.viewhierarchy.MatchCandidate
import com.sickworm.intellij.jugg.mcp.viewhierarchy.ViewHierarchyClient
import com.sickworm.intellij.jugg.platform.IPlatformApi
import com.sickworm.intellij.jugg.platform.PlatformApi
import com.sickworm.intellij.jugg.project.dependency.DependencyDiffResult
import org.junit.Assert
import org.junit.Test
import org.mockito.Mockito
import java.io.File

/**
 * TapMcpToolActionTest covers coordinate/percent modes and server-only element mode.
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
    fun testTapElementModeUsesServerSuccess() {
        val (action, adb) = setup(packageName = "com.example.app")
        Mockito.mockConstruction(ViewHierarchyClient::class.java) { mock, _ ->
            Mockito.`when`(mock.findAndTap("Login", null, null, null)).thenReturn(
                FindAndTapResult.Success(
                    x = 321,
                    y = 654,
                    matchedElement = "text=\"Login\"",
                    matchCount = 1,
                )
            )
        }.use {
            val result = action.execute(
                mapOf("projectDir" to "/tmp/test", "text" to "Login"),
                runtime()
            )
            Assert.assertEquals(McpToolStatus.OK, result.status)
            Assert.assertTrue(adb.tappedCommands.isEmpty())
            @Suppress("UNCHECKED_CAST")
            val data = result.data as Map<String, Any>
            Assert.assertEquals("element", data["mode"])
            Assert.assertEquals(321, data["x"])
            Assert.assertEquals(654, data["y"])
            Assert.assertEquals(1, data["matchCount"])
            Assert.assertEquals("text=\"Login\"", data["matchedElement"])
        }
    }

    @Test
    fun testTapElementModeUsesServerMultipleMatches() {
        val (action, adb) = setup(packageName = "com.example.app")
        val boundsA = jsonObject("""{"left":1,"top":2,"right":101,"bottom":102}""")
        val boundsB = jsonObject("""{"left":201,"top":202,"right":301,"bottom":302}""")
        Mockito.mockConstruction(ViewHierarchyClient::class.java) { mock, _ ->
            Mockito.`when`(mock.findAndTap("Item", null, null, null)).thenReturn(
                FindAndTapResult.Multiple(
                    matchCount = 2,
                    matches = listOf(
                        MatchCandidate("Item", "id/a", "", "android.widget.TextView", boundsA, 51, 52),
                        MatchCandidate("Item", "id/b", "", "android.widget.TextView", boundsB, 251, 252),
                    ),
                    message = "multiple",
                )
            )
        }.use {
            val result = action.execute(
                mapOf("projectDir" to "/tmp/test", "text" to "Item"),
                runtime()
            )
            Assert.assertEquals(McpToolStatus.ERROR, result.status)
            Assert.assertEquals(McpErrorCode.MCP_INVALID_PARAMS, result.errorCode)
            Assert.assertTrue(adb.tappedCommands.isEmpty())
            @Suppress("UNCHECKED_CAST")
            val data = result.data as Map<String, Any>
            Assert.assertEquals("element", data["mode"])
            Assert.assertEquals(2, data["matchCount"])
            @Suppress("UNCHECKED_CAST")
            val matches = data["matches"] as List<Map<String, Any>>
            Assert.assertEquals(2, matches.size)
            Assert.assertEquals("{\"left\":1,\"top\":2,\"right\":101,\"bottom\":102}", matches[0]["bounds"])
            Assert.assertEquals(51, matches[0]["centerX"])
            Assert.assertEquals(251, matches[1]["centerX"])
        }
    }

    @Test
    fun testTapElementModeUsesServerNotFound() {
        val (action, adb) = setup(packageName = "com.example.app")
        Mockito.mockConstruction(ViewHierarchyClient::class.java) { mock, _ ->
            Mockito.`when`(mock.findAndTap("Missing", null, null, null)).thenReturn(
                FindAndTapResult.NotFound(
                    candidates = listOf(
                        MatchCandidate(
                            text = "Login",
                            resourceId = "com.example:id/login",
                            contentDesc = "",
                            className = "android.widget.Button",
                            bounds = null,
                            centerX = 10,
                            centerY = 20,
                        )
                    ),
                    message = "not found",
                )
            )
        }.use {
            val result = action.execute(
                mapOf("projectDir" to "/tmp/test", "text" to "Missing"),
                runtime()
            )
            Assert.assertEquals(McpToolStatus.ERROR, result.status)
            Assert.assertEquals(McpErrorCode.MCP_INTERNAL_ERROR, result.errorCode)
            Assert.assertTrue(result.message.contains("No matching UI element found"))
            Assert.assertTrue(result.message.contains("Login"))
            Assert.assertTrue(result.message.contains("com.example:id/login"))
            Assert.assertTrue(result.message.contains("android.widget.Button"))
            Assert.assertTrue(adb.tappedCommands.isEmpty())
        }
    }

    @Test
    fun testTapElementModeServerFailureReturnsError() {
        val (action, adb) = setup(packageName = "com.example.app")
        Mockito.mockConstruction(ViewHierarchyClient::class.java) { mock, _ ->
            Mockito.`when`(mock.findAndTap("Any", null, null, null)).thenReturn(
                FindAndTapResult.Failure("socket failed")
            )
        }.use {
            val result = action.execute(
                mapOf("projectDir" to "/tmp/test", "text" to "Any"),
                runtime()
            )
            Assert.assertEquals(McpToolStatus.ERROR, result.status)
            Assert.assertEquals(McpErrorCode.MCP_INTERNAL_ERROR, result.errorCode)
            Assert.assertTrue(result.message.contains("ViewHierarchy server error"))
            Assert.assertTrue(adb.tappedCommands.isEmpty())
        }
    }

    @Test
    fun testTapElementModeServerUnavailableReturnsError() {
        val (action, _) = setup(packageName = "com.example.app")
        Mockito.mockConstruction(ViewHierarchyClient::class.java) { mock, _ ->
            Mockito.`when`(mock.findAndTap("Any", null, null, null)).thenReturn(null)
        }.use {
            val result = action.execute(
                mapOf("projectDir" to "/tmp/test", "text" to "Any"),
                runtime()
            )
            Assert.assertEquals(McpToolStatus.ERROR, result.status)
            Assert.assertEquals(McpErrorCode.MCP_INTERNAL_ERROR, result.errorCode)
            Assert.assertTrue(result.message.contains("server is unavailable"))
        }
    }

    @Test
    fun testTapElementModeWithoutPackageNameReturnsError() {
        val (action, _) = setup(packageName = null)
        val result = action.execute(
            mapOf("projectDir" to "/tmp/test", "text" to "Any"),
            runtime()
        )
        Assert.assertEquals(McpToolStatus.ERROR, result.status)
        Assert.assertEquals(McpErrorCode.MCP_INTERNAL_ERROR, result.errorCode)
        Assert.assertTrue(result.message.contains("Unable to resolve package name"))
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
        val (action, _) = setup(
            shellOutputs = mapOf(
                "wm size" to "Physical size: 1080x2400",
            ),
            packageName = "com.example.app",
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
        packageName: String? = null,
    ): Pair<TapMcpToolAction, FakeDeviceAdb> {
        val device = Mockito.mock(IDevice::class.java)
        val adb = FakeDeviceAdb(shellOutputs)
        PlatformApi.impl = FakePlatformApi(mapOf(device to adb))

        val deployTargetManager = Mockito.mock(IDeployTargetManager::class.java)
        Mockito.`when`(deployTargetManager.getSelectedDevices()).thenReturn(listOf(device))
        Mockito.`when`(deployTargetManager.getConnectedDevices()).thenReturn(listOf(device))
        if (packageName != null) {
            Mockito.`when`(deployTargetManager.getPackageName()).thenReturn(packageName)
        }
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

    private fun jsonObject(raw: String): JsonObject {
        return JsonParser.parseString(raw).asJsonObject
    }

    private class FakeDeviceAdb(
        private val shellOutputs: Map<String, String> = emptyMap(),
    ) : IDeviceAdb {
        override val displayName: String? = "fake_device"
        override val api: Int = 34
        override val serial: String = "emulator-5554"
        override val isOnline: Boolean = true

        val executedCommands = mutableListOf<String>()
        val tappedCommands = mutableListOf<String>()

        override fun execAdbShellCmd(cmd: String): String {
            executedCommands.add(cmd)
            if (cmd.startsWith("input tap ")) {
                tappedCommands.add(cmd)
                return ""
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
