package com.sickworm.intellij.jugg.ai.mcp.actions

import com.sickworm.intellij.jugg.deploy.api.IDevice
import com.google.gson.Gson
import com.google.gson.JsonParser
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
import com.sickworm.intellij.jugg.ai.mcp.McpResultMapper
import com.sickworm.intellij.jugg.ai.mcp.McpToolResult
import com.sickworm.intellij.jugg.ai.mcp.McpToolStatus
import com.sickworm.intellij.jugg.ai.mcp.viewhierarchy.LayoutDumpResult
import com.sickworm.intellij.jugg.ai.mcp.viewhierarchy.ViewHierarchyClient
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
import java.nio.charset.StandardCharsets

/**
 * LayoutDumpMcpToolActionTest verifies app-side server-only behavior (no uiautomator fallback).
 */
class LayoutDumpMcpToolActionTest {
    @Before
    fun setUp() {
        McpAppReadyGuard.resetForTest()
        McpAppReadyGuard.preFailureRetryIntervalOverrideForTest = 0L
    }

    @After
    fun tearDown() {
        McpAppReadyGuard.resetForTest()
    }

    @Test
    fun testOffscreenPlaceholderIsNotCountedAndInternalUiArtifactStaysComplete() {
        val projectDir = createTempDir(prefix = "jugg_layout_offscreen_")
        val setup = setup(projectDir, packageName = "com.example.app")
        val payload = """{"deviceInfo":{"density":2},"truncated":false,"windows":[{"root":{
            "className":"FrameLayout","bounds":[0,0,200,200],"children":[
            {"className":"Page","id":"_vir_id_page","bounds":[200,0,400,200],"clickable":true,
            "children":[{"className":"TextView","text":"Offscreen label","bounds":[220,0,240,20],
            "clickable":true}]}]}}]}"""
        Mockito.mockConstruction(ViewHierarchyClient::class.java) { mock, _ ->
            Mockito.`when`(mock.dumpLayout(anyOrNull(), any(), any())).thenReturn(LayoutDumpResult(payload, null))
        }.use {
            val output = LayoutDumpHelper.dumpInternal(setup.runtime, "layout-dump", mode = "interaction")
                as LayoutDumpHelper.DumpInternalResult.Success
            val json = JsonParser.parseString(output.outputFile.readText()).asJsonObject
            Assert.assertEquals(json, output.inlineLayout)
            Assert.assertEquals(output.outputFile.length(), output.contentBytes.toLong())
            Assert.assertTrue(output.summary.contains("2 nodes, 1 clickable"))
            Assert.assertTrue(output.summary.contains("not truncated"))
            val page = json.getAsJsonArray("windows")[0].asJsonObject.getAsJsonObject("root")
                .getAsJsonArray("children")[0].asJsonObject
            Assert.assertEquals("[100,0,200,100]", page["bounds"].toString())
            Assert.assertTrue(page.getAsJsonArray("children")[0].asJsonObject.has("jugg_details"))
            Assert.assertFalse(output.outputFile.readText().contains("Offscreen label"))
            Assert.assertTrue(output.jsonFile.readText().contains("Offscreen label"))
            Assert.assertFalse(output.jsonFile.readText().contains("jugg_details"))
        }
    }

    @Test
    fun testLayoutModesExposeJsonAndPreserveTheirNodeScope() {
        val projectDir = createTempDir(prefix = "jugg_layout_modes_")
        val setup = setup(projectDir, packageName = "com.example.app")
        val payload = """{"deviceInfo":{"density":2,"scaledDensity":2.5},"truncated":false,
            "windows":[{"windowType":"activity","title":"Main","root":{"className":"FrameLayout",
            "id":"_vir_id_root","bounds":[0,0,720,1280],"children":[
            {"className":"FrameLayout","id":"_vir_id_wrapper","children":[
            {"className":"TextView","id":"_vir_id_label","text":"状态","bounds":[20,40,220,80],
            "visibility":"gone","alpha":0.5,"enabled":false,"padding":[2,4,6,8]}]},
            {"className":"ImageView","id":"_vir_id_image","bounds":[0,100,100,200]}]}}]}"""
        Mockito.mockConstruction(ViewHierarchyClient::class.java) { mock, _ ->
            Mockito.`when`(mock.dumpLayout(anyOrNull(), any(), any())).thenReturn(LayoutDumpResult(payload, null))
        }.use {
            for (mode in listOf(null, "interaction", "ui")) {
                val arguments = mutableMapOf<String, Any>("projectDir" to projectDir.absolutePath)
                if (mode != null) arguments["mode"] = mode
                val result = LayoutDumpMcpToolAction().execute(arguments, setup.runtime)
                assertLayoutMode(result, mode ?: "interaction")
            }
        }
    }

    private fun assertLayoutMode(result: McpToolResult, mode: String) {
        Assert.assertEquals(McpToolStatus.OK, result.status)
        Assert.assertEquals("json", result.artifacts.single().type)
        val data = result.data as Map<*, *>
        val file = File(data["file"] as String)
        Assert.assertTrue(file.name.endsWith(".json"))
        Assert.assertEquals(file.length(), (data["contentBytes"] as Number).toLong())
        val json = JsonParser.parseString(file.readText()).asJsonObject
        Assert.assertEquals(json, data["layout"])
        Assert.assertTrue(file.readText().lines().any { it.startsWith("{\"className\":") })
        Assert.assertEquals(mode, json["mode"].asString)
        Assert.assertEquals("dp", json.getAsJsonObject("units")["bounds"].asString)
        Assert.assertFalse(json["truncated"].asBoolean)
        val window = json.getAsJsonArray("windows")[0].asJsonObject
        Assert.assertEquals("activity", window["windowType"].asString)
        val root = window.getAsJsonObject("root")
        Assert.assertEquals("_vid_root", root["id"].asString)
        if (mode == "interaction") {
            Assert.assertFalse(root.has("children"))
            Assert.assertTrue(result.message.contains("1 nodes"))
            return
        }
        val children = root.getAsJsonArray("children")
        Assert.assertEquals(2, children.size())
        Assert.assertEquals("ImageView", children[1].asJsonObject["className"].asString)
        Assert.assertTrue(result.message.contains("4 nodes"))
        val label = children[0].asJsonObject.getAsJsonArray("children")[0].asJsonObject
        Assert.assertEquals("_vid_label", label["id"].asString)
        Assert.assertEquals("gone", label["visibility"].asString)
        Assert.assertEquals(0.5, label["alpha"].asDouble, 0.0)
        Assert.assertFalse(label["enabled"].asBoolean)
        Assert.assertEquals("[10,20,110,40]", label["bounds"].toString())
        Assert.assertEquals("[1,2,3,4]", label["padding"].toString())
    }

    @Test
    fun testInlineLimitUsesUtf8BytesAndKeepsTheCompleteArtifact() {
        val projectDir = createTempDir(prefix = "jugg_layout_inline_")
        val setup = setup(projectDir, packageName = "com.example.app")
        var payload = ""
        Mockito.mockConstruction(ViewHierarchyClient::class.java) { mock, _ ->
            Mockito.`when`(mock.dumpLayout(anyOrNull(), any(), any())).thenAnswer { LayoutDumpResult(payload, null) }
        }.use {
            val arguments = mapOf("projectDir" to projectDir.absolutePath, "mode" to "ui")
            fun dump(text: String): McpToolResult {
                payload = Gson().toJson(mapOf("windows" to listOf(mapOf("root" to mapOf(
                    "className" to "TextView", "text" to text,
                )))))
                return LayoutDumpMcpToolAction().execute(arguments, setup.runtime)
            }
            val marker = "内联唯一文本🙂"
            val initial = dump(marker)
            val initialBytes = (initial.data as Map<*, *>)["contentBytes"] as Int
            val boundaryText = marker + "x".repeat(16 * 1024 - initialBytes)
            for (extra in listOf("", "界")) {
                val result = dump(boundaryText + extra)
                val data = result.data as Map<*, *>
                val file = File(data["file"] as String)
                Assert.assertEquals(16 * 1024L + extra.toByteArray(StandardCharsets.UTF_8).size, file.length())
                Assert.assertEquals(file.length(), (data["contentBytes"] as Number).toLong())
                val stored = JsonParser.parseString(file.readText()).asJsonObject
                val text = stored.getAsJsonArray("windows")[0].asJsonObject.getAsJsonObject("root")["text"].asString
                Assert.assertEquals(boundaryText + extra, text)
                Assert.assertEquals(extra.isEmpty(), data.containsKey("layout"))
                val response = JsonParser.parseString(Gson().toJson(McpResultMapper().toolSuccess(1, result)))
                    .asJsonObject.getAsJsonObject("result")
                val wireData = response.getAsJsonObject("structuredContent").getAsJsonObject("data")
                Assert.assertEquals(extra.isEmpty(), wireData.has("layout"))
                if (extra.isEmpty()) Assert.assertEquals(stored, wireData["layout"])
                Assert.assertFalse(response.getAsJsonArray("content").toString().contains(marker))
            }
        }
    }

    @Test
    fun testInvalidLayoutModeFailsBeforeDeviceAccess() {
        val runtime = Mockito.mock(IMcpRuntime::class.java)
        for (mode in listOf("unknown", "", 1)) {
            val result = LayoutDumpMcpToolAction().execute(mapOf("mode" to mode), runtime)
            Assert.assertEquals(McpToolStatus.ERROR, result.status)
            Assert.assertEquals(McpErrorCode.INVALID_PARAMS, result.errorCode)
        }
        Mockito.verifyNoInteractions(runtime)
    }

    @Test
    fun testIncompleteLayoutKeepsArtifactsAndReportsWarnings() {
        val projectDir = createTempDir(prefix = "jugg_layout_incomplete_")
        val setup = setup(projectDir, packageName = "com.example.app")
        Mockito.mockConstruction(ViewHierarchyClient::class.java) { mock, _ ->
            Mockito.`when`(mock.dumpLayout(anyOrNull(), any(), any())).thenReturn(
                LayoutDumpResult(
                    payloadJson = """{"warnings":["Compose tooling unavailable"],"windows":[{"root":{
                        "className":"FrameLayout","children":[
                          {"className":"ComposeView","message":"Compose tooling unavailable"},
                          {"className":"TextView","text":"Available"}]
                    }}]}""",
                    remoteFilePath = null,
                )
            )
        }.use {
            val result = LayoutDumpMcpToolAction().execute(mapOf("projectDir" to projectDir.absolutePath), setup.runtime)
            Assert.assertEquals(McpToolStatus.OK, result.status)
            Assert.assertTrue(result.message.contains("incomplete hierarchy"))
            val data = result.data as Map<*, *>
            Assert.assertEquals(listOf("Compose tooling unavailable"), data["warnings"])
            val json = File(data["file"] as String).readText()
            Assert.assertTrue(json.contains("Available"))
            Assert.assertTrue(json.contains("Compose tooling unavailable"))
            val artifact = JsonParser.parseString(json).asJsonObject
            Assert.assertEquals(artifact, data["layout"])
            Assert.assertEquals("Compose tooling unavailable", artifact.getAsJsonArray("warnings")[0].asString)
            Assert.assertEquals("px", artifact.getAsJsonObject("units")["bounds"].asString)
            Assert.assertEquals("json", result.artifacts.single().type)
        }
    }

    @Test
    fun testLayoutDumpUsesServerInlineJsonWhenAvailable() {
        val projectDir = createTempDir(prefix = "jugg_layout_dump_json_")
        val setup = setup(projectDir, packageName = "com.example.app")
        val action = LayoutDumpMcpToolAction()

        Mockito.mockConstruction(ViewHierarchyClient::class.java) { mock, _ ->
            Mockito.`when`(mock.dumpLayout(anyOrNull(), any(), any())).thenReturn(
                LayoutDumpResult(
                    payloadJson = """{"windows":[{"title":"MainActivity"}]}""",
                    remoteFilePath = null,
                )
            )
        }.use { construction ->
            val result = action.execute(mapOf("projectDir" to projectDir.absolutePath), setup.runtime)
            Assert.assertEquals(McpToolStatus.OK, result.status)
            Assert.assertTrue(result.message.contains("windows"))
            Assert.assertTrue(result.message.contains("nodes"))
            Assert.assertEquals(1, construction.constructed().size)
            Assert.assertEquals(0, setup.adb.pullCount)
            Assert.assertEquals("json", result.artifacts.firstOrNull()?.type)
            @Suppress("UNCHECKED_CAST")
            val data = result.data as Map<String, Any>
            val filePath = data["file"] as String
            Assert.assertTrue(filePath.endsWith(".json"))
            val content = File(filePath).readText(StandardCharsets.UTF_8)
            Assert.assertTrue(content.contains("MainActivity"))
        }
    }

    @Test
    fun testLayoutDumpUsesServerFileModeWhenAvailable() {
        val projectDir = createTempDir(prefix = "jugg_layout_dump_file_mode_")
        val setup = setup(projectDir, packageName = "com.example.app")
        val action = LayoutDumpMcpToolAction()
        val remotePath = "/data/local/tmp/jugg_vh/layout_remote.json"

        Mockito.mockConstruction(ViewHierarchyClient::class.java) { mock, _ ->
            Mockito.`when`(mock.dumpLayout(anyOrNull(), any(), any())).thenReturn(
                LayoutDumpResult(
                    payloadJson = null,
                    remoteFilePath = remotePath,
                )
            )
        }.use {
            val result = action.execute(mapOf("projectDir" to projectDir.absolutePath), setup.runtime)
            Assert.assertEquals(McpToolStatus.OK, result.status)
            Assert.assertEquals(1, setup.adb.pullCount)
            Assert.assertTrue(setup.adb.pullFromPaths.contains(remotePath))
            Assert.assertEquals("json", result.artifacts.firstOrNull()?.type)
            @Suppress("UNCHECKED_CAST")
            val data = result.data as Map<String, Any>
            val filePath = data["file"] as String
            Assert.assertTrue(filePath.endsWith(".json"))
            val content = File(filePath).readText(StandardCharsets.UTF_8)
            Assert.assertEquals("interaction", JsonParser.parseString(content).asJsonObject["mode"].asString)
        }
    }

    @Test
    fun testLayoutDumpReturnsErrorWhenServerUnavailable() {
        val projectDir = createTempDir(prefix = "jugg_layout_dump_unavailable_")
        val setup = setup(projectDir, packageName = "com.example.app")
        val action = LayoutDumpMcpToolAction()

        Mockito.mockConstruction(ViewHierarchyClient::class.java) { mock, _ ->
            Mockito.`when`(mock.dumpLayout(anyOrNull(), any(), any())).thenReturn(null)
        }.use { construction ->
            val result = action.execute(mapOf("projectDir" to projectDir.absolutePath), setup.runtime)
            Assert.assertEquals(McpToolStatus.ERROR, result.status)
            Assert.assertEquals(4, construction.constructed().size)
            Assert.assertTrue(result.message.contains("ViewHierarchy server is unavailable"))
            Assert.assertTrue(result.artifacts.isEmpty())
            Assert.assertEquals(0, setup.adb.pullCount)
        }
    }

    @Test
    fun testLayoutDumpReturnsScreenOffErrorWhenServerUnavailableAndDeviceSleeping() {
        val projectDir = createTempDir(prefix = "jugg_layout_dump_screen_off_")
        val setup = setup(
            projectDir,
            packageName = "com.example.app",
            shellOutputs = mapOf(
                "dumpsys power" to "mWakefulness=Dozing",
                "dumpsys window policy" to "screenState=SCREEN_STATE_OFF\ninteractiveState=INTERACTIVE_STATE_SLEEP",
            ),
        )
        val action = LayoutDumpMcpToolAction()

        Mockito.mockConstruction(ViewHierarchyClient::class.java) { mock, _ ->
            Mockito.`when`(mock.dumpLayout(anyOrNull(), any(), any())).thenReturn(null)
        }.use { construction ->
            val result = action.execute(mapOf("projectDir" to projectDir.absolutePath), setup.runtime)
            Assert.assertEquals(McpToolStatus.ERROR, result.status)
            Assert.assertEquals(McpErrorCode.DEVICE_NOT_INTERACTIVE, result.errorCode)
            Assert.assertEquals(1, construction.constructed().size)
            Assert.assertTrue(result.message.contains("device screen is off"))
            Assert.assertTrue(setup.adb.executedCommands.contains("dumpsys power"))
            Assert.assertTrue(setup.adb.executedCommands.contains("dumpsys window policy"))
        }
    }

    @Test
    fun testLayoutDumpReturnsAppBackgroundErrorWhenServerUnavailableAndAppNotForeground() {
        val projectDir = createTempDir(prefix = "jugg_layout_dump_background_")
        val setup = setup(
            projectDir,
            packageName = "com.example.app",
            shellOutputs = mapOf(
                "dumpsys power" to "mWakefulness=Awake",
                "dumpsys window policy" to "screenState=SCREEN_STATE_ON\ninteractiveState=INTERACTIVE_STATE_AWAKE",
                "dumpsys activity activities" to
                    "topResumedActivity=ActivityRecord{100 com.android.launcher/.Launcher t1}",
            ),
        )
        val action = LayoutDumpMcpToolAction()

        Mockito.mockConstruction(ViewHierarchyClient::class.java) { mock, _ ->
            Mockito.`when`(mock.dumpLayout(anyOrNull(), any(), any())).thenReturn(null)
        }.use { construction ->
            val result = action.execute(mapOf("projectDir" to projectDir.absolutePath), setup.runtime)
            Assert.assertEquals(McpToolStatus.ERROR, result.status)
            Assert.assertEquals(McpErrorCode.APP_NOT_FOREGROUND, result.errorCode)
            Assert.assertEquals(1, construction.constructed().size)
            Assert.assertTrue(result.message.contains("target app is not in foreground"))
            Assert.assertTrue(setup.adb.executedCommands.contains("dumpsys activity activities"))
        }
    }

    @Test
    fun testLayoutDumpReturnsErrorWhenPackageNameMissing() {
        val projectDir = createTempDir(prefix = "jugg_layout_dump_no_pkg_")
        val setup = setup(projectDir, packageName = null)
        val action = LayoutDumpMcpToolAction()

        val result = action.execute(mapOf("projectDir" to projectDir.absolutePath), setup.runtime)
        Assert.assertEquals(McpToolStatus.ERROR, result.status)
        Assert.assertTrue(result.message.contains("failed to resolve package name"))
    }

    @Test
    fun testLayoutDumpNoDeviceReturnsNoDeviceError() {
        val projectDir = createTempDir(prefix = "jugg_layout_dump_no_device_")
        PlatformApi.impl = FakePlatformApi()
        val deployTargetManager = Mockito.mock(IDeployTargetManager::class.java)
        Mockito.`when`(deployTargetManager.getSelectedDevices()).thenReturn(emptyList())
        Mockito.`when`(deployTargetManager.getConnectedDevices()).thenReturn(emptyList())
        val action = LayoutDumpMcpToolAction()

        val result = action.execute(
            mapOf("projectDir" to projectDir.absolutePath),
            buildRuntime(projectDir, deployTargetManager)
        )
        Assert.assertEquals(McpToolStatus.ERROR, result.status)
        Assert.assertEquals(McpErrorCode.NO_DEVICE, result.errorCode)
    }

    @Test
    fun testLayoutDumpReturnsErrorWhenAppNotReadyAfterRetries() {
        McpAppReadyGuard.preTimeoutOverrideForTest = 8L
        McpAppReadyGuard.prePollIntervalOverrideForTest = 1L
        val projectDir = createTempDir(prefix = "jugg_layout_dump_not_ready_")
        var checks = 0
        val setup = setup(
            projectDir = projectDir,
            packageName = "com.example.app",
            isAppReadyProvider = {
                checks += 1
                false
            },
        )
        val action = LayoutDumpMcpToolAction()

        Mockito.mockConstruction(ViewHierarchyClient::class.java).use { construction ->
            val result = action.execute(mapOf("projectDir" to projectDir.absolutePath), setup.runtime)
            Assert.assertEquals(McpToolStatus.ERROR, result.status)
            Assert.assertTrue(result.message.contains("app is not ready"))
            Assert.assertTrue(checks >= 2)
            Assert.assertEquals(0, construction.constructed().size)
        }
    }

    @Test
    fun testLayoutDumpReturnsFilePathAndContentBytes() {
        val projectDir = createTempDir(prefix = "jugg_layout_dump_inline_content_")
        val setup = setup(projectDir, packageName = "com.example.app")
        val action = LayoutDumpMcpToolAction()
        val inlineJson = """{"windows":[{"title":"InlineTest"}],"truncated":false}"""

        Mockito.mockConstruction(ViewHierarchyClient::class.java) { mock, _ ->
            Mockito.`when`(mock.dumpLayout(anyOrNull(), any(), any())).thenReturn(
                LayoutDumpResult(
                    payloadJson = inlineJson,
                    remoteFilePath = null,
                )
            )
        }.use {
            val result = action.execute(mapOf("projectDir" to projectDir.absolutePath), setup.runtime)
            Assert.assertEquals(McpToolStatus.OK, result.status)
            @Suppress("UNCHECKED_CAST")
            val data = result.data as Map<String, Any>
            Assert.assertFalse("data.content should not be present", data.containsKey("content"))
            Assert.assertFalse("inlineOmitted should not be present", data.containsKey("inlineOmitted"))
            Assert.assertFalse("inlineThresholdKb should not be present", data.containsKey("inlineThresholdKb"))
            Assert.assertFalse("data.jsonFile should stay internal", data.containsKey("jsonFile"))
            Assert.assertTrue((data["contentBytes"] as Number).toInt() > 0)
            Assert.assertTrue((data["file"] as String).endsWith(".json"))
        }
    }

    @Test
    fun testLayoutDumpPassesRootLayoutToClient() {
        val projectDir = createTempDir(prefix = "jugg_layout_dump_root_layout_")
        val setup = setup(projectDir, packageName = "com.example.app")
        val action = LayoutDumpMcpToolAction()
        val rootLayoutId = "content"

        Mockito.mockConstruction(ViewHierarchyClient::class.java) { mock, _ ->
            Mockito.`when`(mock.dumpLayout(anyOrNull(), any(), any())).thenReturn(
                LayoutDumpResult(
                    payloadJson = """{"windows":[],"truncated":false,"rootLayout":"content"}""",
                    remoteFilePath = null,
                )
            )
        }.use { construction ->
            val result = action.execute(
                mapOf("projectDir" to projectDir.absolutePath, "rootLayout" to rootLayoutId),
                setup.runtime,
            )
            Assert.assertEquals(McpToolStatus.OK, result.status)
            val client = construction.constructed().first()
            Mockito.verify(client).dumpLayout(rootLayoutId, true, true)
        }
    }

    @Test
    fun testLayoutDumpNeverInlinesContentForLargePayload() {
        val projectDir = createTempDir(prefix = "jugg_layout_dump_large_payload_")
        val setup = setup(projectDir, packageName = "com.example.app")
        val action = LayoutDumpMcpToolAction()
        val largeText = "x".repeat(20_000)
        val largeJson = """{"windows":[{"title":"MainActivity","root":{"className":"Root","text":"$largeText","bounds":[0,0,10,10]}}],"truncated":false}"""

        Mockito.mockConstruction(ViewHierarchyClient::class.java) { mock, _ ->
            Mockito.`when`(mock.dumpLayout(anyOrNull(), any(), any())).thenReturn(
                LayoutDumpResult(
                    payloadJson = largeJson,
                    remoteFilePath = null,
                )
            )
        }.use {
            val result = action.execute(mapOf("projectDir" to projectDir.absolutePath), setup.runtime)
            Assert.assertEquals(McpToolStatus.OK, result.status)
            @Suppress("UNCHECKED_CAST")
            val data = result.data as Map<String, Any>
            Assert.assertFalse("data.content should not be present", data.containsKey("content"))
            Assert.assertFalse("inlineOmitted should not be present", data.containsKey("inlineOmitted"))
            Assert.assertTrue((data["contentBytes"] as Number).toInt() > 16 * 1024)
        }
    }

    private fun setup(
        projectDir: File,
        packageName: String? = null,
        isAppReadyProvider: () -> Boolean = { true },
        shellOutputs: Map<String, String> = emptyMap(),
    ): SetupResult {
        val device = Mockito.mock(IDevice::class.java)
        val adb = FakeDeviceAdb(shellOutputs)
        PlatformApi.impl = FakePlatformApi()

        val deployTargetManager = Mockito.mock(IDeployTargetManager::class.java)
        Mockito.`when`(deployTargetManager.getSelectedDevices()).thenReturn(listOf(device))
        Mockito.`when`(deployTargetManager.getConnectedDevices()).thenReturn(listOf(device))
        Mockito.`when`(deployTargetManager.createDeviceAdb(device)).thenReturn(adb)
        if (packageName != null) {
            Mockito.`when`(deployTargetManager.getPackageName()).thenReturn(packageName)
        }
        return SetupResult(
            runtime = buildRuntime(projectDir, deployTargetManager, isAppReadyProvider),
            adb = adb,
        )
    }

    private fun buildRuntime(
        projectDir: File,
        deployTargetManager: IDeployTargetManager,
        isAppReadyProvider: () -> Boolean = { true },
    ): IMcpRuntime {
        return object : com.sickworm.intellij.jugg.ai.mcp.TestMcpRuntime() {
            override val logger: com.intellij.openapi.diagnostic.Logger
                get() = com.intellij.openapi.diagnostic.Logger.getInstance("TestMcpRuntime")
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
        private val shellOutputs: Map<String, String> = emptyMap(),
    ) : IDeviceAdb {
        override val displayName: String? = "fake_device"
        override val api: Int = 34
        override val serial: String = "emulator-5554"
        override val isOnline: Boolean = true

        val executedCommands: MutableList<String> = mutableListOf()
        val pullFromPaths: MutableList<String> = mutableListOf()
        var pullCount: Int = 0
            private set

        override fun execAdbShellCmd(cmd: String): String {
            executedCommands.add(cmd)
            return shellOutputs[cmd].orEmpty()
        }

        override fun push(from: File, to: String): Boolean = true

        override fun pull(from: String, to: File): Boolean {
            pullCount += 1
            pullFromPaths.add(from)
            to.parentFile?.mkdirs()
            if (from.endsWith(".json")) {
                to.writeText("""{"windows":[{"source":"remote"}]}""", StandardCharsets.UTF_8)
            }
            return true
        }

        override fun getDefaultLaunchActivity(apkFile: File): String? = null
        override fun getArch(packageName: String): String = "ARCH_64_BIT"
        override fun getProperty(name: String): String? = null
    }

    @Test
    fun testLayoutDumpSummaryContainsClickableCount() {
        val projectDir = createTempDir(prefix = "jugg_layout_dump_clickable_")
        val setup = setup(projectDir, packageName = "com.example.app")
        val action = LayoutDumpMcpToolAction()
        val jsonWithClickable = """{"windows":[{"title":"MainActivity","root":{"className":"FrameLayout","bounds":[0,0,1080,1920],"children":[{"className":"Button","bounds":[0,0,300,100],"clickable":true},{"className":"TextView","bounds":[0,200,300,300]}]}}],"truncated":false}"""

        Mockito.mockConstruction(ViewHierarchyClient::class.java) { mock, _ ->
            Mockito.`when`(mock.dumpLayout(anyOrNull(), any(), any())).thenReturn(
                LayoutDumpResult(payloadJson = jsonWithClickable, remoteFilePath = null)
            )
        }.use {
            val result = action.execute(mapOf("projectDir" to projectDir.absolutePath), setup.runtime)
            Assert.assertEquals(McpToolStatus.OK, result.status)
            Assert.assertTrue(
                "summary should contain 'clickable' count: ${result.message}",
                result.message.contains("clickable")
            )
            Assert.assertTrue(
                "summary should count 1 clickable: ${result.message}",
                result.message.contains("1 clickable")
            )
        }
    }

    @Test
    fun testLayoutDumpConvertsPxToDp() {
        val projectDir = createTempDir(prefix = "jugg_layout_dump_dp_")
        val setup = setup(projectDir, packageName = "com.example.app")
        val action = LayoutDumpMcpToolAction()
        // density=3.0, bounds=[0,0,300,600] in px should become [0,0,100,200] in dp;
        // Button is clickable so it appears in the JSON output with the converted bounds.
        val jsonWithPx = """{"windows":[{"title":"MainActivity","root":{"className":"Button","bounds":[0,0,300,600],"clickable":true}}],"truncated":false,"deviceInfo":{"density":3.0,"scaledDensity":3.0}}"""

        Mockito.mockConstruction(ViewHierarchyClient::class.java) { mock, _ ->
            Mockito.`when`(mock.dumpLayout(anyOrNull(), any(), any())).thenReturn(
                LayoutDumpResult(payloadJson = jsonWithPx, remoteFilePath = null)
            )
        }.use {
            val result = action.execute(mapOf("projectDir" to projectDir.absolutePath), setup.runtime)
            Assert.assertEquals(McpToolStatus.OK, result.status)
            @Suppress("UNCHECKED_CAST")
            val data = result.data as Map<String, Any>
            val filePath = data["file"] as String
            Assert.assertTrue("artifact should be JSON", filePath.endsWith(".json"))
            val content = File(filePath).readText(StandardCharsets.UTF_8)
            // Converted bounds [0,0,100,200] should appear in JSON output
            Assert.assertTrue("JSON should contain converted bounds", content.contains("0,0,100,200"))
        }
    }

    @Test
    fun testLayoutDumpKeepsVirtualIdForWindowRoot() {
        val projectDir = createTempDir(prefix = "jugg_layout_dump_virtual_id_")
        val setup = setup(projectDir, packageName = "com.example.app")
        val action = LayoutDumpMcpToolAction()
        // Virtual ID wrapper around a real Button child
        val jsonWithVirtualIds = """{"windows":[{"title":"MainActivity","root":{"className":"FrameLayout","id":"_vir_id_0","bounds":[0,0,360,640],"children":[{"className":"Button","id":"btn_action","bounds":[0,0,100,50],"text":"OK","clickable":true}]}}],"truncated":false,"deviceInfo":{"density":3.0,"scaledDensity":3.0}}"""

        Mockito.mockConstruction(ViewHierarchyClient::class.java) { mock, _ ->
            Mockito.`when`(mock.dumpLayout(anyOrNull(), any(), any())).thenReturn(
                LayoutDumpResult(payloadJson = jsonWithVirtualIds, remoteFilePath = null)
            )
        }.use {
            val result = action.execute(mapOf("projectDir" to projectDir.absolutePath), setup.runtime)
            Assert.assertEquals(McpToolStatus.OK, result.status)
            @Suppress("UNCHECKED_CAST")
            val data = result.data as Map<String, Any>
            val filePath = data["file"] as String
            val content = File(filePath).readText(StandardCharsets.UTF_8)
            Assert.assertTrue("Window root keeps its virtual ID", content.contains("_vid_0"))
            Assert.assertTrue("Real child btn_action should appear in JSON", content.contains("btn_action"))
        }
    }

    @Test
    fun testLayoutDumpDpConversionWithNestedChildren() {
        val projectDir = createTempDir(prefix = "jugg_layout_dump_nested_dp_")
        val setup = setup(projectDir, packageName = "com.example.app")
        val action = LayoutDumpMcpToolAction()
        // density=2.0; TextView has bounds [20,30,180,70] -> [10,15,90,35] in dp.
        // FrameLayout has real id so it won't be pruned.
        val jsonWithNested = """{"windows":[{"title":"MainActivity","root":{"className":"FrameLayout","id":"root_frame","bounds":[0,0,200,400],"children":[{"className":"LinearLayout","id":"inner_ll","bounds":[10,20,190,380],"children":[{"className":"TextView","bounds":[20,30,180,70],"text":"Hello"}]}]}}],"truncated":false,"deviceInfo":{"density":2.0,"scaledDensity":2.0}}"""

        Mockito.mockConstruction(ViewHierarchyClient::class.java) { mock, _ ->
            Mockito.`when`(mock.dumpLayout(anyOrNull(), any(), any())).thenReturn(
                LayoutDumpResult(payloadJson = jsonWithNested, remoteFilePath = null)
            )
        }.use {
            val result = action.execute(mapOf("projectDir" to projectDir.absolutePath), setup.runtime)
            Assert.assertEquals(McpToolStatus.OK, result.status)
            @Suppress("UNCHECKED_CAST")
            val data = result.data as Map<String, Any>
            val filePath = data["file"] as String
            val content = File(filePath).readText(StandardCharsets.UTF_8)
            // Converted root bounds [0,0,100,200] should appear
            Assert.assertTrue("converted root bounds should appear", content.contains("0,0,100,200"))
            // Converted TextView bounds [10,15,90,35] should appear
            Assert.assertTrue("converted TextView bounds should appear", content.contains("10,15,90,35"))
        }
    }

    private class FakePlatformApi : IPlatformApi {
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
