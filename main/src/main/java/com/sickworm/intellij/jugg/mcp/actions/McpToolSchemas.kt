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
        description = "Project absolute path.",
        pattern = "^/.+",
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

    val tapActionProperties: Map<String, McpJsonSchemaProperty> = mapOf(
        "action" to McpJsonSchemaProperty(
            type = "string",
            enum = listOf("tap", "long-press", "swipe"),
            description = "Touch action type. Default: tap.",
        ),
        "x" to McpJsonSchemaProperty(
            type = "number",
            description = "X coordinate (coordinate mode).",
            minimum = 0.0,
        ),
        "y" to McpJsonSchemaProperty(
            type = "number",
            description = "Y coordinate (coordinate mode).",
            minimum = 0.0,
        ),
        "endX" to McpJsonSchemaProperty(
            type = "number",
            description = "End X for swipe (coordinate mode).",
            minimum = 0.0,
        ),
        "endY" to McpJsonSchemaProperty(
            type = "number",
            description = "End Y for swipe (coordinate mode).",
            minimum = 0.0,
        ),
        "xPercent" to McpJsonSchemaProperty(
            type = "number",
            description = "X% of screen width (0-100, percent mode).",
            minimum = 0.0,
            maximum = 100.0,
        ),
        "yPercent" to McpJsonSchemaProperty(
            type = "number",
            description = "Y% of screen height (0-100, percent mode).",
            minimum = 0.0,
            maximum = 100.0,
        ),
        "endXPercent" to McpJsonSchemaProperty(
            type = "number",
            description = "End X% for swipe (0-100).",
            minimum = 0.0,
            maximum = 100.0,
        ),
        "endYPercent" to McpJsonSchemaProperty(
            type = "number",
            description = "End Y% for swipe (0-100).",
            minimum = 0.0,
            maximum = 100.0,
        ),
        "duration" to McpJsonSchemaProperty(
            type = "number",
            description = "Duration in ms (swipe speed / long-press hold). Defaults: swipe=300, long-press=500.",
            minimum = 50.0,
        ),
        "text" to McpJsonSchemaProperty(
            type = "string",
            description = "Element text selector (element mode, exact match).",
        ),
        "resourceId" to McpJsonSchemaProperty(
            type = "string",
            description = "Element resource-id selector (element mode, exact match). Alias: id.",
        ),
        "id" to McpJsonSchemaProperty(
            type = "string",
            description = "Alias for resourceId. Element resource-id selector (element mode, exact match).",
        ),
        "contentDesc" to McpJsonSchemaProperty(
            type = "string",
            description = "Element content-desc selector (element mode, exact match). Alias: desc.",
        ),
        "desc" to McpJsonSchemaProperty(
            type = "string",
            description = "Alias for contentDesc. Element content-desc selector (element mode, exact match).",
        ),
        "className" to McpJsonSchemaProperty(
            type = "string",
            description = "Class name filter for element mode (AND with other selectors). Alias: class.",
        ),
        "class" to McpJsonSchemaProperty(
            type = "string",
            description = "Alias for className. Class name filter for element mode (AND with other selectors).",
        ),
    )
}
