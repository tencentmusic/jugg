package com.sickworm.intellij.jugg.mcp

data class McpToolsListResult(
    val tools: List<McpToolDefinition>,
)

data class McpToolDefinition(
    val name: String,
    val description: String,
    val inputSchema: McpJsonSchemaObject,
    val outputSchema: McpJsonSchemaObject? = null,
)

data class McpJsonSchemaObject(
    val type: String = "object",
    val description: String? = null,
    val properties: Map<String, McpJsonSchemaProperty> = emptyMap(),
    val required: List<String> = emptyList(),
    val additionalProperties: Boolean? = null,
)

data class McpJsonSchemaProperty(
    val type: String,
    val description: String? = null,
    val default: Any? = null,
    val minimum: Double? = null,
    val maximum: Double? = null,
    val `enum`: List<Any>? = null,
    val examples: List<Any>? = null,
    val pattern: String? = null,
    val properties: Map<String, McpJsonSchemaProperty>? = null,
    val required: List<String>? = null,
    val items: McpJsonSchemaProperty? = null,
    val additionalProperties: Boolean? = null,
)

data class McpArtifact(
    val type: String,
    val path: String,
)

data class McpToolResult(
    val status: String,
    val message: String,
    val data: Any? = emptyMap<String, Any>(),
    val artifacts: List<McpArtifact> = emptyList(),
    val errorCode: String? = null,
) {

    companion object {
        fun internalErrorResult(toolName: String, reason: String): McpToolResult {
            return McpToolResult(
                status = McpToolStatus.ERROR,
                message = "$toolName failed. Reason: $reason.",
                data = emptyMap<String, Any>(),
                artifacts = emptyList(),
                errorCode = McpErrorCode.MCP_INTERNAL_ERROR,
            )
        }
    }
}

data class McpToolCallResult(
    val content: List<McpContentItem>,
    val isError: Boolean = false,
    val structuredContent: Map<String, Any?> = emptyMap(),
)

data class McpContentItem(
    val type: String = "text",
    val text: String,
)

object McpToolStatus {
    const val OK = "OK"
    const val ERROR = "ERROR"
}
