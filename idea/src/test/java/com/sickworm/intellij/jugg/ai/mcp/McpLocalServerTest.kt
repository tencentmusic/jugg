package com.sickworm.intellij.jugg.ai.mcp

import com.google.gson.Gson
import com.sickworm.intellij.jugg.ide.logic.IdeaPlatformApi
import com.sickworm.intellij.jugg.ai.mcp.actions.McpToolActionRegistry
import com.sickworm.intellij.jugg.platform.PlatformApi
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.After
import org.junit.Assert
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class McpLocalServerTest {

    companion object {
        init {
            PlatformApi.impl = IdeaPlatformApi()
        }
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()

    private val initializePayload = """
        {
          "jsonrpc": "2.0",
          "id": 100,
          "method": "initialize",
          "params": {
            "protocolVersion": "2025-06-18",
            "capabilities": {},
            "clientInfo": {"name": "test-client", "version": "1.0.0"}
          }
        }
    """.trimIndent()

    @Before
    fun setUp() {
        PlatformApi.impl = IdeaPlatformApi()
        if (McpLocalServer.isRunning()) {
            McpLocalServer.stop()
        }
    }

    @After
    fun tearDown() {
        if (McpLocalServer.isRunning()) {
            McpLocalServer.stop()
        }
    }

    @Test
    fun testServerStartAndStop() {
        Assert.assertFalse(McpLocalServer.isRunning())
        McpLocalServer.start()
        Assert.assertTrue(McpLocalServer.isRunning())
        Assert.assertTrue(McpLocalServer.getPort() in 12320..12329)
    }

    @Test
    fun testAnyHttpRequestRefreshesExternalActivity() {
        val activityCount = AtomicInteger()
        McpLocalServer.start { activityCount.incrementAndGet() }

        val request = Request.Builder()
            .url("http://localhost:${McpLocalServer.getPort()}/jugg-mcp")
            .get()
            .build()
        client.newCall(request).execute().close()

        Assert.assertEquals(1, activityCount.get())
    }

    @Test
    fun testToolsListRequest() {
        McpLocalServer.start()
        doInitialize()
        val requestJson = """
            {
              "jsonrpc": "2.0",
              "id": 1,
              "method": "tools/list",
              "params": {}
            }
        """.trimIndent()

        val request = Request.Builder()
            .url("http://localhost:${McpLocalServer.getPort()}/jugg-mcp")
            .post(requestJson.toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall(request).execute().use { response ->
            Assert.assertEquals(200, response.code)
            val responseBody = response.body?.string().orEmpty()
            val rpcResponse = gson.fromJson(responseBody, McpJsonRpcResponse::class.java)
            Assert.assertNull(rpcResponse.error)
            val resultJson = gson.toJsonTree(rpcResponse.result).asJsonObject
            val tools = resultJson.getAsJsonArray("tools")
            val names = tools.mapNotNull { item ->
                val nameElement = item.asJsonObject.get("name")
                if (nameElement == null || nameElement.isJsonNull) {
                    null
                } else {
                    nameElement.asString
                }
            }
            Assert.assertTrue(names.contains(McpToolActionRegistry.ToolNames.LIST_PROJECTS))
            Assert.assertTrue(names.contains(McpToolActionRegistry.ToolNames.RESTART))
            Assert.assertTrue(names.contains(McpToolActionRegistry.ToolNames.COMPILE))
            Assert.assertTrue(names.contains(McpToolActionRegistry.ToolNames.DEPLOY))
            Assert.assertTrue(names.contains(McpToolActionRegistry.ToolNames.REINSTALL))
            Assert.assertTrue(names.contains(McpToolActionRegistry.ToolNames.GRADLE_BUILD))
            Assert.assertTrue(names.contains(McpToolActionRegistry.ToolNames.GET_COMPILE_STATUS))
            Assert.assertTrue(names.contains(McpToolActionRegistry.ToolNames.SSH_INFO))
            Assert.assertTrue(names.contains(McpToolActionRegistry.ToolNames.DEVICES))
            Assert.assertTrue(names.contains(McpToolActionRegistry.ToolNames.LAYOUT_DUMP))
            Assert.assertTrue(names.contains(McpToolActionRegistry.ToolNames.ACTIVITY_STACK))
            Assert.assertTrue(names.contains(McpToolActionRegistry.ToolNames.TAP))
        }
    }

    @Test
    fun testToolsListWithoutInitialize() {
        McpLocalServer.start()
        val requestJson = """
            {
              "jsonrpc": "2.0",
              "id": 10,
              "method": "tools/list",
              "params": {}
            }
        """.trimIndent()

        val request = Request.Builder()
            .url("http://localhost:${McpLocalServer.getPort()}/jugg-mcp")
            .post(requestJson.toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall(request).execute().use { response ->
            Assert.assertEquals(200, response.code)
            val responseBody = response.body?.string().orEmpty()
            val rpcResponse = gson.fromJson(responseBody, McpJsonRpcResponse::class.java)
            Assert.assertNull(rpcResponse.error)
        }
    }

    @Test
    fun testToolsCallMissingProjectDir() {
        McpLocalServer.start()
        doInitialize()
        val requestJson = """
            {
              "jsonrpc": "2.0",
              "id": 2,
              "method": "tools/call",
              "params": {
                "name": "${McpToolActionRegistry.ToolNames.RESTART}",
                "arguments": {}
              }
            }
        """.trimIndent()

        val request = Request.Builder()
            .url("http://localhost:${McpLocalServer.getPort()}/jugg-mcp")
            .post(requestJson.toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall(request).execute().use { response ->
            Assert.assertEquals(200, response.code)
            val responseBody = response.body?.string().orEmpty()
            val rpcResponse = gson.fromJson(responseBody, McpJsonRpcResponse::class.java)
            Assert.assertNull(rpcResponse.error)
            val resultJson = gson.toJsonTree(rpcResponse.result).asJsonObject
            if (resultJson.has("isError")) {
                Assert.assertEquals(true, resultJson.get("isError").asBoolean)
                val structured = resultJson.getAsJsonObject("structuredContent")
                Assert.assertEquals("ERROR", structured.get("status").asString)
                Assert.assertEquals("INVALID_PARAMS", structured.get("errorCode").asString)
            } else {
                Assert.assertEquals("ERROR", resultJson.get("status").asString)
                Assert.assertEquals("INVALID_PARAMS", resultJson.get("errorCode").asString)
            }
        }
    }

    /**
     * Verifies that list-projects does NOT require projectDir and returns OK,
     * ensuring the whitelist in IdeaMcpRuntime bypasses the projectDir guard correctly.
     */
    @Test
    fun testListProjectsNoProjectDirRequired() {
        McpLocalServer.start()
        doInitialize()
        val requestJson = """
            {
              "jsonrpc": "2.0",
              "id": 3,
              "method": "tools/call",
              "params": {
                "name": "${McpToolActionRegistry.ToolNames.LIST_PROJECTS}",
                "arguments": {}
              }
            }
        """.trimIndent()

        val request = Request.Builder()
            .url("http://localhost:${McpLocalServer.getPort()}/jugg-mcp")
            .post(requestJson.toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall(request).execute().use { response ->
            Assert.assertEquals(200, response.code)
            val responseBody = response.body?.string().orEmpty()
            val rpcResponse = gson.fromJson(responseBody, McpJsonRpcResponse::class.java)
            Assert.assertNull(rpcResponse.error)
            val resultJson = gson.toJsonTree(rpcResponse.result).asJsonObject
            val structured = resultJson.getAsJsonObject("structuredContent")
            // list-projects must succeed without projectDir (whitelist bypass)
            Assert.assertEquals("OK", structured.get("status").asString)
        }
    }

    @Test
    fun testGetEndpointMethodNotAllowed() {
        McpLocalServer.start()

        val request = Request.Builder()
            .url("http://localhost:${McpLocalServer.getPort()}/jugg-mcp")
            .get()
            .build()

        client.newCall(request).execute().use { response ->
            Assert.assertEquals(405, response.code)
            val responseBody = response.body?.string().orEmpty()
            val rpcResponse = gson.fromJson(responseBody, McpJsonRpcResponse::class.java)
            Assert.assertNotNull(rpcResponse.error)
            Assert.assertEquals(McpJsonRpc.ErrorCode.MethodNotFound, rpcResponse.error?.code)
        }
    }

    @Test
    fun testNotificationReturns202() {
        McpLocalServer.start()
        doInitialize()

        val notificationJson = """
            {
              "jsonrpc": "2.0",
              "method": "notifications/initialized",
              "params": {}
            }
        """.trimIndent()

        val request = Request.Builder()
            .url("http://localhost:${McpLocalServer.getPort()}/jugg-mcp")
            .post(notificationJson.toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall(request).execute().use { response ->
            Assert.assertEquals(202, response.code)
            Assert.assertTrue((response.body?.string().orEmpty()).isEmpty())
        }
    }

    @Test
    fun testInvalidJson() {
        McpLocalServer.start()

        val request = Request.Builder()
            .url("http://localhost:${McpLocalServer.getPort()}/jugg-mcp")
            .post("{invalid json}".toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall(request).execute().use { response ->
            Assert.assertEquals(400, response.code)
            val responseBody = response.body?.string().orEmpty()
            val rpcResponse = gson.fromJson(responseBody, McpJsonRpcResponse::class.java)
            Assert.assertNotNull(rpcResponse.error)
            Assert.assertEquals(McpJsonRpc.ErrorCode.ParseError, rpcResponse.error?.code)
        }
    }

    @Test
    fun testUnsupportedHttpMethod() {
        McpLocalServer.start()

        val request = Request.Builder()
            .url("http://localhost:${McpLocalServer.getPort()}/jugg-mcp")
            .put("".toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall(request).execute().use { response ->
            Assert.assertEquals(405, response.code)
            val responseBody = response.body?.string().orEmpty()
            val rpcResponse = gson.fromJson(responseBody, McpJsonRpcResponse::class.java)
            Assert.assertNotNull(rpcResponse.error)
            Assert.assertEquals(McpJsonRpc.ErrorCode.MethodNotFound, rpcResponse.error?.code)
        }
    }

    @Test
    fun testPromptsAndResourcesList() {
        McpLocalServer.start()
        doInitialize()

        val promptsRequest = Request.Builder()
            .url("http://localhost:${McpLocalServer.getPort()}/jugg-mcp")
            .post("""
                {"jsonrpc":"2.0","id":30,"method":"prompts/list","params":{}}
            """.trimIndent().toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall(promptsRequest).execute().use { response ->
            Assert.assertEquals(200, response.code)
            val body = response.body?.string().orEmpty()
            val rpcResponse = gson.fromJson(body, McpJsonRpcResponse::class.java)
            Assert.assertNull(rpcResponse.error)
        }

        val resourcesRequest = Request.Builder()
            .url("http://localhost:${McpLocalServer.getPort()}/jugg-mcp")
            .post("""
                {"jsonrpc":"2.0","id":31,"method":"resources/list","params":{}}
            """.trimIndent().toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall(resourcesRequest).execute().use { response ->
            Assert.assertEquals(200, response.code)
            val body = response.body?.string().orEmpty()
            val rpcResponse = gson.fromJson(body, McpJsonRpcResponse::class.java)
            Assert.assertNull(rpcResponse.error)
        }
    }

    @Test
    fun testUnsupportedProtocolVersion() {
        McpLocalServer.start()
        val requestJson = """
            {
              "jsonrpc": "2.0",
              "id": 88,
              "method": "initialize",
              "params": {
                "protocolVersion": "2025-06-18",
                "capabilities": {},
                "clientInfo": {"name": "test-client", "version": "1.0.0"}
              }
            }
        """.trimIndent()

        val request = Request.Builder()
            .url("http://localhost:${McpLocalServer.getPort()}/jugg-mcp")
            .addHeader("MCP-Protocol-Version", "2099-01-01")
            .post(requestJson.toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall(request).execute().use { response ->
            Assert.assertEquals(400, response.code)
            val responseBody = response.body?.string().orEmpty()
            val rpcResponse = gson.fromJson(responseBody, McpJsonRpcResponse::class.java)
            Assert.assertNotNull(rpcResponse.error)
            Assert.assertEquals(McpJsonRpc.ErrorCode.InvalidRequest, rpcResponse.error?.code)
        }
    }

    private fun doInitialize() {
        val request = Request.Builder()
            .url("http://localhost:${McpLocalServer.getPort()}/jugg-mcp")
            .post(initializePayload.toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall(request).execute().use { response ->
            Assert.assertEquals(200, response.code)
            val responseBody = response.body?.string().orEmpty()
            val rpcResponse = gson.fromJson(responseBody, McpJsonRpcResponse::class.java)
            Assert.assertNull(rpcResponse.error)
        }
    }
}
