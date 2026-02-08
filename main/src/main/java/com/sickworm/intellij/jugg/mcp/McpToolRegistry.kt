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
            ),
            McpToolDefinition(
                name = "compile",
                description = "Compile project by Jugg",
                inputSchema = McpJsonSchemaObject(
                    properties = mapOf(
                        "projectDir" to McpJsonSchemaProperty(type = "string")
                    ),
                    required = listOf("projectDir")
                )
            ),
            McpToolDefinition(
                name = "deploy",
                description = "Deploy project by Jugg",
                inputSchema = McpJsonSchemaObject(
                    properties = mapOf(
                        "projectDir" to McpJsonSchemaProperty(type = "string")
                    ),
                    required = listOf("projectDir")
                )
            ),
            McpToolDefinition(
                name = "clean_reinstall",
                description = "Force clean and reinstall by Jugg",
                inputSchema = McpJsonSchemaObject(
                    properties = mapOf(
                        "projectDir" to McpJsonSchemaProperty(type = "string")
                    ),
                    required = listOf("projectDir")
                )
            ),
            McpToolDefinition(
                name = "device_list",
                description = "List connected Android devices",
                inputSchema = McpJsonSchemaObject(
                    properties = mapOf(
                        "projectDir" to McpJsonSchemaProperty(type = "string")
                    ),
                    required = listOf("projectDir")
                )
            ),
            McpToolDefinition(
                name = "screenshot",
                description = "Capture screenshot from device",
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
            ),
            McpToolDefinition(
                name = "record",
                description = "Record device screen video",
                inputSchema = McpJsonSchemaObject(
                    properties = mapOf(
                        "projectDir" to McpJsonSchemaProperty(type = "string"),
                        "serial" to McpJsonSchemaProperty(
                            type = "string",
                            description = "Optional. If absent or invalid, fallback to selected device."
                        ),
                        "durationSec" to McpJsonSchemaProperty(
                            type = "number",
                            description = "Optional. Screenrecord duration in seconds, default 10, max 180."
                        )
                    ),
                    required = listOf("projectDir")
                )
            ),
            McpToolDefinition(
                name = "layout_dump",
                description = "Dump current UI hierarchy XML",
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
            ),
            McpToolDefinition(
                name = "app_start",
                description = "Start app activity on target device",
                inputSchema = McpJsonSchemaObject(
                    properties = mapOf(
                        "projectDir" to McpJsonSchemaProperty(type = "string"),
                        "serial" to McpJsonSchemaProperty(
                            type = "string",
                            description = "Optional. If absent or invalid, fallback to selected device."
                        ),
                        "packageName" to McpJsonSchemaProperty(
                            type = "string",
                            description = "Optional. If absent, use current Jugg package name."
                        ),
                        "activity" to McpJsonSchemaProperty(
                            type = "string",
                            description = "Optional. Supports short form (.MainActivity) or full class name."
                        )
                    ),
                    required = listOf("projectDir")
                )
            ),
            McpToolDefinition(
                name = "tap",
                description = "Tap screen coordinate on target device",
                inputSchema = McpJsonSchemaObject(
                    properties = mapOf(
                        "projectDir" to McpJsonSchemaProperty(type = "string"),
                        "serial" to McpJsonSchemaProperty(
                            type = "string",
                            description = "Optional. If absent or invalid, fallback to selected device."
                        ),
                        "x" to McpJsonSchemaProperty(type = "number"),
                        "y" to McpJsonSchemaProperty(type = "number"),
                    ),
                    required = listOf("projectDir", "x", "y")
                )
            )
        )
    }

    fun hasTool(toolName: String): Boolean {
        return listTools().any { it.name == toolName }
    }
}
