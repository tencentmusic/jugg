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

    private fun composeToolText(message: String, status: String): String {
        if (message.isNotBlank()) {
            return message
        }
        return if (status == McpToolStatus.ERROR) "Tool execution failed." else "Tool executed successfully."
    }

    private fun compactSchemaObject(schema: McpJsonSchemaObject): McpJsonSchemaObject {
        return McpJsonSchemaObject(
            type = schema.type,
            properties = schema.properties.mapValues { (_, property) -> compactSchemaProperty(property) },
            required = schema.required,
            additionalProperties = schema.additionalProperties,
        )
    }

    private fun compactSchemaProperty(property: McpJsonSchemaProperty): McpJsonSchemaProperty {
        return McpJsonSchemaProperty(
            type = property.type,
            default = property.default,
            minimum = property.minimum,
            maximum = property.maximum,
            `enum` = property.`enum`,
            pattern = property.pattern,
            properties = property.properties?.mapValues { (_, nested) -> compactSchemaProperty(nested) },
            required = property.required,
            items = property.items?.let { compactSchemaProperty(it) },
            additionalProperties = property.additionalProperties,
        )
    }

    private fun compactToolDefinition(tool: McpToolDefinition): McpToolDefinition {
        return McpToolDefinition(
            name = tool.name,
            description = tool.description,
            inputSchema = compactSchemaObject(tool.inputSchema),
            outputSchema = null,
        )
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
        val compactTools = tools.map { compactToolDefinition(it) }
        return McpJsonRpcResponse(id = normalizeId(id), result = McpToolsListResult(tools = compactTools))
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
                    McpContentItem(text = composeToolText(toolResult.message, toolResult.status))
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
                    McpContentItem(text = composeToolText(message, McpToolStatus.ERROR))
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
