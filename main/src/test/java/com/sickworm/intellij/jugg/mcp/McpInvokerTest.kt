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
}
