package com.sickworm.intellij.jugg.mcp

/**
 * McpToolsListResult carries tools.
 */
data class McpToolsListResult(
    val tools: List<McpToolDefinition>,
)

/**
 * McpToolDefinition carries name, description, inputSchema, and outputSchema.
 */
data class McpToolDefinition(
    val name: String,
    val description: String,
    val inputSchema: McpJsonSchemaObject,
    val outputSchema: McpJsonSchemaObject? = null,
)

/**
 * McpJsonSchemaObject carries type, description, properties, and required.
 */
data class McpJsonSchemaObject(
    val type: String = "object",
    val description: String? = null,
    val properties: Map<String, McpJsonSchemaProperty> = emptyMap(),
    val required: List<String> = emptyList(),
    val additionalProperties: Boolean? = null,
)

/**
 * McpJsonSchemaProperty carries type, description, default, and minimum.
 */
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

/**
 * McpArtifact carries type and path.
 */
data class McpArtifact(
    val type: String,
    val path: String,
)

/**
 * McpToolResult carries status, message, data, and artifacts.
 */
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

/**
 * McpToolCallResult carries content, isError, and structuredContent.
 */
data class McpToolCallResult(
    val content: List<McpContentItem>,
    val isError: Boolean = false,
    val structuredContent: Map<String, Any?> = emptyMap(),
)

/**
 * McpContentItem carries type and text.
 */
data class McpContentItem(
    val type: String = "text",
    val text: String,
)

/**
 * McpToolStatus defines the canonical execution status values for tool results.
 */
object McpToolStatus {
    const val OK = "OK"
    const val ERROR = "ERROR"
}
