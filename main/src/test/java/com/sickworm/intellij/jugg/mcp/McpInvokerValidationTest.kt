package com.sickworm.intellij.jugg.mcp

import org.junit.Assert
import org.junit.Test

class McpInvokerValidationTest : McpInvokerTestBase() {

    @Test
    fun testStartRecordRejectUnknownArgument() {
        val invoker = newToolInvoker()
        val response = invoker.invokeMcp(
            McpJsonRpcRequest(
                method = McpJsonRpc.Method.ToolsCall,
                id = 1,
                params = mapOf(
                    "name" to "start_record",
                    "arguments" to mapOf("projectDir" to "/tmp/projectA", "durationSec" to 500)
                )
            )
        )

        val result = response.result as McpToolCallResult
        Assert.assertTrue(result.isError)
        Assert.assertEquals(McpErrorCode.MCP_INVALID_PARAMS, result.structuredContent["errorCode"])
        Assert.assertTrue(result.content.first().text.contains("Unknown argument(s): durationSec"))
    }

    @Test
    fun testCrashReportRejectPackageNameArgument() {
        val invoker = newToolInvoker()
        val response = invoker.invokeMcp(
            McpJsonRpcRequest(
                method = McpJsonRpc.Method.ToolsCall,
                id = 2,
                params = mapOf(
                    "name" to "crash_report",
                    "arguments" to mapOf(
                        "projectDir" to "/tmp/projectA",
                        "packageName" to "invalid-package-name"
                    )
                )
            )
        )

        val result = response.result as McpToolCallResult
        Assert.assertTrue(result.isError)
        Assert.assertEquals(McpErrorCode.MCP_INVALID_PARAMS, result.structuredContent["errorCode"])
        Assert.assertTrue(result.content.first().text.contains("Unknown argument(s): packageName"))
    }

    @Test
    fun testTapRejectUnknownArgument() {
        val invoker = newToolInvoker()
        val response = invoker.invokeMcp(
            McpJsonRpcRequest(
                method = McpJsonRpc.Method.ToolsCall,
                id = 3,
                params = mapOf(
                    "name" to "tap",
                    "arguments" to mapOf(
                        "projectDir" to "/tmp/projectA",
                        "x" to 100,
                        "y" to 200,
                        "extra" to true,
                    )
                )
            )
        )

        val result = response.result as McpToolCallResult
        Assert.assertTrue(result.isError)
        Assert.assertEquals(McpErrorCode.MCP_INVALID_PARAMS, result.structuredContent["errorCode"])
        Assert.assertTrue(result.content.first().text.contains("Unknown argument(s): extra"))
    }

    @Test
    fun testStopRecordRejectMissingSessionId() {
        val invoker = newToolInvoker()
        val response = invoker.invokeMcp(
            McpJsonRpcRequest(
                method = McpJsonRpc.Method.ToolsCall,
                id = 4,
                params = mapOf(
                    "name" to "stop_record",
                    "arguments" to mapOf("projectDir" to "/tmp/projectA")
                )
            )
        )

        val result = response.result as McpToolCallResult
        Assert.assertTrue(result.isError)
        Assert.assertEquals(McpErrorCode.MCP_INVALID_PARAMS, result.structuredContent["errorCode"])
        Assert.assertTrue(result.content.first().text.contains("sessionId is required"))
    }

    @Test
    fun testListProjectsRejectUnknownArgument() {
        val invoker = newToolInvoker()
        val response = invoker.invokeMcp(
            McpJsonRpcRequest(
                method = McpJsonRpc.Method.ToolsCall,
                id = 5,
                params = mapOf(
                    "name" to "list_projects",
                    "arguments" to mapOf("projectDir" to "/tmp/projectA")
                )
            )
        )

        val result = response.result as McpToolCallResult
        Assert.assertTrue(result.isError)
        Assert.assertEquals(McpErrorCode.MCP_INVALID_PARAMS, result.structuredContent["errorCode"])
        Assert.assertTrue(result.content.first().text.contains("Unknown argument(s): projectDir"))
    }
}
