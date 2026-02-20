package com.sickworm.intellij.jugg.mcp.actions

import com.sickworm.intellij.jugg.mcp.McpJsonSchemaObject
import com.sickworm.intellij.jugg.mcp.McpJsonSchemaProperty
import com.sickworm.intellij.jugg.mcp.McpToolStatus

/**
 * McpToolSchemas centralizes reusable JSON-schema fragments for MCP tool definitions.
 */
object McpToolSchemas {
    val projectDirProperty = McpJsonSchemaProperty(
        type = "string",
        description = "Absolute project path. Must match a currently initialized IDE project.",
        pattern = "^/.+",
        examples = listOf("/Users/you/IdeaProjects/demo"),
    )

    val baseOutputSchema = McpJsonSchemaObject(
        description = "Structured result returned via tools/call structuredContent.",
        properties = mapOf(
            "status" to McpJsonSchemaProperty(type = "string", `enum` = listOf(McpToolStatus.OK, McpToolStatus.ERROR)),
            "message" to McpJsonSchemaProperty(type = "string"),
            "data" to McpJsonSchemaProperty(type = "object", additionalProperties = true),
            "artifacts" to McpJsonSchemaProperty(
                type = "array",
                items = McpJsonSchemaProperty(
                    type = "object",
                    properties = mapOf(
                        "type" to McpJsonSchemaProperty(type = "string"),
                        "path" to McpJsonSchemaProperty(type = "string"),
                    ),
                    required = listOf("type", "path"),
                    additionalProperties = false,
                )
            ),
            "errorCode" to McpJsonSchemaProperty(type = "string"),
        ),
        required = listOf("status", "message", "data", "artifacts"),
        additionalProperties = false,
    )
}
