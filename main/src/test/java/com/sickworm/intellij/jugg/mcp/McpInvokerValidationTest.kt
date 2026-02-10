package com.sickworm.intellij.jugg.mcp

import org.junit.Assert
import org.junit.Test

class McpInvokerValidationTest : McpInvokerTestBase() {

    @Test
    fun testRecordRejectWhenDurationOutOfRange() {
        val invoker = McpInvoker(currentProjectDir = "/tmp/projectA")
        initialize(invoker)
        val response = invoker.invokeMcp(
            McpJsonRpcRequest(
                method = McpJsonRpc.Method.ToolsCall,
                id = 1,
                params = mapOf(
                    "name" to "record",
                    "arguments" to mapOf("projectDir" to "/tmp/projectA", "durationSec" to 500)
                )
            )
        )

        val result = response.result as McpToolCallResult
        Assert.assertTrue(result.isError)
        Assert.assertEquals(McpErrorCode.MCP_INVALID_PARAMS, result.structuredContent["errorCode"])
        Assert.assertTrue(result.content.first().text.contains("durationSec must be <= 180"))
    }

    @Test
    fun testAppStartRejectWhenPackagePatternInvalid() {
        val invoker = McpInvoker(currentProjectDir = "/tmp/projectA")
        initialize(invoker)
        val response = invoker.invokeMcp(
            McpJsonRpcRequest(
                method = McpJsonRpc.Method.ToolsCall,
                id = 2,
                params = mapOf(
                    "name" to "start_activity",
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
        Assert.assertTrue(result.content.first().text.contains("packageName does not match required pattern"))
    }

    @Test
    fun testTapRejectUnknownArgument() {
        val invoker = McpInvoker(currentProjectDir = "/tmp/projectA")
        initialize(invoker)
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
    fun testRecordApplyDefaultDurationSec() {
        val invoker = McpInvoker(currentProjectDir = "/tmp/projectA")
        initialize(invoker)
        val response = invoker.invokeMcp(
            McpJsonRpcRequest(
                method = McpJsonRpc.Method.ToolsCall,
                id = 4,
                params = mapOf(
                    "name" to "record",
                    "arguments" to mapOf("projectDir" to "/tmp/projectA")
                )
            )
        )

        val result = response.result as McpToolCallResult
        Assert.assertFalse(result.isError)
        val data = result.structuredContent["data"] as Map<*, *>
        Assert.assertEquals(10, data["durationSec"])
    }

    @Test
    fun testListProjectsRejectUnknownArgument() {
        val invoker = McpInvoker(currentProjectDir = "/tmp/projectA")
        initialize(invoker)
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
