package com.sickworm.intellij.jugg.ai.mcp.actions

import com.sickworm.intellij.jugg.deploy.api.IDevice
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.sickworm.intellij.jugg.ai.mcp.IMcpRuntime
import com.sickworm.intellij.jugg.ai.mcp.McpErrorCode
import com.sickworm.intellij.jugg.ai.mcp.McpToolStatus
import com.sickworm.intellij.jugg.ai.mcp.viewhierarchy.FindElementsResult
import com.sickworm.intellij.jugg.ai.mcp.viewhierarchy.MatchCandidate
import com.sickworm.intellij.jugg.ai.mcp.viewhierarchy.SourceLocation
import com.sickworm.intellij.jugg.ai.mcp.viewhierarchy.ViewHierarchyClient
import com.sickworm.intellij.jugg.deploy.IDeployTargetManager
import com.sickworm.intellij.jugg.deploy.IDeviceAdb
import com.sickworm.intellij.jugg.ide.logic.IJuggConfigurationRunner
import com.sickworm.intellij.jugg.platform.IPlatformApi
import com.sickworm.intellij.jugg.platform.PlatformApi
import org.junit.After
import org.junit.Assert
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import java.io.File

/**
 * ViewLocateMcpToolActionTest verifies the public selector and result contract.
 */
class ViewLocateMcpToolActionTest {

    private val action = UiFindMcpToolAction()

    @Before
    fun setUp() {
        McpAppReadyGuard.resetForTest()
    }

    @After
    fun tearDown() {
        McpAppReadyGuard.resetForTest()
    }

    @Test
    fun toolNameIsViewLocate() {
        Assert.assertEquals("view-locate", action.toolName)
    }

    @Test
    fun definitionNameMatchesToolName() {
        Assert.assertEquals("view-locate", action.definition.name)
    }

    @Test
    fun descriptionContainsUseFor() {
        Assert.assertTrue(
            "description should contain 'Use for'",
            action.definition.description.contains("Use for")
        )
    }

    @Test
    fun descriptionContainsDoNotUseFor() {
        Assert.assertTrue(
            "description should contain 'Do NOT use for'",
            action.definition.description.contains("Do NOT use for")
        )
    }

    @Test
    fun descriptionMentionsViewInspect() {
        Assert.assertTrue(
            "description should reference view-inspect as alternative",
            action.definition.description.contains("view-inspect")
        )
    }

    @Test
    fun definitionExposesUnifiedSelectorAndBudgets() {
        val properties = action.definition.inputSchema.properties
        val target = properties.getValue("target")

        Assert.assertTrue(target.properties.orEmpty().containsKey("className"))
        Assert.assertEquals(true, properties.getValue("visibleOnly").default)
        Assert.assertEquals(10, properties.getValue("maxResults").default)
        Assert.assertEquals(1.0, properties.getValue("maxResults").minimum)
        Assert.assertEquals(100.0, properties.getValue("maxResults").maximum)
        Assert.assertTrue(properties.containsKey("figmaNode"))
    }

    @Test
    fun executeRejectsNonIntegerMaxResults() {
        val projectDir = createTempDir(prefix = "jugg_view_locate_")
        val runtime = buildRuntime(projectDir)

        listOf("3", 1.5, 101).forEach { invalidValue ->
            val result = action.execute(
                mapOf(
                    "projectDir" to projectDir.absolutePath,
                    "target" to mapOf("text" to "Avatar"),
                    "maxResults" to invalidValue,
                ),
                runtime,
            )

            Assert.assertEquals(McpToolStatus.ERROR, result.status)
            Assert.assertEquals(McpErrorCode.INVALID_PARAMS, result.errorCode)
        }
    }

    @Test
    fun executeUsesRuntimeSelectorAndReturnsUniqueMatchInDpWithSource() {
        val projectDir = createTempDir(prefix = "jugg_view_locate_")
        val runtime = buildRuntime(projectDir)
        lateinit var client: ViewHierarchyClient

        Mockito.mockConstruction(ViewHierarchyClient::class.java) { mock, _ ->
            client = mock
            Mockito.`when`(
                mock.findElements(anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull(), any(), any())
            ).thenReturn(
                FindElementsResult(
                    matchCount = 1,
                    returnedCount = 1,
                    truncated = false,
                    density = 2.0,
                    matches = listOf(
                        MatchCandidate(
                            text = "Unique MCP Target",
                            resourceId = "btn_mcp_unique_text",
                            contentDesc = "Submit",
                            className = "Button",
                            bounds = listOf(10, 20, 110, 70),
                            centerX = 60,
                            centerY = 45,
                            source = SourceLocation("CheckoutScreen.kt", 27),
                        )
                    ),
                )
            )
        }.use {
            val result = action.execute(
                mapOf(
                    "projectDir" to projectDir.absolutePath,
                    "target" to mapOf(
                        "text" to "Unique MCP Target",
                        "resourceId" to "btn_mcp_unique_text",
                        "contentDesc" to "Submit",
                        "className" to "android.widget.Button",
                    ),
                    "visibleOnly" to false,
                    "maxResults" to 3,
                ),
                runtime,
            )

            Assert.assertEquals(McpToolStatus.OK, result.status)
            @Suppress("UNCHECKED_CAST")
            val data = result.data as Map<String, Any>
            Assert.assertEquals(true, data["found"])
            Assert.assertEquals(listOf(5, 10, 55, 35), data["bounds"])
            Assert.assertEquals(mapOf("file" to "CheckoutScreen.kt", "line" to 27), data["source"])
            Mockito.verify(client).findElements(
                "Unique MCP Target",
                "btn_mcp_unique_text",
                "Submit",
                "android.widget.Button",
                false,
                3,
            )
        }
    }

    @Test
    fun executeReturnsBudgetMetadataWithoutImplicitFirstMatch() {
        val projectDir = createTempDir(prefix = "jugg_view_locate_")
        val runtime = buildRuntime(projectDir)

        Mockito.mockConstruction(ViewHierarchyClient::class.java) { mock, _ ->
            Mockito.`when`(
                mock.findElements(anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull(), any(), any())
            ).thenReturn(
                FindElementsResult(
                    matchCount = 4,
                    returnedCount = 2,
                    truncated = true,
                    density = 1.0,
                    matches = listOf(
                        candidate("btn_mcp_repeat_a", 20),
                        candidate("btn_mcp_repeat_b", 90),
                    ),
                )
            )
        }.use {
            val result = action.execute(
                mapOf(
                    "projectDir" to projectDir.absolutePath,
                    "target" to mapOf("text" to "Repeat Tap Target"),
                ),
                runtime,
            )

            Assert.assertEquals(McpToolStatus.OK, result.status)
            @Suppress("UNCHECKED_CAST")
            val data = result.data as Map<String, Any>
            Assert.assertEquals(4, data["matchCount"])
            Assert.assertEquals(2, data["returnedCount"])
            Assert.assertEquals(true, data["truncated"])
            Assert.assertFalse(data.containsKey("bounds"))
            Assert.assertFalse(data.containsKey("position"))
            Assert.assertFalse(data.containsKey("size"))
            @Suppress("UNCHECKED_CAST")
            val matches = data["matches"] as List<Map<String, Any?>>
            Assert.assertEquals("btn_mcp_repeat_a", matches[0]["resourceId"])
            Assert.assertEquals("btn_mcp_repeat_b", matches[1]["resourceId"])
        }
    }

    @Test
    fun executeUsesExplicitSerialForViewLookup() {
        val projectDir = createTempDir(prefix = "jugg_view_locate_serial_")
        val selectedDevice = Mockito.mock(IDevice::class.java)
        val targetDevice = Mockito.mock(IDevice::class.java)
        val selectedAdb = FakeDeviceAdb("device-1")
        val targetAdb = FakeDeviceAdb("device-2")
        PlatformApi.impl = FakePlatformApi(mapOf(selectedDevice to selectedAdb, targetDevice to targetAdb))
        val deployTargetManager = Mockito.mock(IDeployTargetManager::class.java)
        Mockito.`when`(deployTargetManager.getSelectedDevices()).thenReturn(listOf(selectedDevice))
        Mockito.`when`(deployTargetManager.getTargetDevices("device-2")).thenReturn(listOf(targetDevice))
        Mockito.`when`(deployTargetManager.getPackageName()).thenReturn("com.example.app")
        val deployStateManager = Mockito.mock(com.sickworm.intellij.jugg.deploy.IDeployStateManager::class.java)
        Mockito.`when`(deployStateManager.updateDeployState(targetDevice))
            .thenReturn(com.sickworm.intellij.jugg.deploy.JuggDeployState.READY)
        val runtime = object : com.sickworm.intellij.jugg.ai.mcp.TestMcpRuntime() {
            override val logger: Logger = Logger.getInstance("ViewLocateSerialTest")
            override val projectDir: String = projectDir.absolutePath
            override val deployTargetManager: IDeployTargetManager = deployTargetManager
            override val deployStateManager = deployStateManager
            override val forceGradleCompileHelper = FakeForceGradleCompileHelper()
            override val juggConfigurationRunner: IJuggConfigurationRunner = FakeJuggConfigurationRunner()
        }
        var constructedAdb: Any? = null

        Mockito.mockConstruction(ViewHierarchyClient::class.java) { mock, context ->
            constructedAdb = context.arguments().firstOrNull()
            Mockito.`when`(
                mock.findElements(anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull(), any(), any())
            ).thenReturn(
                FindElementsResult(
                    matchCount = 1,
                    returnedCount = 1,
                    truncated = false,
                    density = 1.0,
                    matches = listOf(
                        MatchCandidate(
                            text = "Target",
                            resourceId = "target",
                            contentDesc = "",
                            className = "Button",
                            bounds = listOf(0, 0, 10, 10),
                            centerX = 5,
                            centerY = 5,
                            source = null,
                        )
                    ),
                )
            )
        }.use {
            val result = action.execute(
                mapOf(
                    "projectDir" to projectDir.absolutePath,
                    "serial" to "device-2",
                    "target" to mapOf("text" to "Target"),
                ),
                runtime,
            )

            Assert.assertEquals(McpToolStatus.OK, result.status)
            Assert.assertSame(targetAdb, constructedAdb)
            Mockito.verify(deployStateManager).updateDeployState(targetDevice)
        }
    }

    private fun candidate(resourceId: String, top: Int): MatchCandidate {
        return MatchCandidate(
            text = "Repeat Tap Target",
            resourceId = resourceId,
            contentDesc = "",
            className = "Button",
            bounds = listOf(10, top, 110, top + 50),
            centerX = 60,
            centerY = top + 25,
            source = null,
        )
    }

    private fun buildRuntime(projectDir: File): IMcpRuntime {
        val device = Mockito.mock(IDevice::class.java)
        val adb = FakeDeviceAdb()
        PlatformApi.impl = FakePlatformApi(mapOf(device to adb))

        val deployTargetManager = Mockito.mock(IDeployTargetManager::class.java)
        Mockito.`when`(deployTargetManager.getSelectedDevices()).thenReturn(listOf(device))
        Mockito.`when`(deployTargetManager.getConnectedDevices()).thenReturn(listOf(device))
        Mockito.`when`(deployTargetManager.getPackageName()).thenReturn("com.example.app")

        return object : com.sickworm.intellij.jugg.ai.mcp.TestMcpRuntime() {
            override val logger: Logger = Logger.getInstance("ViewLocateTest")
            override val projectDir: String = projectDir.absolutePath
            override val deployTargetManager: IDeployTargetManager = deployTargetManager
            override val forceGradleCompileHelper = FakeForceGradleCompileHelper()
            override val juggConfigurationRunner: IJuggConfigurationRunner = FakeJuggConfigurationRunner()
            override fun isAppReadyDeploy(): Boolean = true
        }
    }

    private class FakeDeviceAdb(
        override val serial: String = "emulator-5554",
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
        override fun invokeMcp(request: com.sickworm.intellij.jugg.ai.mcp.McpJsonRpcRequest) =
            throw UnsupportedOperationException("not used")
        override fun getInitializedProjectDirs(): List<File> = emptyList()
        override fun executeGradleCompile(autoConfirm: Boolean, useCleanAndReinstall: Boolean) =
            throw UnsupportedOperationException("not used")
    }
}
