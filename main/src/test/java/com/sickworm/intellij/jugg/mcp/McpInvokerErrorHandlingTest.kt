package com.sickworm.intellij.jugg.mcp

import org.junit.Assert
import org.junit.Test

class McpInvokerErrorHandlingTest : McpInvokerTestBase() {

    @Test
    fun testToolsCallParamsRequired() {
        val invoker = newToolInvoker()
        val response = invoker.invokeMcp(
            McpJsonRpcRequest(
                method = McpJsonRpc.Method.ToolsCall,
                id = 1,
                params = null,
            )
        )

        val result = response.result as McpToolCallResult
        Assert.assertTrue(result.isError)
        Assert.assertEquals(McpErrorCode.INVALID_PARAMS, result.structuredContent["errorCode"])
        Assert.assertTrue(result.content.first().text.contains("tools/call params is required"))
    }

    @Test
    fun testToolNameRequired() {
        val invoker = newToolInvoker()
        val response = invoker.invokeMcp(
            McpJsonRpcRequest(
                method = McpJsonRpc.Method.ToolsCall,
                id = 2,
                params = mapOf(
                    "arguments" to mapOf("projectDir" to "/tmp/projectA")
                ),
            )
        )

        val result = response.result as McpToolCallResult
        Assert.assertTrue(result.isError)
        Assert.assertEquals(McpErrorCode.INVALID_PARAMS, result.structuredContent["errorCode"])
        Assert.assertTrue(result.content.first().text.contains("Tool name is required"))
    }

    @Test
    fun testToolNotFound() {
        val invoker = newToolInvoker()
        val response = invoker.invokeMcp(
            McpJsonRpcRequest(
                method = McpJsonRpc.Method.ToolsCall,
                id = 3,
                params = mapOf(
                    "name" to "unknown_tool",
                    "arguments" to mapOf("projectDir" to "/tmp/projectA")
                ),
            )
        )

        val result = response.result as McpToolCallResult
        Assert.assertTrue(result.isError)
        Assert.assertEquals(McpErrorCode.TOOL_NOT_FOUND, result.structuredContent["errorCode"])
        Assert.assertTrue(result.content.first().text.contains("Tool not found"))
    }

    @Test
    fun testVersionSucceedsWithoutProjectDir() {
        val invoker = newToolInvoker()
        val response = invoker.invokeMcp(
            McpJsonRpcRequest(
                method = McpJsonRpc.Method.ToolsCall,
                id = 13,
                params = mapOf(
                    "name" to "version",
                    "arguments" to emptyMap<String, Any?>(),
                ),
            )
        )

        val result = response.result as McpToolCallResult
        Assert.assertFalse(result.isError)
        Assert.assertEquals(McpToolStatus.OK, result.structuredContent["status"])
        Assert.assertTrue(result.content.first().text.contains("version executed successfully"))
    }

    @Test
    fun testProjectDirRequiredForNormalTools() {
        val invoker = newToolInvoker()
        val response = invoker.invokeMcp(
            McpJsonRpcRequest(
                method = McpJsonRpc.Method.ToolsCall,
                id = 4,
                params = mapOf(
                    "name" to "compile",
                    "arguments" to emptyMap<String, Any?>(),
                ),
            )
        )

        val result = response.result as McpToolCallResult
        Assert.assertTrue(result.isError)
        Assert.assertEquals(McpErrorCode.INVALID_PARAMS, result.structuredContent["errorCode"])
        Assert.assertTrue(result.content.first().text.contains("projectDir is required"))
    }

    @Test
    fun testProjectNotInitialized() {
        val invoker = newToolInvoker()
        val response = invoker.invokeMcp(
            McpJsonRpcRequest(
                method = McpJsonRpc.Method.ToolsCall,
                id = 5,
                params = mapOf(
                    "name" to "compile",
                    "arguments" to mapOf("projectDir" to "/tmp/projectB")
                ),
            )
        )

        val result = response.result as McpToolCallResult
        Assert.assertTrue(result.isError)
        Assert.assertEquals(McpErrorCode.PROJECT_NOT_INITIALIZED, result.structuredContent["errorCode"])
        Assert.assertTrue(result.content.first().text.contains("project is not initialized"))
    }

    @Test
    fun testRestartAppSuccessWithDefaultSelection() {
        val invoker = newToolInvoker()
        val response = invoker.invokeMcp(
            McpJsonRpcRequest(
                method = McpJsonRpc.Method.ToolsCall,
                id = 7,
                params = mapOf(
                    "name" to "restart",
                    "arguments" to mapOf("projectDir" to "/tmp/projectA")
                )
            )
        )

        val result = response.result as McpToolCallResult
        Assert.assertFalse(result.isError)
        Assert.assertTrue(result.content.first().text.contains("restart executed successfully"))
    }

    @Test
    fun testRestartAppNoDevice() {
        val invoker = newToolInvoker(currentProjectDir = "/tmp/projectNoDevice")
        val response = invoker.invokeMcp(
            McpJsonRpcRequest(
                method = McpJsonRpc.Method.ToolsCall,
                id = 8,
                params = mapOf(
                    "name" to "restart",
                    "arguments" to mapOf("projectDir" to "/tmp/projectNoDevice")
                )
            )
        )

        val result = response.result as McpToolCallResult
        // Business-level errors (tool executed but no devices found) use toolSuccess (isError=false),
        // distinguishing success/failure via structuredContent.
        Assert.assertFalse(result.isError)
        Assert.assertEquals(McpToolStatus.ERROR, result.structuredContent["status"])
        Assert.assertEquals(McpErrorCode.NO_DEVICE, result.structuredContent["errorCode"])
        Assert.assertTrue(result.content.first().text.contains("No connected device is available"))
        Assert.assertFalse(result.content.first().text.contains("structuredContent="))
    }

    @Test
    fun testRestartAppRejectSerialArgument() {
        val invoker = newToolInvoker()
        val response = invoker.invokeMcp(
            McpJsonRpcRequest(
                method = McpJsonRpc.Method.ToolsCall,
                id = 9,
                params = mapOf(
                    "name" to "restart",
                    "arguments" to mapOf("projectDir" to "/tmp/projectA", "serial" to "invalid")
                )
            )
        )

        val result = response.result as McpToolCallResult
        Assert.assertTrue(result.isError)
        Assert.assertEquals(McpErrorCode.INVALID_PARAMS, result.structuredContent["errorCode"])
        Assert.assertTrue(result.content.first().text.contains("Unknown argument(s): serial"))
    }

    @Test
    fun testRestartAppRejectTapActionsArgument() {
        val invoker = newToolInvoker()
        val response = invoker.invokeMcp(
            McpJsonRpcRequest(
                method = McpJsonRpc.Method.ToolsCall,
                id = 10,
                params = mapOf(
                    "name" to "restart",
                    "arguments" to mapOf(
                        "projectDir" to "/tmp/projectA",
                        "tap_actions" to listOf(
                            mapOf("text" to "MCP Test Page")
                        ),
                    ),
                )
            )
        )

        val result = response.result as McpToolCallResult
        Assert.assertTrue(result.isError)
        Assert.assertEquals(McpErrorCode.INVALID_PARAMS, result.structuredContent["errorCode"])
        Assert.assertTrue(result.content.first().text.contains("Unknown argument(s): tap_actions"))
    }

    @Test
    fun testRestartAppRejectTapActionsSwipeFields() {
        val invoker = newToolInvoker()
        val response = invoker.invokeMcp(
            McpJsonRpcRequest(
                method = McpJsonRpc.Method.ToolsCall,
                id = 11,
                params = mapOf(
                    "name" to "restart",
                    "arguments" to mapOf(
                        "projectDir" to "/tmp/projectA",
                        "tap_actions" to listOf(
                            mapOf(
                                "action" to "swipe",
                                "x" to 100,
                                "y" to 200,
                                "endX" to 100,
                                "endY" to 50,
                            )
                        ),
                    ),
                )
            )
        )

        val result = response.result as McpToolCallResult
        Assert.assertTrue(result.isError)
        Assert.assertEquals(McpErrorCode.INVALID_PARAMS, result.structuredContent["errorCode"])
        Assert.assertTrue(result.content.first().text.contains("Unknown argument(s): tap_actions"))
    }

    @Test
    fun testRestartAppRejectUnknownArgument() {
        val invoker = newToolInvoker()
        val response = invoker.invokeMcp(
            McpJsonRpcRequest(
                method = McpJsonRpc.Method.ToolsCall,
                id = 12,
                params = mapOf(
                    "name" to "restart",
                    "arguments" to mapOf(
                        "projectDir" to "/tmp/projectA",
                        "custom" to 100,
                    ),
                )
            )
        )

        val result = response.result as McpToolCallResult
        Assert.assertTrue(result.isError)
        Assert.assertEquals(McpErrorCode.INVALID_PARAMS, result.structuredContent["errorCode"])
        Assert.assertTrue(result.content.first().text.contains("Unknown argument(s): custom"))
    }
}
