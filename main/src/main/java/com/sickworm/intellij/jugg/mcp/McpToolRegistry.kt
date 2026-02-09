package com.sickworm.intellij.jugg.mcp

class McpToolRegistry {

    fun listTools(): List<McpToolDefinition> {
        return listOf(
            McpToolDefinition(
                name = "list_projects",
                description = "List initialized projects in current IDE process",
                inputSchema = McpJsonSchemaObject(
                    properties = mapOf(
                        "projectDir" to McpJsonSchemaProperty(type = "string")
                    ),
                    required = listOf("projectDir")
                )
            ),
            McpToolDefinition(
                name = "restart_app",
                description = "Restart app on selected device(s)",
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
                description = "Compile modified source files by Jugg. Use to quick check file changes is compilable.",
                inputSchema = McpJsonSchemaObject(
                    properties = mapOf(
                        "projectDir" to McpJsonSchemaProperty(type = "string")
                    ),
                    required = listOf("projectDir")
                )
            ),
            McpToolDefinition(
                name = "deploy",
                description = "Compile and deploy all changes by Jugg. Use to let your file changes take effect on App.",
                inputSchema = McpJsonSchemaObject(
                    properties = mapOf(
                        "projectDir" to McpJsonSchemaProperty(type = "string")
                    ),
                    required = listOf("projectDir")
                )
            ),
            McpToolDefinition(
                name = "clean_reinstall",
                description = "Force clean and reinstall App by Jugg",
                inputSchema = McpJsonSchemaObject(
                    properties = mapOf(
                        "projectDir" to McpJsonSchemaProperty(type = "string")
                    ),
                    required = listOf("projectDir")
                )
            ),
            McpToolDefinition(
                name = "force_gradle_compile",
                description = "Downgrade path: trigger to fallback to Gradle build instead of Jugg incremental build.",
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
                description = "Record device screen video, optionally with in-record app_start/tap actions",
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
                        ),
                        "packageName" to McpJsonSchemaProperty(
                            type = "string",
                            description = "Optional. Used with activity for in-record app start."
                        ),
                        "activity" to McpJsonSchemaProperty(
                            type = "string",
                            description = "Optional. Supports short form (.MainActivity) or full class name."
                        ),
                        "tapX" to McpJsonSchemaProperty(
                            type = "number",
                            description = "Optional. Tap x, must pair with tapY when provided."
                        ),
                        "tapY" to McpJsonSchemaProperty(
                            type = "number",
                            description = "Optional. Tap y, must pair with tapX when provided."
                        ),
                        "preTapDelaySec" to McpJsonSchemaProperty(
                            type = "number",
                            description = "Optional. Delay after app_start before first tap."
                        ),
                        "tapRepeat" to McpJsonSchemaProperty(
                            type = "number",
                            description = "Optional. Number of taps for in-record interaction, default 1."
                        ),
                        "tapIntervalSec" to McpJsonSchemaProperty(
                            type = "number",
                            description = "Optional. Delay between repeated taps."
                        ),
                        "recordStartDelaySec" to McpJsonSchemaProperty(
                            type = "number",
                            description = "Optional. Delay after screenrecord starts before app_start."
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
