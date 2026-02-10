package com.sickworm.intellij.jugg.mcp

import org.junit.Assert
import org.junit.Test

class McpInvokerToolSuccessTest : McpInvokerTestBase() {

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
    fun testCompileToolCallSuccess() {
        val invoker = McpInvoker(currentProjectDir = "/tmp/projectA")
        initialize(invoker)
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
        val invoker = McpInvoker(currentProjectDir = "/tmp/projectA")
        initialize(invoker)
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
        val invoker = McpInvoker(currentProjectDir = "/tmp/projectA")
        initialize(invoker)
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
    fun testDeviceListToolCallSuccess() {
        val invoker = McpInvoker(currentProjectDir = "/tmp/projectA")
        initialize(invoker)
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
        val invoker = McpInvoker(currentProjectDir = "/tmp/projectA")
        initialize(invoker)
        val response = invoker.invokeMcp(
            McpJsonRpcRequest(
                method = McpJsonRpc.Method.ToolsCall,
                id = 10,
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
                id = 11,
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
    fun testAppStartToolCallSuccess() {
        val invoker = McpInvoker(currentProjectDir = "/tmp/projectA")
        initialize(invoker)
        val response = invoker.invokeMcp(
            McpJsonRpcRequest(
                method = McpJsonRpc.Method.ToolsCall,
                id = 13,
                params = mapOf(
                    "name" to "app_start",
                    "arguments" to mapOf("projectDir" to "/tmp/projectA", "activity" to ".MainActivity")
                )
            )
        )

        val result = response.result as McpToolCallResult
        Assert.assertFalse(result.isError)
        Assert.assertEquals(McpToolStatus.OK, result.structuredContent["status"])
    }

    @Test
    fun testTapToolCallSuccess() {
        val invoker = McpInvoker(currentProjectDir = "/tmp/projectA")
        initialize(invoker)
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
    fun testStartEmulatorToolCallSuccess() {
        val invoker = McpInvoker(currentProjectDir = "/tmp/projectA")
        initialize(invoker)
        val response = invoker.invokeMcp(
            McpJsonRpcRequest(
                method = McpJsonRpc.Method.ToolsCall,
                id = 15,
                params = mapOf(
                    "name" to "start_emulator",
                    "arguments" to mapOf("projectDir" to "/tmp/projectA", "avdName" to "Pixel_8_API_35", "waitForDeviceSec" to 20)
                )
            )
        )

        val result = response.result as McpToolCallResult
        Assert.assertFalse(result.isError)
        Assert.assertEquals(McpToolStatus.OK, result.structuredContent["status"])
        Assert.assertTrue(result.content.first().text.contains("start_emulator executed successfully"))
    }

    @Test
    fun testEmulatorListToolCallSuccess() {
        val invoker = McpInvoker(currentProjectDir = "/tmp/projectA")
        initialize(invoker)
        val response = invoker.invokeMcp(
            McpJsonRpcRequest(
                method = McpJsonRpc.Method.ToolsCall,
                id = 16,
                params = mapOf(
                    "name" to "emulator_list",
                    "arguments" to mapOf("projectDir" to "/tmp/projectA")
                )
            )
        )

        val result = response.result as McpToolCallResult
        Assert.assertFalse(result.isError)
        Assert.assertEquals(McpToolStatus.OK, result.structuredContent["status"])
        Assert.assertTrue(result.content.first().text.contains("emulator_list executed successfully"))
    }
}
