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

    val tapActionProperties: Map<String, McpJsonSchemaProperty> = mapOf(
        "action" to McpJsonSchemaProperty(
            type = "string",
            enum = listOf("tap", "longPress", "swipe"),
            description = "Touch action type. Default: tap.",
        ),
        "x" to McpJsonSchemaProperty(
            type = "number",
            description = "X coordinate in device screen space (coordinate mode).",
            minimum = 0.0,
            examples = listOf(200),
        ),
        "y" to McpJsonSchemaProperty(
            type = "number",
            description = "Y coordinate in device screen space (coordinate mode).",
            minimum = 0.0,
            examples = listOf(400),
        ),
        "endX" to McpJsonSchemaProperty(
            type = "number",
            description = "End X coordinate for swipe (coordinate mode).",
            minimum = 0.0,
            examples = listOf(200),
        ),
        "endY" to McpJsonSchemaProperty(
            type = "number",
            description = "End Y coordinate for swipe (coordinate mode).",
            minimum = 0.0,
            examples = listOf(1200),
        ),
        "xPercent" to McpJsonSchemaProperty(
            type = "number",
            description = "X position as percentage of screen width, 0-100 (percent mode).",
            minimum = 0.0,
            maximum = 100.0,
            examples = listOf(50),
        ),
        "yPercent" to McpJsonSchemaProperty(
            type = "number",
            description = "Y position as percentage of screen height, 0-100 (percent mode).",
            minimum = 0.0,
            maximum = 100.0,
            examples = listOf(50),
        ),
        "endXPercent" to McpJsonSchemaProperty(
            type = "number",
            description = "End X as percentage of screen width for swipe, 0-100.",
            minimum = 0.0,
            maximum = 100.0,
            examples = listOf(50),
        ),
        "endYPercent" to McpJsonSchemaProperty(
            type = "number",
            description = "End Y as percentage of screen height for swipe, 0-100.",
            minimum = 0.0,
            maximum = 100.0,
            examples = listOf(20),
        ),
        "duration" to McpJsonSchemaProperty(
            type = "number",
            description = "Duration in ms. For swipe: speed. For longPress: hold time. Defaults: swipe=300, longPress=500.",
            minimum = 50.0,
        ),
        "text" to McpJsonSchemaProperty(
            type = "string",
            description = "UI element text to match (element mode). Exact match only.",
        ),
        "resourceId" to McpJsonSchemaProperty(
            type = "string",
            description = "UI element resource-id to match (element mode). Exact match only. Prefer short id (e.g. btn_play).",
        ),
        "contentDesc" to McpJsonSchemaProperty(
            type = "string",
            description = "UI element content-desc to match (element mode). Exact match only.",
        ),
        "className" to McpJsonSchemaProperty(
            type = "string",
            description = "Additional class name filter for element mode (AND logic with other selectors). Exact match only.",
        ),
    )

    // Compact variant used for tap_actions items in restart_app: retains type constraints but
    // strips description/examples to avoid duplicating the full field docs already present in
    // the top-level tap tool schema.
    val tapActionStepProperty = McpJsonSchemaProperty(
        type = "object",
        description = "Touch step. Supports the same action/mode args as tool tap.",
        properties = tapActionProperties.mapValues { (_, v) ->
            v.copy(description = null, examples = null)
        },
        additionalProperties = false,
    )
}
