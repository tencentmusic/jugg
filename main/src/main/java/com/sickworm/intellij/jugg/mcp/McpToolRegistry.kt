package com.sickworm.intellij.jugg.mcp

class McpToolRegistry {

    fun listTools(): List<McpToolDefinition> {
        return listOf(
            McpToolDefinition(
                name = "list_projects",
                description = "List Android projects that can run Jugg",
                inputSchema = McpJsonSchemaObject(
                    properties = mapOf(
                        "projectDir" to McpJsonSchemaProperty(type = "string")
                    ),
                    required = emptyList()
                )
            ),
            McpToolDefinition(
                name = "restart_app",
                description = "Restart Android app by Jugg",
                inputSchema = McpJsonSchemaObject(
                    properties = mapOf(
                        "projectDir" to McpJsonSchemaProperty(type = "string"),
                        "serial" to McpJsonSchemaProperty(
                            type = "string",
                            description = "Optional. If absent or invalid, fallback to selected device."
                        )
                    ),
                    required = listOf("projectDir")
                )
            )
        )
    }

    fun hasTool(toolName: String): Boolean {
        return listTools().any { it.name == toolName }
    }
}
