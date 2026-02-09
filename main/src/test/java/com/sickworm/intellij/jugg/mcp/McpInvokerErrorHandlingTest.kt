package com.sickworm.intellij.jugg.mcp

import org.junit.Assert
import org.junit.Test

class McpInvokerErrorHandlingTest : McpInvokerTestBase() {

    @Test
    fun testToolsCallParamsRequired() {
        val invoker = McpInvoker(currentProjectDir = "/tmp/projectA")
        initialize(invoker)
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
        val invoker = McpInvoker(currentProjectDir = "/tmp/projectA")
        initialize(invoker)
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
        val invoker = McpInvoker(currentProjectDir = "/tmp/projectA")
        initialize(invoker)
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
        val invoker = McpInvoker(currentProjectDir = "/tmp/projectA")
        initialize(invoker)
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
        val invoker = McpInvoker(currentProjectDir = "/tmp/projectA")
        initialize(invoker)
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
    fun testRuntimeNotInitialized() {
        McpRuntimeHolder.runtime = null
        val invoker = McpInvoker(currentProjectDir = "/tmp/projectA")
        initialize(invoker)
        val response = invoker.invokeMcp(
            McpJsonRpcRequest(
                method = McpJsonRpc.Method.ToolsCall,
                id = 6,
                params = mapOf(
                    "name" to "compile_only",
                    "arguments" to mapOf("projectDir" to "/tmp/projectA")
                ),
            )
        )

        val result = response.result as McpToolCallResult
        Assert.assertTrue(result.isError)
        Assert.assertEquals(McpErrorCode.MCP_INTERNAL_ERROR, result.structuredContent["errorCode"])
        Assert.assertTrue(result.content.first().text.contains("runtime is not initialized"))
    }

    @Test
    fun testRestartAppFallbackWhenSerialMissing() {
        val invoker = McpInvoker(currentProjectDir = "/tmp/projectA")
        initialize(invoker)
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
        Assert.assertTrue(result.content.first().text.contains("Serial not provided"))
    }

    @Test
    fun testRestartAppFallbackWhenSerialInvalid() {
        val invoker = McpInvoker(currentProjectDir = "/tmp/projectA")
        initialize(invoker)
        val response = invoker.invokeMcp(
            McpJsonRpcRequest(
                method = McpJsonRpc.Method.ToolsCall,
                id = 8,
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
                id = 9,
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
}
