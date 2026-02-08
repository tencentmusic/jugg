package com.sickworm.intellij.jugg.mcp

data class McpToolsListResult(
    val tools: List<McpToolDefinition>,
)

data class McpToolDefinition(
    val name: String,
    val description: String,
    val inputSchema: McpJsonSchemaObject,
)

data class McpJsonSchemaObject(
    val type: String = "object",
    val properties: Map<String, McpJsonSchemaProperty> = emptyMap(),
    val required: List<String> = emptyList(),
)

data class McpJsonSchemaProperty(
    val type: String,
    val description: String? = null,
)

data class McpToolCallParams(
    val name: String,
    val arguments: Map<String, Any?> = emptyMap(),
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
)

object McpToolStatus {
    const val OK = "OK"
    const val ERROR = "ERROR"
}
