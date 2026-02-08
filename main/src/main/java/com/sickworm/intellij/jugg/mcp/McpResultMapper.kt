package com.sickworm.intellij.jugg.mcp

import kotlin.math.roundToInt
import kotlin.math.roundToLong

class McpResultMapper {

    private fun normalizeId(id: Any?): Any? {
        return when (id) {
            is Double -> id.roundToLong()
            is Float -> id.roundToInt()
            else -> id
        }
    }

    fun initialize(id: Any?): McpJsonRpcResponse {
        return McpJsonRpcResponse(
            id = normalizeId(id),
            result = McpInitializeResult(
                protocolVersion = McpJsonRpc.ProtocolVersion,
                capabilities = mapOf(
                    "tools" to mapOf(
                        "listChanged" to false
                    )
                ),
                serverInfo = McpPeerInfo(
                    name = "jugg-mcp",
                    version = "1.0.0"
                ),
                instructions = "Always provide projectDir for tool calls."
            )
        )
    }

    fun ping(id: Any?): McpJsonRpcResponse {
        return McpJsonRpcResponse(id = normalizeId(id), result = emptyMap<String, Any>())
    }

    fun promptsList(id: Any?): McpJsonRpcResponse {
        return McpJsonRpcResponse(id = normalizeId(id), result = mapOf("prompts" to emptyList<Any>()))
    }

    fun resourcesList(id: Any?): McpJsonRpcResponse {
        return McpJsonRpcResponse(id = normalizeId(id), result = mapOf("resources" to emptyList<Any>()))
    }

    fun resourcesTemplatesList(id: Any?): McpJsonRpcResponse {
        return McpJsonRpcResponse(id = normalizeId(id), result = mapOf("resourceTemplates" to emptyList<Any>()))
    }

    fun notificationAck(): McpJsonRpcResponse {
        return McpJsonRpcResponse(result = emptyMap<String, Any>())
    }

    fun toolsList(id: Any?, tools: List<McpToolDefinition>): McpJsonRpcResponse {
        return McpJsonRpcResponse(id = normalizeId(id), result = McpToolsListResult(tools = tools))
    }

    fun toolSuccess(id: Any?, toolResult: McpToolResult): McpJsonRpcResponse {
        val structured = mapOf(
            "status" to toolResult.status,
            "message" to toolResult.message,
            "data" to toolResult.data,
            "artifacts" to toolResult.artifacts,
            "errorCode" to toolResult.errorCode,
        )
        return McpJsonRpcResponse(
            id = normalizeId(id),
            result = McpToolCallResult(
                content = listOf(
                    McpContentItem(text = toolResult.message)
                ),
                isError = false,
                structuredContent = structured,
            )
        )
    }

    fun toolError(id: Any?, errorCode: String, message: String, data: Any? = emptyMap<String, Any>()): McpJsonRpcResponse {
        val structured = mapOf(
            "status" to McpToolStatus.ERROR,
            "message" to message,
            "data" to data,
            "artifacts" to emptyList<McpArtifact>(),
            "errorCode" to errorCode,
        )
        return McpJsonRpcResponse(
            id = normalizeId(id),
            result = McpToolCallResult(
                content = listOf(
                    McpContentItem(text = message)
                ),
                isError = true,
                structuredContent = structured,
            )
        )
    }

    fun jsonRpcError(id: Any?, errorCode: String, message: String, jsonRpcCode: Int): McpJsonRpcResponse {
        return McpJsonRpcResponse(
            id = normalizeId(id),
            error = McpJsonRpcError(
                code = jsonRpcCode,
                message = message,
                data = mapOf("errorCode" to errorCode),
            )
        )
    }
}
