package com.sickworm.intellij.jugg.mcp

class McpToolRegistry {

    fun listTools(): List<McpToolDefinition> {
        val projectDirProperty = McpJsonSchemaProperty(
            type = "string",
            description = "Absolute project path. Must match a currently initialized IDE project.",
            pattern = "^/.+",
            examples = listOf("/Users/you/IdeaProjects/demo"),
        )
        val serialProperty = McpJsonSchemaProperty(
            type = "string",
            description = "Optional target device serial. Prefer value from device_list. If absent or invalid, fallback to IDE-selected device.",
            pattern = "^\\S+$",
            examples = listOf("emulator-5554"),
        )
        val deviceProperty = McpJsonSchemaProperty(
            type = "object",
            properties = mapOf(
                "serial" to McpJsonSchemaProperty(type = "string"),
                "name" to McpJsonSchemaProperty(type = "string"),
                "isOnline" to McpJsonSchemaProperty(type = "boolean"),
            ),
            required = listOf("serial", "name", "isOnline"),
            additionalProperties = false,
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

        return listOf(
            McpToolDefinition(
                name = "list_projects",
                description = "List projects initialized in this IDE process. Use when you need a valid projectDir before calling other tools. Avoid when projectDir is already known. Side effects: none.",
                inputSchema = McpJsonSchemaObject(
                    description = "No arguments required.",
                    properties = emptyMap(),
                    additionalProperties = false,
                ),
                outputSchema = baseOutputSchema.copy(
                    properties = baseOutputSchema.properties + mapOf(
                        "data" to McpJsonSchemaProperty(
                            type = "object",
                            properties = mapOf(
                                "projects" to McpJsonSchemaProperty(
                                    type = "array",
                                    items = McpJsonSchemaProperty(
                                        type = "object",
                                        properties = mapOf(
                                            "projectDir" to McpJsonSchemaProperty(type = "string"),
                                            "initialized" to McpJsonSchemaProperty(type = "boolean"),
                                        ),
                                        required = listOf("projectDir", "initialized"),
                                        additionalProperties = false,
                                    )
                                )
                            ),
                            required = listOf("projects"),
                            additionalProperties = false,
                        )
                    )
                )
            ),
            McpToolDefinition(
                name = "restart_app",
                description = "Restart app on target device. Use when app process must be refreshed after deploy or runtime changes. Avoid when installation artifacts must be replaced. Side effects: restarts app process, no reinstall.",
                inputSchema = McpJsonSchemaObject(
                    properties = mapOf(
                        "projectDir" to projectDirProperty,
                        "serial" to serialProperty,
                    ),
                    required = listOf("projectDir"),
                    additionalProperties = false,
                ),
                outputSchema = baseOutputSchema,
            ),
            McpToolDefinition(
                name = "emulator_list",
                description = "List available Android Virtual Devices (AVDs) from host Android SDK emulator. Use before start_emulator to choose a valid avdName. Side effects: none.",
                inputSchema = McpJsonSchemaObject(
                    properties = mapOf(
                        "projectDir" to projectDirProperty,
                    ),
                    required = listOf("projectDir"),
                    additionalProperties = false,
                ),
                outputSchema = baseOutputSchema.copy(
                    properties = baseOutputSchema.properties + mapOf(
                        "data" to McpJsonSchemaProperty(
                            type = "object",
                            properties = mapOf(
                                "avds" to McpJsonSchemaProperty(
                                    type = "array",
                                    items = McpJsonSchemaProperty(
                                        type = "object",
                                        properties = mapOf(
                                            "name" to McpJsonSchemaProperty(type = "string"),
                                            "isRunning" to McpJsonSchemaProperty(type = "boolean"),
                                            "serial" to McpJsonSchemaProperty(type = "string"),
                                        ),
                                        required = listOf("name", "isRunning"),
                                        additionalProperties = false,
                                    )
                                )
                            ),
                            required = listOf("avds"),
                            additionalProperties = false,
                        )
                    )
                ),
            ),
            McpToolDefinition(
                name = "start_emulator",
                description = "Start an Android emulator (AVD) process from host environment. Use when no suitable emulator is online before deploy/verification. Avoid when a usable online device already exists. Side effects: launches emulator process on host and may change connected device list.",
                inputSchema = McpJsonSchemaObject(
                    properties = mapOf(
                        "projectDir" to projectDirProperty,
                        "avdName" to McpJsonSchemaProperty(
                            type = "string",
                            description = "Optional AVD name. If absent, fallback to first available AVD from `emulator -list-avds`.",
                            pattern = "^\\S.*$",
                            examples = listOf("Pixel_8_API_35"),
                        ),
                        "waitForDeviceSec" to McpJsonSchemaProperty(
                            type = "number",
                            description = "Optional max wait seconds for a newly booted emulator to appear as online device.",
                            default = 45,
                            minimum = 0.0,
                            maximum = 300.0,
                            examples = listOf(0, 45, 120),
                        ),
                    ),
                    required = listOf("projectDir"),
                    additionalProperties = false,
                ),
                outputSchema = baseOutputSchema.copy(
                    properties = baseOutputSchema.properties + mapOf(
                        "data" to McpJsonSchemaProperty(
                            type = "object",
                            properties = mapOf(
                                "avdName" to McpJsonSchemaProperty(type = "string"),
                                "emulatorSerial" to McpJsonSchemaProperty(type = "string"),
                                "started" to McpJsonSchemaProperty(type = "boolean"),
                                "waitedSec" to McpJsonSchemaProperty(type = "number", minimum = 0.0),
                            ),
                            required = listOf("avdName", "started", "waitedSec"),
                            additionalProperties = false,
                        )
                    )
                ),
            ),
            McpToolDefinition(
                name = "compile_only",
                description = "Compile modified source files with Jugg incremental build without deploying to device. Use when you want to validate that code compiles successfully, or when no device is connected. Avoid when changes must take effect on device. Side effects: build only, no deploy and no app restart.",
                inputSchema = McpJsonSchemaObject(
                    properties = mapOf(
                        "projectDir" to projectDirProperty,
                    ),
                    required = listOf("projectDir"),
                    additionalProperties = false,
                ),
                outputSchema = baseOutputSchema,
            ),
            McpToolDefinition(
                name = "compile_and_deploy",
                description = "Compile modified source files then deploy changed artifacts to device with Jugg. This is the default path for normal iteration. Use when code changes must take effect on device. Avoid when incremental state is broken and full reinstall is needed. Side effects: builds and updates app artifacts on device.",
                inputSchema = McpJsonSchemaObject(
                    properties = mapOf(
                        "projectDir" to projectDirProperty,
                    ),
                    required = listOf("projectDir"),
                    additionalProperties = false,
                ),
                outputSchema = baseOutputSchema,
            ),
            McpToolDefinition(
                name = "clean_reinstall_apk",
                description = "Uninstall app then perform a full Gradle build and reinstall APK. Clears app data including Jugg incremental patches stored in code_cache. Use when incremental deploy state is corrupted or signatures mismatch. Avoid for quick iteration due to slower execution. Side effects: uninstalls app (losing all app data), rebuilds from scratch, and reinstalls APK.",
                inputSchema = McpJsonSchemaObject(
                    properties = mapOf(
                        "projectDir" to projectDirProperty,
                    ),
                    required = listOf("projectDir"),
                    additionalProperties = false,
                ),
                outputSchema = baseOutputSchema,
            ),
            McpToolDefinition(
                name = "force_gradle_compile",
                description = "Compile via Gradle fallback instead of Jugg incremental build. Use when Jugg compile repeatedly fails or behaves unexpectedly. Avoid as default path for routine iterations. Side effects: slower full Gradle-oriented compile path.",
                inputSchema = McpJsonSchemaObject(
                    properties = mapOf(
                        "projectDir" to projectDirProperty,
                    ),
                    required = listOf("projectDir"),
                    additionalProperties = false,
                ),
                outputSchema = baseOutputSchema.copy(
                    properties = baseOutputSchema.properties + mapOf(
                        "data" to McpJsonSchemaProperty(
                            type = "object",
                            properties = mapOf(
                                "triggered" to McpJsonSchemaProperty(type = "boolean")
                            ),
                            required = listOf("triggered"),
                            additionalProperties = true,
                        )
                    )
                ),
            ),
            McpToolDefinition(
                name = "device_list",
                description = "List currently connected Android devices. Use when you need a valid serial for device-targeting tools. Avoid when operating only on the already selected IDE device. Side effects: none.",
                inputSchema = McpJsonSchemaObject(
                    properties = mapOf(
                        "projectDir" to projectDirProperty,
                    ),
                    required = listOf("projectDir"),
                    additionalProperties = false,
                ),
                outputSchema = baseOutputSchema.copy(
                    properties = baseOutputSchema.properties + mapOf(
                        "data" to McpJsonSchemaProperty(
                            type = "object",
                            properties = mapOf(
                                "devices" to McpJsonSchemaProperty(
                                    type = "array",
                                    items = McpJsonSchemaProperty(
                                        type = "object",
                                        properties = mapOf(
                                            "serial" to McpJsonSchemaProperty(type = "string"),
                                            "name" to McpJsonSchemaProperty(type = "string"),
                                            "isOnline" to McpJsonSchemaProperty(type = "boolean"),
                                            "api" to McpJsonSchemaProperty(type = "number"),
                                            "isSelected" to McpJsonSchemaProperty(type = "boolean"),
                                        ),
                                        required = listOf("serial", "name", "isOnline", "api", "isSelected"),
                                        additionalProperties = false,
                                    )
                                )
                            ),
                            required = listOf("devices"),
                            additionalProperties = false,
                        )
                    )
                ),
            ),
            McpToolDefinition(
                name = "screenshot",
                description = "Capture a screenshot from target device. Use when you need current visual UI state for debugging or coordinate planning. Avoid when UI hierarchy XML is required instead. Side effects: read-only capture, no app state change.",
                inputSchema = McpJsonSchemaObject(
                    properties = mapOf(
                        "projectDir" to projectDirProperty,
                        "serial" to serialProperty,
                    ),
                    required = listOf("projectDir"),
                    additionalProperties = false,
                ),
                outputSchema = baseOutputSchema.copy(
                    properties = baseOutputSchema.properties + mapOf(
                        "data" to McpJsonSchemaProperty(
                            type = "object",
                            properties = mapOf(
                                "device" to deviceProperty,
                                "file" to McpJsonSchemaProperty(type = "string", pattern = "^/.+\\.png$"),
                            ),
                            required = listOf("device", "file"),
                            additionalProperties = false,
                        )
                    )
                ),
            ),
            McpToolDefinition(
                name = "record",
                description = "Record device screen video, with optional start_activity and tap actions during recording. Use when you need reproducible visual traces. Avoid for a single-frame check where screenshot is enough. Side effects: may launch activity and inject taps if configured.",
                inputSchema = McpJsonSchemaObject(
                    properties = mapOf(
                        "projectDir" to projectDirProperty,
                        "serial" to serialProperty,
                        "durationSec" to McpJsonSchemaProperty(
                            type = "number",
                            description = "Optional recording duration in seconds.",
                            default = 10,
                            minimum = 1.0,
                            maximum = 180.0,
                            examples = listOf(10, 30),
                        ),
                        "packageName" to McpJsonSchemaProperty(
                            type = "string",
                            description = "Optional package for start_activity action during recording.",
                            pattern = "^[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]*)+$",
                            examples = listOf("com.example.app"),
                        ),
                        "activity" to McpJsonSchemaProperty(
                            type = "string",
                            description = "Optional activity for start_activity action. Supports short form (.MainActivity) or full class name.",
                            pattern = "^\\.?[A-Za-z_][A-Za-z0-9_$.]*$",
                            examples = listOf(".MainActivity", "com.example.app.MainActivity"),
                        ),
                        "tapX" to McpJsonSchemaProperty(
                            type = "number",
                            description = "Optional tap x coordinate. Must be provided with tapY.",
                            minimum = 0.0,
                        ),
                        "tapY" to McpJsonSchemaProperty(
                            type = "number",
                            description = "Optional tap y coordinate. Must be provided with tapX.",
                            minimum = 0.0,
                        ),
                        "preTapDelaySec" to McpJsonSchemaProperty(
                            type = "number",
                            description = "Optional delay after start_activity before first tap.",
                            default = 0,
                            minimum = 0.0,
                            maximum = 30.0,
                        ),
                        "tapRepeat" to McpJsonSchemaProperty(
                            type = "number",
                            description = "Optional number of repeated taps.",
                            default = 1,
                            minimum = 1.0,
                            maximum = 20.0,
                        ),
                        "tapIntervalSec" to McpJsonSchemaProperty(
                            type = "number",
                            description = "Optional delay between repeated taps.",
                            default = 0,
                            minimum = 0.0,
                            maximum = 30.0,
                        ),
                        "recordStartDelaySec" to McpJsonSchemaProperty(
                            type = "number",
                            description = "Optional delay after recording starts before start_activity.",
                            default = 0,
                            minimum = 0.0,
                            maximum = 30.0,
                        )
                    ),
                    required = listOf("projectDir"),
                    additionalProperties = false,
                ),
                outputSchema = baseOutputSchema.copy(
                    properties = baseOutputSchema.properties + mapOf(
                        "data" to McpJsonSchemaProperty(
                            type = "object",
                            properties = mapOf(
                                "device" to deviceProperty,
                                "durationSec" to McpJsonSchemaProperty(type = "number", minimum = 1.0, maximum = 180.0),
                                "file" to McpJsonSchemaProperty(type = "string", pattern = "^/.+\\.mp4$"),
                                "packageName" to McpJsonSchemaProperty(type = "string"),
                                "activity" to McpJsonSchemaProperty(type = "string"),
                                "tapX" to McpJsonSchemaProperty(type = "number"),
                                "tapY" to McpJsonSchemaProperty(type = "number"),
                                "tapRepeat" to McpJsonSchemaProperty(type = "number"),
                            ),
                            required = listOf("device", "durationSec", "file"),
                            additionalProperties = true,
                        )
                    )
                ),
            ),
            McpToolDefinition(
                name = "layout_dump",
                description = "Dump current UI hierarchy XML from target device. Use when you need structured node info before tap automation. Avoid when visual evidence only is needed. Side effects: read-only dump, no build or app mutation.",
                inputSchema = McpJsonSchemaObject(
                    properties = mapOf(
                        "projectDir" to projectDirProperty,
                        "serial" to serialProperty,
                    ),
                    required = listOf("projectDir"),
                    additionalProperties = false,
                ),
                outputSchema = baseOutputSchema.copy(
                    properties = baseOutputSchema.properties + mapOf(
                        "data" to McpJsonSchemaProperty(
                            type = "object",
                            properties = mapOf(
                                "device" to deviceProperty,
                                "file" to McpJsonSchemaProperty(type = "string", pattern = "^/.+\\.xml$"),
                            ),
                            required = listOf("device", "file"),
                            additionalProperties = false,
                        )
                    )
                ),
            ),
            McpToolDefinition(
                name = "start_app",
                description = "Start app on target device using default main activity. Use when app must be brought to foreground quickly. Avoid when explicit non-default activity is required. Side effects: launches app main activity and changes app foreground state.",
                inputSchema = McpJsonSchemaObject(
                    properties = mapOf(
                        "projectDir" to projectDirProperty,
                        "serial" to serialProperty,
                        "packageName" to McpJsonSchemaProperty(
                            type = "string",
                            description = "Optional package name. If absent, uses current Jugg package name.",
                            pattern = "^[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]*)+$",
                            examples = listOf("com.example.app"),
                        )
                    ),
                    required = listOf("projectDir"),
                    additionalProperties = false,
                ),
                outputSchema = baseOutputSchema.copy(
                    properties = baseOutputSchema.properties + mapOf(
                        "data" to McpJsonSchemaProperty(
                            type = "object",
                            properties = mapOf(
                                "device" to deviceProperty,
                                "packageName" to McpJsonSchemaProperty(type = "string"),
                                "activity" to McpJsonSchemaProperty(type = "string"),
                                "component" to McpJsonSchemaProperty(type = "string"),
                            ),
                            required = listOf("device", "packageName", "activity", "component"),
                            additionalProperties = false,
                        )
                    )
                ),
            ),
            McpToolDefinition(
                name = "start_activity",
                description = "Start a specific activity on target device, optionally with explicit package and activity. Use when precise activity navigation is required before interaction. Avoid when default app entry is sufficient (use start_app). Side effects: launches specified activity and changes app foreground state.",
                inputSchema = McpJsonSchemaObject(
                    properties = mapOf(
                        "projectDir" to projectDirProperty,
                        "serial" to serialProperty,
                        "packageName" to McpJsonSchemaProperty(
                            type = "string",
                            description = "Optional package name. If absent, uses current Jugg package name.",
                            pattern = "^[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]*)+$",
                            examples = listOf("com.example.app"),
                        ),
                        "activity" to McpJsonSchemaProperty(
                            type = "string",
                            description = "Optional activity name. Supports short form (.MainActivity) or full class name.",
                            pattern = "^\\.?[A-Za-z_][A-Za-z0-9_$.]*$",
                            examples = listOf(".MainActivity", "com.example.app.MainActivity"),
                        ),
                        "action" to McpJsonSchemaProperty(
                            type = "string",
                            description = "Optional intent action for am start -a.",
                            examples = listOf("android.intent.action.VIEW"),
                        ),
                        "categories" to McpJsonSchemaProperty(
                            type = "array",
                            description = "Optional intent categories for am start -c.",
                            items = McpJsonSchemaProperty(type = "string"),
                        ),
                        "data" to McpJsonSchemaProperty(
                            type = "string",
                            description = "Optional intent data URI for am start -d.",
                            examples = listOf("app://detail/123", "https://example.com"),
                        ),
                        "mimeType" to McpJsonSchemaProperty(
                            type = "string",
                            description = "Optional MIME type for am start -t.",
                            examples = listOf("text/plain", "image/*"),
                        ),
                        "flags" to McpJsonSchemaProperty(
                            type = "array",
                            description = "Optional intent flags for am start -f. Accepts numeric or symbolic values as strings.",
                            items = McpJsonSchemaProperty(type = "string"),
                        ),
                        "extras" to McpJsonSchemaProperty(
                            type = "object",
                            description = "Optional extras object. Supports scalar values (string/number/boolean) and array of string/number values.",
                            additionalProperties = true,
                        ),
                        "user" to McpJsonSchemaProperty(
                            type = "number",
                            description = "Optional android user id for am start --user.",
                            minimum = 0.0,
                        )
                    ),
                    required = listOf("projectDir"),
                    additionalProperties = false,
                ),
                outputSchema = baseOutputSchema.copy(
                    properties = baseOutputSchema.properties + mapOf(
                        "data" to McpJsonSchemaProperty(
                            type = "object",
                            properties = mapOf(
                                "device" to deviceProperty,
                                "packageName" to McpJsonSchemaProperty(type = "string"),
                                "activity" to McpJsonSchemaProperty(type = "string"),
                                "component" to McpJsonSchemaProperty(type = "string"),
                            ),
                            required = listOf("device", "packageName", "activity", "component"),
                            additionalProperties = false,
                        )
                    )
                ),
            ),
            McpToolDefinition(
                name = "tap",
                description = "Tap a screen coordinate on target device. Use when scripted interaction at known coordinates is required. Avoid when coordinates are unknown; inspect with screenshot or layout_dump first. Side effects: injects input event into device UI.",
                inputSchema = McpJsonSchemaObject(
                    properties = mapOf(
                        "projectDir" to projectDirProperty,
                        "serial" to serialProperty,
                        "x" to McpJsonSchemaProperty(
                            type = "number",
                            description = "X coordinate in device screen space.",
                            minimum = 0.0,
                            examples = listOf(200),
                        ),
                        "y" to McpJsonSchemaProperty(
                            type = "number",
                            description = "Y coordinate in device screen space.",
                            minimum = 0.0,
                            examples = listOf(400),
                        ),
                    ),
                    required = listOf("projectDir", "x", "y"),
                    additionalProperties = false,
                ),
                outputSchema = baseOutputSchema.copy(
                    properties = baseOutputSchema.properties + mapOf(
                        "data" to McpJsonSchemaProperty(
                            type = "object",
                            properties = mapOf(
                                "device" to deviceProperty,
                                "x" to McpJsonSchemaProperty(type = "number", minimum = 0.0),
                                "y" to McpJsonSchemaProperty(type = "number", minimum = 0.0),
                            ),
                            required = listOf("device", "x", "y"),
                            additionalProperties = false,
                        )
                    )
                ),
            )
        )
    }

    fun hasTool(toolName: String): Boolean {
        return listTools().any { it.name == toolName }
    }

    fun getToolDefinition(toolName: String): McpToolDefinition? {
        return listTools().find { it.name == toolName }
    }
}
