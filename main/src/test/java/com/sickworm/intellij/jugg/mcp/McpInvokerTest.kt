package com.sickworm.intellij.jugg.mcp

import org.junit.After
import org.junit.Assert
import org.junit.Before
import org.junit.Test

class McpInvokerTest {

    private fun initialize(invoker: McpInvoker) {
        val initResponse = invoker.invokeMcp(
            McpJsonRpcRequest(
                id = 100,
                method = McpJsonRpc.Method.Initialize,
                params = McpInitializeParams(
                    protocolVersion = McpJsonRpc.ProtocolVersion,
                    capabilities = emptyMap(),
                    clientInfo = McpPeerInfo("test-client", "1.0.0")
                )
            )
        )
        Assert.assertNull(initResponse.error)
    }

    @Before
    fun setUp() {
        McpRuntimeHolder.runtime = object : IMcpRuntime {
            override fun restartApp(serial: String?): McpToolResult {
                return when (serial) {
                    null -> McpToolResult(
                        status = McpToolStatus.OK,
                        message = "restart_app executed successfully. Serial not provided; selected device 'emulator-5554' is used.",
                        data = mapOf("device" to McpDeviceInfo(serial = "emulator-5554", name = "Pixel", isOnline = true)),
                        artifacts = emptyList(),
                        errorCode = null,
                    )

                    "invalid" -> McpToolResult(
                        status = McpToolStatus.OK,
                        message = "restart_app executed successfully. Serial 'invalid' is invalid; fallback to selected device 'emulator-5554'.",
                        data = mapOf("device" to McpDeviceInfo(serial = "emulator-5554", name = "Pixel", isOnline = true)),
                        artifacts = emptyList(),
                        errorCode = null,
                    )

                    "none" -> McpToolResult(
                        status = McpToolStatus.ERROR,
                        message = "restart_app failed. Reason: No connected device is available.",
                        data = emptyMap<String, Any>(),
                        artifacts = emptyList(),
                        errorCode = McpErrorCode.MCP_NO_DEVICE,
                    )

                    else -> McpToolResult(
                        status = McpToolStatus.OK,
                        message = "restart_app executed successfully. Device selected by serial: $serial.",
                        data = mapOf("device" to McpDeviceInfo(serial = serial, name = "Specified", isOnline = true)),
                        artifacts = emptyList(),
                        errorCode = null,
                    )
                }
            }

            override fun compile(): McpToolResult {
                return McpToolResult(
                    status = McpToolStatus.OK,
                    message = "compile executed successfully.",
                    data = mapOf("isCompileSuccess" to true),
                    artifacts = emptyList(),
                    errorCode = null,
                )
            }

            override fun deploy(): McpToolResult {
                return McpToolResult(
                    status = McpToolStatus.OK,
                    message = "deploy executed successfully.",
                    data = mapOf("isDeploySuccess" to true),
                    artifacts = emptyList(),
                    errorCode = null,
                )
            }

            override fun cleanReinstall(): McpToolResult {
                return McpToolResult(
                    status = McpToolStatus.OK,
                    message = "clean_reinstall executed successfully.",
                    data = mapOf("cleanAndReinstall" to true),
                    artifacts = emptyList(),
                    errorCode = null,
                )
            }

            override fun deviceList(): McpToolResult {
                return McpToolResult(
                    status = McpToolStatus.OK,
                    message = "device_list executed successfully.",
                    data = mapOf("devices" to emptyList<Map<String, Any?>>()),
                    artifacts = emptyList(),
                    errorCode = null,
                )
            }

            override fun screenshot(serial: String?): McpToolResult {
                return McpToolResult(
                    status = McpToolStatus.OK,
                    message = "screenshot executed successfully.",
                    data = mapOf("serial" to serial),
                    artifacts = listOf(McpArtifact(type = "image", path = "/tmp/a.png")),
                    errorCode = null,
                )
            }

            override fun record(serial: String?, durationSec: Int?): McpToolResult {
                return McpToolResult(
                    status = McpToolStatus.OK,
                    message = "record executed successfully.",
                    data = mapOf("serial" to serial, "durationSec" to durationSec),
                    artifacts = listOf(McpArtifact(type = "video", path = "/tmp/a.mp4")),
                    errorCode = null,
                )
            }

            override fun layoutDump(serial: String?): McpToolResult {
                return McpToolResult(
                    status = McpToolStatus.OK,
                    message = "layout_dump executed successfully.",
                    data = mapOf("serial" to serial),
                    artifacts = listOf(McpArtifact(type = "xml", path = "/tmp/a.xml")),
                    errorCode = null,
                )
            }
        }
    }

    @After
    fun tearDown() {
        McpRuntimeHolder.runtime = null
    }

    @Test
    fun testListProjectsAcceptedWithoutProjectDir() {
        val invoker = McpInvoker(currentProjectDir = "/tmp/projectA")
        initialize(invoker)
        val response = invoker.invokeMcp(
            McpJsonRpcRequest(
                method = McpJsonRpc.Method.ToolsCall,
                id = 1,
                params = mapOf(
                    "name" to "list_projects",
                    "arguments" to emptyMap<String, Any?>(),
                )
            )
        )

        Assert.assertNull(response.error)
        val result = response.result as McpToolCallResult
        Assert.assertFalse(result.isError)
        Assert.assertEquals(McpToolStatus.OK, result.structuredContent["status"])
    }

    @Test
    fun testRestartAppFallbackWhenSerialMissing() {
        val invoker = McpInvoker(currentProjectDir = "/tmp/projectA")
        initialize(invoker)
        val response = invoker.invokeMcp(
            McpJsonRpcRequest(
                method = McpJsonRpc.Method.ToolsCall,
                id = 2,
                params = mapOf(
                    "name" to "restart_app",
                    "arguments" to mapOf("projectDir" to "/tmp/projectA")
                )
            )
        )

        val result = response.result as McpToolCallResult
        Assert.assertFalse(result.isError)
        Assert.assertTrue(result.content.first().text.contains("Serial not provided"))
        Assert.assertTrue(result.content.first().text.contains("structuredContent={"))
    }

    @Test
    fun testRestartAppFallbackWhenSerialInvalid() {
        val invoker = McpInvoker(currentProjectDir = "/tmp/projectA")
        initialize(invoker)
        val response = invoker.invokeMcp(
            McpJsonRpcRequest(
                method = McpJsonRpc.Method.ToolsCall,
                id = 3,
                params = mapOf(
                    "name" to "restart_app",
                    "arguments" to mapOf("projectDir" to "/tmp/projectA", "serial" to "invalid")
                )
            )
        )

        val result = response.result as McpToolCallResult
        Assert.assertFalse(result.isError)
        Assert.assertTrue(result.content.first().text.contains("fallback to selected device"))
    }

    @Test
    fun testRestartAppNoDevice() {
        val invoker = McpInvoker(currentProjectDir = "/tmp/projectA")
        initialize(invoker)
        val response = invoker.invokeMcp(
            McpJsonRpcRequest(
                method = McpJsonRpc.Method.ToolsCall,
                id = 4,
                params = mapOf(
                    "name" to "restart_app",
                    "arguments" to mapOf("projectDir" to "/tmp/projectA", "serial" to "none")
                )
            )
        )

        val result = response.result as McpToolCallResult
        Assert.assertTrue(result.isError)
        Assert.assertEquals(McpErrorCode.MCP_NO_DEVICE, result.structuredContent["errorCode"])
        Assert.assertTrue(result.content.first().text.contains("\"errorCode\":\"MCP_NO_DEVICE\""))
    }

    @Test
    fun testToolsListWithoutInitializeIsAllowedInInvoker() {
        val invoker = McpInvoker(currentProjectDir = "/tmp/projectA")
        val response = invoker.invokeMcp(
            McpJsonRpcRequest(
                method = McpJsonRpc.Method.ToolsList,
                id = 999,
                params = emptyMap<String, Any>()
            )
        )

        Assert.assertNull(response.error)
    }

    @Test
    fun testPromptsAndResourcesList() {
        val invoker = McpInvoker(currentProjectDir = "/tmp/projectA")
        initialize(invoker)

        val prompts = invoker.invokeMcp(
            McpJsonRpcRequest(id = 200, method = McpJsonRpc.Method.PromptsList, params = emptyMap<String, Any>())
        )
        Assert.assertNull(prompts.error)

        val resources = invoker.invokeMcp(
            McpJsonRpcRequest(id = 201, method = McpJsonRpc.Method.ResourcesList, params = emptyMap<String, Any>())
        )
        Assert.assertNull(resources.error)
    }

    @Test
    fun testCompileToolCallSuccess() {
        val invoker = McpInvoker(currentProjectDir = "/tmp/projectA")
        initialize(invoker)
        val response = invoker.invokeMcp(
            McpJsonRpcRequest(
                method = McpJsonRpc.Method.ToolsCall,
                id = 5,
                params = mapOf(
                    "name" to "compile",
                    "arguments" to mapOf("projectDir" to "/tmp/projectA")
                )
            )
        )

        val result = response.result as McpToolCallResult
        Assert.assertFalse(result.isError)
        Assert.assertEquals(McpToolStatus.OK, result.structuredContent["status"])
        Assert.assertTrue(result.content.first().text.contains("compile executed successfully"))
    }

    @Test
    fun testDeployToolCallSuccess() {
        val invoker = McpInvoker(currentProjectDir = "/tmp/projectA")
        initialize(invoker)
        val response = invoker.invokeMcp(
            McpJsonRpcRequest(
                method = McpJsonRpc.Method.ToolsCall,
                id = 6,
                params = mapOf(
                    "name" to "deploy",
                    "arguments" to mapOf("projectDir" to "/tmp/projectA")
                )
            )
        )

        val result = response.result as McpToolCallResult
        Assert.assertFalse(result.isError)
        Assert.assertEquals(McpToolStatus.OK, result.structuredContent["status"])
        Assert.assertTrue(result.content.first().text.contains("deploy executed successfully"))
    }

    @Test
    fun testCleanReinstallToolCallSuccess() {
        val invoker = McpInvoker(currentProjectDir = "/tmp/projectA")
        initialize(invoker)
        val response = invoker.invokeMcp(
            McpJsonRpcRequest(
                method = McpJsonRpc.Method.ToolsCall,
                id = 7,
                params = mapOf(
                    "name" to "clean_reinstall",
                    "arguments" to mapOf("projectDir" to "/tmp/projectA")
                )
            )
        )

        val result = response.result as McpToolCallResult
        Assert.assertFalse(result.isError)
        Assert.assertEquals(McpToolStatus.OK, result.structuredContent["status"])
        Assert.assertTrue(result.content.first().text.contains("clean_reinstall executed successfully"))
    }

    @Test
    fun testDeviceListToolCallSuccess() {
        val invoker = McpInvoker(currentProjectDir = "/tmp/projectA")
        initialize(invoker)
        val response = invoker.invokeMcp(
            McpJsonRpcRequest(
                method = McpJsonRpc.Method.ToolsCall,
                id = 8,
                params = mapOf(
                    "name" to "device_list",
                    "arguments" to mapOf("projectDir" to "/tmp/projectA")
                )
            )
        )

        val result = response.result as McpToolCallResult
        Assert.assertFalse(result.isError)
        Assert.assertEquals(McpToolStatus.OK, result.structuredContent["status"])
    }

    @Test
    fun testScreenshotToolCallSuccess() {
        val invoker = McpInvoker(currentProjectDir = "/tmp/projectA")
        initialize(invoker)
        val response = invoker.invokeMcp(
            McpJsonRpcRequest(
                method = McpJsonRpc.Method.ToolsCall,
                id = 9,
                params = mapOf(
                    "name" to "screenshot",
                    "arguments" to mapOf("projectDir" to "/tmp/projectA", "serial" to "emulator-5554")
                )
            )
        )

        val result = response.result as McpToolCallResult
        Assert.assertFalse(result.isError)
        Assert.assertEquals(McpToolStatus.OK, result.structuredContent["status"])
    }

    @Test
    fun testRecordToolCallSuccess() {
        val invoker = McpInvoker(currentProjectDir = "/tmp/projectA")
        initialize(invoker)
        val response = invoker.invokeMcp(
            McpJsonRpcRequest(
                method = McpJsonRpc.Method.ToolsCall,
                id = 10,
                params = mapOf(
                    "name" to "record",
                    "arguments" to mapOf("projectDir" to "/tmp/projectA", "durationSec" to 12)
                )
            )
        )

        val result = response.result as McpToolCallResult
        Assert.assertFalse(result.isError)
        Assert.assertEquals(McpToolStatus.OK, result.structuredContent["status"])
    }

    @Test
    fun testLayoutDumpToolCallSuccess() {
        val invoker = McpInvoker(currentProjectDir = "/tmp/projectA")
        initialize(invoker)
        val response = invoker.invokeMcp(
            McpJsonRpcRequest(
                method = McpJsonRpc.Method.ToolsCall,
                id = 11,
                params = mapOf(
                    "name" to "layout_dump",
                    "arguments" to mapOf("projectDir" to "/tmp/projectA")
                )
            )
        )

        val result = response.result as McpToolCallResult
        Assert.assertFalse(result.isError)
        Assert.assertEquals(McpToolStatus.OK, result.structuredContent["status"])
    }
}
