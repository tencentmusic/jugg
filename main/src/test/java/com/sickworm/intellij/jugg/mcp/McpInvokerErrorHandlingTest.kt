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
        Assert.assertEquals(McpErrorCode.MCP_INVALID_PARAMS, result.structuredContent["errorCode"])
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
        Assert.assertEquals(McpErrorCode.MCP_INVALID_PARAMS, result.structuredContent["errorCode"])
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
        Assert.assertEquals(McpErrorCode.MCP_TOOL_NOT_FOUND, result.structuredContent["errorCode"])
        Assert.assertTrue(result.content.first().text.contains("Tool not found"))
    }

    @Test
    fun testProjectDirRequiredForNormalTools() {
        val invoker = newToolInvoker()
        val response = invoker.invokeMcp(
            McpJsonRpcRequest(
                method = McpJsonRpc.Method.ToolsCall,
                id = 4,
                params = mapOf(
                    "name" to "compile_only",
                    "arguments" to emptyMap<String, Any?>(),
                ),
            )
        )

        val result = response.result as McpToolCallResult
        Assert.assertTrue(result.isError)
        Assert.assertEquals(McpErrorCode.MCP_INVALID_PARAMS, result.structuredContent["errorCode"])
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
                    "name" to "compile_only",
                    "arguments" to mapOf("projectDir" to "/tmp/projectB")
                ),
            )
        )

        val result = response.result as McpToolCallResult
        Assert.assertTrue(result.isError)
        Assert.assertEquals(McpErrorCode.MCP_PROJECT_NOT_INITIALIZED, result.structuredContent["errorCode"])
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
                    "name" to "restart_app",
                    "arguments" to mapOf("projectDir" to "/tmp/projectA")
                )
            )
        )

        val result = response.result as McpToolCallResult
        Assert.assertFalse(result.isError)
        Assert.assertTrue(result.content.first().text.contains("restart_app executed successfully"))
    }

    @Test
    fun testRestartAppNoDevice() {
        val invoker = newToolInvoker(currentProjectDir = "/tmp/projectNoDevice")
        val response = invoker.invokeMcp(
            McpJsonRpcRequest(
                method = McpJsonRpc.Method.ToolsCall,
                id = 8,
                params = mapOf(
                    "name" to "restart_app",
                    "arguments" to mapOf("projectDir" to "/tmp/projectNoDevice")
                )
            )
        )

        val result = response.result as McpToolCallResult
        Assert.assertTrue(result.isError)
        Assert.assertEquals(McpErrorCode.MCP_NO_DEVICE, result.structuredContent["errorCode"])
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
                    "name" to "restart_app",
                    "arguments" to mapOf("projectDir" to "/tmp/projectA", "serial" to "invalid")
                )
            )
        )

        val result = response.result as McpToolCallResult
        Assert.assertTrue(result.isError)
        Assert.assertEquals(McpErrorCode.MCP_INVALID_PARAMS, result.structuredContent["errorCode"])
        Assert.assertTrue(result.content.first().text.contains("Unknown argument(s): serial"))
    }
}
