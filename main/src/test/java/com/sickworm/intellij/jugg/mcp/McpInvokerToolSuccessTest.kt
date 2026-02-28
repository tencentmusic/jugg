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
    fun testCompileToolCallSuccess() {
        val invoker = newToolInvoker()
        val response = invoker.invokeMcp(
            McpJsonRpcRequest(
                method = McpJsonRpc.Method.ToolsCall,
                id = 5,
                params = mapOf(
                    "name" to "compile_only",
                    "arguments" to mapOf("projectDir" to "/tmp/projectA")
                )
            )
        )

        val result = response.result as McpToolCallResult
        Assert.assertFalse(result.isError)
        Assert.assertEquals(McpToolStatus.OK, result.structuredContent["status"])
        Assert.assertTrue(result.content.first().text.contains("compile_only executed successfully"))
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
                    "name" to "compile_and_deploy",
                    "arguments" to mapOf("projectDir" to "/tmp/projectA")
                )
            )
        )

        val result = response.result as McpToolCallResult
        Assert.assertFalse(result.isError)
        Assert.assertEquals(McpToolStatus.OK, result.structuredContent["status"])
        Assert.assertTrue(result.content.first().text.contains("compile_and_deploy executed successfully"))
    }

    @Test
    fun testCleanReinstallToolCallSuccess() {
        val invoker = newToolInvoker()
        val response = invoker.invokeMcp(
            McpJsonRpcRequest(
                method = McpJsonRpc.Method.ToolsCall,
                id = 7,
                params = mapOf(
                    "name" to "clean_reinstall_apk",
                    "arguments" to mapOf("projectDir" to "/tmp/projectA")
                )
            )
        )

        val result = response.result as McpToolCallResult
        Assert.assertFalse(result.isError)
        Assert.assertEquals(McpToolStatus.OK, result.structuredContent["status"])
        Assert.assertTrue(result.content.first().text.contains("clean_reinstall_apk executed successfully"))
    }

    @Test
    fun testForceGradleCompileToolCallSuccess() {
        val invoker = newToolInvoker()
        val response = invoker.invokeMcp(
            McpJsonRpcRequest(
                method = McpJsonRpc.Method.ToolsCall,
                id = 8,
                params = mapOf(
                    "name" to "force_gradle_compile",
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
                    "name" to "get_compile_status",
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
                    "name" to "request_remote_ssh_info",
                    "arguments" to mapOf(
                        "projectDir" to "/tmp/projectA",
                        "reason" to "manual troubleshooting",
                        "userConsent" to true,
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
        Assert.assertFalse(result.isError)
        Assert.assertEquals(McpToolStatus.OK, result.structuredContent["status"])
    }

    @Test
    fun testStartRecordToolCallSuccess() {
        val invoker = newToolInvoker()
        val response = invoker.invokeMcp(
            McpJsonRpcRequest(
                method = McpJsonRpc.Method.ToolsCall,
                id = 11,
                params = mapOf(
                    "name" to "start_record",
                    "arguments" to mapOf("projectDir" to "/tmp/projectA")
                )
            )
        )

        val result = response.result as McpToolCallResult
        Assert.assertFalse(result.isError)
        Assert.assertEquals(McpToolStatus.OK, result.structuredContent["status"])
    }

    @Test
    fun testStopRecordToolCallSuccess() {
        val invoker = newToolInvoker()
        val response = invoker.invokeMcp(
            McpJsonRpcRequest(
                method = McpJsonRpc.Method.ToolsCall,
                id = 111,
                params = mapOf(
                    "name" to "stop_record",
                    "arguments" to mapOf("projectDir" to "/tmp/projectA", "sessionId" to "rec_123")
                )
            )
        )

        val result = response.result as McpToolCallResult
        Assert.assertFalse(result.isError)
        Assert.assertEquals(McpToolStatus.OK, result.structuredContent["status"])
    }

    @Test
    fun testLayoutDumpToolCallSuccess() {
        val invoker = newToolInvoker()
        val response = invoker.invokeMcp(
            McpJsonRpcRequest(
                method = McpJsonRpc.Method.ToolsCall,
                id = 12,
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

    @Test
    fun testActivityStackToolCallSuccess() {
        val invoker = newToolInvoker()
        val response = invoker.invokeMcp(
            McpJsonRpcRequest(
                method = McpJsonRpc.Method.ToolsCall,
                id = 19,
                params = mapOf(
                    "name" to "activity_stack",
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
    fun testCrashReportToolCallSuccess() {
        val invoker = newToolInvoker()
        val response = invoker.invokeMcp(
            McpJsonRpcRequest(
                method = McpJsonRpc.Method.ToolsCall,
                id = 20,
                params = mapOf(
                    "name" to "crash_report",
                    "arguments" to mapOf("projectDir" to "/tmp/projectA")
                )
            )
        )

        val result = response.result as McpToolCallResult
        Assert.assertFalse(result.isError)
        Assert.assertEquals(McpToolStatus.OK, result.structuredContent["status"])
        val data = result.structuredContent["data"] as Map<*, *>
        Assert.assertEquals(true, data["hasCrash"])
        val crashLogs = data["crashLogs"] as List<*>
        Assert.assertFalse(crashLogs.isEmpty())
    }

    @Test
    fun testStartAppToolCallNotRegistered() {
        val invoker = newToolInvoker()
        val response = invoker.invokeMcp(
            McpJsonRpcRequest(
                method = McpJsonRpc.Method.ToolsCall,
                id = 13,
                params = mapOf(
                    "name" to "start_app",
                    "arguments" to mapOf("projectDir" to "/tmp/projectA")
                )
            )
        )

        val result = response.result as McpToolCallResult
        Assert.assertTrue(result.isError)
        Assert.assertEquals(McpToolStatus.ERROR, result.structuredContent["status"])
        Assert.assertEquals(McpErrorCode.MCP_TOOL_NOT_FOUND, result.structuredContent["errorCode"])
    }

    @Test
    fun testStartActivityToolCallNotRegistered() {
        val invoker = newToolInvoker()
        val response = invoker.invokeMcp(
            McpJsonRpcRequest(
                method = McpJsonRpc.Method.ToolsCall,
                id = 17,
                params = mapOf(
                    "name" to "start_activity",
                    "arguments" to mapOf("projectDir" to "/tmp/projectA", "activity" to ".MainActivity")
                )
            )
        )

        val result = response.result as McpToolCallResult
        Assert.assertTrue(result.isError)
        Assert.assertEquals(McpToolStatus.ERROR, result.structuredContent["status"])
        Assert.assertEquals(McpErrorCode.MCP_TOOL_NOT_FOUND, result.structuredContent["errorCode"])
    }

    @Test
    fun testStartActivityWithIntentArgsNotRegistered() {
        val invoker = newToolInvoker()
        val response = invoker.invokeMcp(
            McpJsonRpcRequest(
                method = McpJsonRpc.Method.ToolsCall,
                id = 18,
                params = mapOf(
                    "name" to "start_activity",
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
        Assert.assertEquals(McpErrorCode.MCP_TOOL_NOT_FOUND, result.structuredContent["errorCode"])
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
}
