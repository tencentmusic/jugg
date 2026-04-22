package com.sickworm.intellij.jugg.mcp

import com.sickworm.intellij.jugg.mock.TestPlatformApi
import com.sickworm.intellij.jugg.platform.PlatformApi
import org.junit.Assert
import org.junit.Test

class McpInvokerToolSuccessTest : McpInvokerTestBase() {

    @Test
    fun testListProjectsAcceptedWithoutProjectDir() {
        val invoker = newBaseInvoker()
        PlatformApi.impl = TestPlatformApi()
        val response = invoker.invokeMcp(
            McpJsonRpcRequest(
                method = McpJsonRpc.Method.ToolsCall,
                id = 1,
                params = mapOf(
                    "name" to "list-projects",
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
    fun testVersionAcceptedWithoutProjectDir() {
        val invoker = newBaseInvoker()
        PlatformApi.impl = TestPlatformApi()
        val response = invoker.invokeMcp(
            McpJsonRpcRequest(
                method = McpJsonRpc.Method.ToolsCall,
                id = 2,
                params = mapOf(
                    "name" to "version",
                    "arguments" to emptyMap<String, Any?>(),
                )
            )
        )

        Assert.assertNull(response.error)
        val result = response.result as McpToolCallResult
        Assert.assertFalse(result.isError)
        Assert.assertEquals(McpToolStatus.OK, result.structuredContent["status"])
        Assert.assertTrue(result.content.first().text.contains("version executed successfully"))
    }

    @Test
    fun testCompileToolCallSuccess() {
        val invoker = newToolInvoker()
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
        Assert.assertFalse(result.content.first().text.contains("structuredContent="))
    }

    @Test
    fun testDeployToolCallSuccess() {
        val invoker = newToolInvoker()
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
        val invoker = newToolInvoker()
        val response = invoker.invokeMcp(
            McpJsonRpcRequest(
                method = McpJsonRpc.Method.ToolsCall,
                id = 7,
                params = mapOf(
                    "name" to "clean-reinstall",
                    "arguments" to mapOf("projectDir" to "/tmp/projectA")
                )
            )
        )

        val result = response.result as McpToolCallResult
        Assert.assertFalse(result.isError)
        Assert.assertEquals(McpToolStatus.OK, result.structuredContent["status"])
        Assert.assertTrue(result.content.first().text.contains("clean-reinstall executed successfully"))
    }

    @Test
    fun testForceGradleCompileToolCallSuccess() {
        val invoker = newToolInvoker()
        val response = invoker.invokeMcp(
            McpJsonRpcRequest(
                method = McpJsonRpc.Method.ToolsCall,
                id = 8,
                params = mapOf(
                    "name" to "gradle-build",
                    "arguments" to mapOf("projectDir" to "/tmp/projectA")
                )
            )
        )

        val result = response.result as McpToolCallResult
        Assert.assertFalse(result.isError)
        Assert.assertEquals(McpToolStatus.OK, result.structuredContent["status"])
    }

    @Test
    fun testGetCompileStatusToolCallSuccess() {
        val invoker = newToolInvoker()
        val response = invoker.invokeMcp(
            McpJsonRpcRequest(
                method = McpJsonRpc.Method.ToolsCall,
                id = 81,
                params = mapOf(
                    "name" to "get-compile-status",
                    "arguments" to mapOf("projectDir" to "/tmp/projectA", "jobId" to "job-1")
                )
            )
        )

        val result = response.result as McpToolCallResult
        Assert.assertFalse(result.isError)
        Assert.assertEquals(McpToolStatus.OK, result.structuredContent["status"])
    }

    @Test
    fun testRequestRemoteSshInfoToolCallSuccess() {
        val invoker = newToolInvoker()
        val response = invoker.invokeMcp(
            McpJsonRpcRequest(
                method = McpJsonRpc.Method.ToolsCall,
                id = 82,
                params = mapOf(
                    "name" to "ssh-info",
                    "arguments" to mapOf(
                        "projectDir" to "/tmp/projectA",
                        "reason" to "manual troubleshooting",
                    )
                )
            )
        )

        val result = response.result as McpToolCallResult
        Assert.assertFalse(result.isError)
        Assert.assertEquals(McpToolStatus.OK, result.structuredContent["status"])
    }

    @Test
    fun testDeviceListToolCallSuccess() {
        val invoker = newToolInvoker()
        val response = invoker.invokeMcp(
            McpJsonRpcRequest(
                method = McpJsonRpc.Method.ToolsCall,
                id = 9,
                params = mapOf(
                    "name" to "devices",
                    "arguments" to mapOf("projectDir" to "/tmp/projectA")
                )
            )
        )

        val result = response.result as McpToolCallResult
        Assert.assertFalse(result.isError)
        Assert.assertEquals(McpToolStatus.OK, result.structuredContent["status"])
    }

    @Test
    fun testScreenshotToolCallNotRegistered() {
        val invoker = newToolInvoker()
        val response = invoker.invokeMcp(
            McpJsonRpcRequest(
                method = McpJsonRpc.Method.ToolsCall,
                id = 10,
                params = mapOf(
                    "name" to "screenshot",
                    "arguments" to mapOf("projectDir" to "/tmp/projectA")
                )
            )
        )

        val result = response.result as McpToolCallResult
        Assert.assertTrue(result.isError)
        Assert.assertEquals(McpToolStatus.ERROR, result.structuredContent["status"])
        Assert.assertEquals(McpErrorCode.TOOL_NOT_FOUND, result.structuredContent["errorCode"])
    }

    @Test
    fun testStartRecordToolCallNotRegistered() {
        val invoker = newToolInvoker()
        val response = invoker.invokeMcp(
            McpJsonRpcRequest(
                method = McpJsonRpc.Method.ToolsCall,
                id = 11,
                params = mapOf(
                    "name" to "record-start",
                    "arguments" to mapOf("projectDir" to "/tmp/projectA")
                )
            )
        )

        val result = response.result as McpToolCallResult
        Assert.assertTrue(result.isError)
        Assert.assertEquals(McpToolStatus.ERROR, result.structuredContent["status"])
        Assert.assertEquals(McpErrorCode.TOOL_NOT_FOUND, result.structuredContent["errorCode"])
    }

    @Test
    fun testStopRecordToolCallNotRegistered() {
        val invoker = newToolInvoker()
        val response = invoker.invokeMcp(
            McpJsonRpcRequest(
                method = McpJsonRpc.Method.ToolsCall,
                id = 111,
                params = mapOf(
                    "name" to "record-stop",
                    "arguments" to mapOf("projectDir" to "/tmp/projectA", "sessionId" to "rec_123")
                )
            )
        )

        val result = response.result as McpToolCallResult
        Assert.assertTrue(result.isError)
        Assert.assertEquals(McpToolStatus.ERROR, result.structuredContent["status"])
        Assert.assertEquals(McpErrorCode.TOOL_NOT_FOUND, result.structuredContent["errorCode"])
    }

    @Test
    fun testLayoutDumpToolCallSuccess() {
        val invoker = newToolInvoker()
        val response = invoker.invokeMcp(
            McpJsonRpcRequest(
                method = McpJsonRpc.Method.ToolsCall,
                id = 12,
                params = mapOf(
                    "name" to "layout-dump",
                    "arguments" to mapOf("projectDir" to "/tmp/projectA")
                )
            )
        )

        val result = response.result as McpToolCallResult
        Assert.assertFalse(result.isError)
        Assert.assertEquals(McpToolStatus.OK, result.structuredContent["status"])
    }

    @Test
    fun testActivityStackToolCallSuccess() {
        val invoker = newToolInvoker()
        val response = invoker.invokeMcp(
            McpJsonRpcRequest(
                method = McpJsonRpc.Method.ToolsCall,
                id = 19,
                params = mapOf(
                    "name" to "activity-stack",
                    "arguments" to mapOf("projectDir" to "/tmp/projectA")
                )
            )
        )

        val result = response.result as McpToolCallResult
        Assert.assertFalse(result.isError)
        Assert.assertEquals(McpToolStatus.OK, result.structuredContent["status"])
        val data = result.structuredContent["data"] as Map<*, *>
        Assert.assertEquals("com.example.app/.MainActivity", data["topActivity"])
        val activities = data["activities"] as List<*>
        Assert.assertFalse(activities.isEmpty())
    }

    @Test
    fun testStartAppToolCallNotRegistered() {
        val invoker = newToolInvoker()
        val response = invoker.invokeMcp(
            McpJsonRpcRequest(
                method = McpJsonRpc.Method.ToolsCall,
                id = 13,
                params = mapOf(
                    "name" to "start-app",
                    "arguments" to mapOf("projectDir" to "/tmp/projectA")
                )
            )
        )

        val result = response.result as McpToolCallResult
        Assert.assertTrue(result.isError)
        Assert.assertEquals(McpToolStatus.ERROR, result.structuredContent["status"])
        Assert.assertEquals(McpErrorCode.TOOL_NOT_FOUND, result.structuredContent["errorCode"])
    }

    @Test
    fun testStartActivityToolCallNotRegistered() {
        val invoker = newToolInvoker()
        val response = invoker.invokeMcp(
            McpJsonRpcRequest(
                method = McpJsonRpc.Method.ToolsCall,
                id = 17,
                params = mapOf(
                    "name" to "start-activity",
                    "arguments" to mapOf("projectDir" to "/tmp/projectA", "activity" to ".MainActivity")
                )
            )
        )

        val result = response.result as McpToolCallResult
        Assert.assertTrue(result.isError)
        Assert.assertEquals(McpToolStatus.ERROR, result.structuredContent["status"])
        Assert.assertEquals(McpErrorCode.TOOL_NOT_FOUND, result.structuredContent["errorCode"])
    }

    @Test
    fun testStartActivityWithIntentArgsNotRegistered() {
        val invoker = newToolInvoker()
        val response = invoker.invokeMcp(
            McpJsonRpcRequest(
                method = McpJsonRpc.Method.ToolsCall,
                id = 18,
                params = mapOf(
                    "name" to "start-activity",
                    "arguments" to mapOf(
                        "projectDir" to "/tmp/projectA",
                        "packageName" to "com.example.myapplication",
                        "activity" to ".MainActivity",
                        "action" to "android.intent.action.VIEW",
                        "categories" to listOf("android.intent.category.DEFAULT"),
                        "data" to "app://detail/123",
                        "mimeType" to "text/plain",
                        "flags" to listOf("0x10000000"),
                        "extras" to mapOf(
                            "from" to "mcp",
                            "count" to 3,
                            "debug" to true,
                        ),
                        "user" to 0,
                    )
                )
            )
        )

        val result = response.result as McpToolCallResult
        Assert.assertTrue(result.isError)
        Assert.assertEquals(McpToolStatus.ERROR, result.structuredContent["status"])
        Assert.assertEquals(McpErrorCode.TOOL_NOT_FOUND, result.structuredContent["errorCode"])
    }

    @Test
    fun testFigmaLayoutVerifyNotRegistered() {
        val invoker = newToolInvoker()
        val response = invoker.invokeMcp(
            McpJsonRpcRequest(
                method = McpJsonRpc.Method.ToolsCall,
                id = 15,
                params = mapOf(
                    "name" to "figma-layout-verify",
                    "arguments" to mapOf("projectDir" to "/tmp/projectA", "figmaJsonPath" to "/tmp/figma.json")
                )
            )
        )

        val result = response.result as McpToolCallResult
        Assert.assertTrue(result.isError)
        Assert.assertEquals(McpErrorCode.TOOL_NOT_FOUND, result.structuredContent["errorCode"])
    }

    @Test
    fun testTapToolCallSuccess() {
        val invoker = newToolInvoker()
        val response = invoker.invokeMcp(
            McpJsonRpcRequest(
                method = McpJsonRpc.Method.ToolsCall,
                id = 14,
                params = mapOf(
                    "name" to "tap",
                    "arguments" to mapOf("projectDir" to "/tmp/projectA", "x" to 100, "y" to 200)
                )
            )
        )

        val result = response.result as McpToolCallResult
        Assert.assertFalse(result.isError)
        Assert.assertEquals(McpToolStatus.OK, result.structuredContent["status"])
    }

    @Test
    fun testWaitLogsToolCallSuccess() {
        val invoker = newToolInvoker()
        val response = invoker.invokeMcp(
            McpJsonRpcRequest(
                method = McpJsonRpc.Method.ToolsCall,
                id = 20,
                params = mapOf(
                    "name" to "wait-logs",
                    "arguments" to mapOf(
                        "projectDir" to "/tmp/projectA",
                        "marker" to "\\[JUGG_AR\\] DONE",
                    )
                )
            )
        )

        val result = response.result as McpToolCallResult
        Assert.assertFalse(result.isError)
        Assert.assertEquals(McpToolStatus.OK, result.structuredContent["status"])
        val data = result.structuredContent["data"] as Map<*, *>
        Assert.assertEquals("marker", data["stopReason"])
    }
}
