package com.sickworm.intellij.jugg.mcp

import org.junit.Assert
import org.junit.Test

class McpInvokerProtocolTest : McpInvokerTestBase() {

    @Test
    fun testInitializeSuccess() {
        val invoker = newBaseInvoker()
        val response = invoker.invokeMcp(
            McpJsonRpcRequest(
                id = 100,
                method = McpJsonRpc.Method.Initialize,
                params = McpInitializeParams(
                    protocolVersion = McpJsonRpc.ProtocolVersion,
                    capabilities = emptyMap(),
                    clientInfo = McpPeerInfo("test-client", "1.0.0"),
                )
            )
        )

        Assert.assertNull(response.error)
    }

    @Test
    fun testInvalidJsonRpcVersion() {
        val invoker = newBaseInvoker()
        val response = invoker.invokeMcp(
            McpJsonRpcRequest(
                jsonrpc = "1.0",
                id = 1,
                method = McpJsonRpc.Method.Ping,
                params = emptyMap<String, Any>(),
            )
        )

        Assert.assertNotNull(response.error)
        Assert.assertEquals(McpJsonRpc.ErrorCode.InvalidRequest, response.error?.code)
    }

    @Test
    fun testNotificationsInitializedAck() {
        val invoker = newBaseInvoker()
        initialize(invoker)
        val response = invoker.invokeMcp(
            McpJsonRpcRequest(
                id = 2,
                method = McpJsonRpc.Method.NotificationsInitialized,
                params = emptyMap<String, Any>(),
            )
        )

        Assert.assertNull(response.error)
    }

    @Test
    fun testPingSuccess() {
        val invoker = newBaseInvoker()
        initialize(invoker)
        val response = invoker.invokeMcp(
            McpJsonRpcRequest(
                id = 3,
                method = McpJsonRpc.Method.Ping,
                params = emptyMap<String, Any>(),
            )
        )

        Assert.assertNull(response.error)
    }

    @Test
    fun testToolsListWithoutInitializeIsAllowedInInvoker() {
        val invoker = newBaseInvoker()
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
        val invoker = newBaseInvoker()
        initialize(invoker)

        val prompts = invoker.invokeMcp(
            McpJsonRpcRequest(id = 200, method = McpJsonRpc.Method.PromptsList, params = emptyMap<String, Any>())
        )
        Assert.assertNull(prompts.error)

        val resources = invoker.invokeMcp(
            McpJsonRpcRequest(id = 201, method = McpJsonRpc.Method.ResourcesList, params = emptyMap<String, Any>())
        )
        Assert.assertNull(resources.error)

        val templates = invoker.invokeMcp(
            McpJsonRpcRequest(id = 202, method = McpJsonRpc.Method.ResourcesTemplatesList, params = emptyMap<String, Any>())
        )
        Assert.assertNull(templates.error)
    }
}
