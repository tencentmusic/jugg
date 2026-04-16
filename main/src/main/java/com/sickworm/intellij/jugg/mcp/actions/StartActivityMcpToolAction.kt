package com.sickworm.intellij.jugg.mcp.actions

import com.sickworm.intellij.jugg.deploy.IDeviceAdb
import com.sickworm.intellij.jugg.mcp.DeviceSelectionResolver
import com.sickworm.intellij.jugg.mcp.DeviceSelectionResult
import com.sickworm.intellij.jugg.mcp.IMcpRuntime
import com.sickworm.intellij.jugg.mcp.McpErrorCode
import com.sickworm.intellij.jugg.mcp.McpJsonSchemaObject
import com.sickworm.intellij.jugg.mcp.McpJsonSchemaProperty
import com.sickworm.intellij.jugg.mcp.McpToolDefinition
import com.sickworm.intellij.jugg.mcp.McpToolResult
import com.sickworm.intellij.jugg.mcp.McpToolStatus
import com.sickworm.intellij.jugg.platform.PlatformApi

/**
 * StartActivityMcpToolAction implements MCP tool `start_activity` and converts request arguments into tool execution and MCP result payloads.
 */
class StartActivityMcpToolAction : McpToolAction {
    override val toolName: String = "start_activity"

    override val definition: McpToolDefinition = McpToolDefinition(
        name = toolName,
        description = "Start specific activity on target device with optional intent fields.",
        inputSchema = McpJsonSchemaObject(
            properties = mapOf(
                "projectDir" to McpToolSchemas.projectDirProperty,
                "packageName" to McpJsonSchemaProperty(
                    type = "string",
                    description = "Optional package name. If absent, uses current Jugg target package.",
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
        outputSchema = McpToolSchemas.baseOutputSchema.copy(
            properties = McpToolSchemas.baseOutputSchema.properties + mapOf(
                "data" to McpJsonSchemaProperty(
                    type = "object",
                    properties = mapOf(
                        "packageName" to McpJsonSchemaProperty(type = "string"),
                        "activity" to McpJsonSchemaProperty(type = "string"),
                        "component" to McpJsonSchemaProperty(type = "string"),
                        "command" to McpJsonSchemaProperty(type = "string"),
                    ),
                    required = listOf("packageName", "activity", "component", "command"),
                    additionalProperties = false,
                )
            )
        ),
    )

    override fun execute(arguments: Map<String, Any?>, runtime: IMcpRuntime): McpToolResult {
        @Suppress("UNCHECKED_CAST")
        val categories = arguments["categories"] as? List<String>
        @Suppress("UNCHECKED_CAST")
        val flags = (arguments["flags"] as? List<*>)?.mapNotNull { it?.toString() }
        @Suppress("UNCHECKED_CAST")
        val extras = arguments["extras"] as? Map<String, Any?>
        return startActivityAction(
            runtime,
            packageName = arguments["packageName"] as? String,
            activity = arguments["activity"] as? String,
            action = arguments["action"] as? String,
            categories = categories,
            data = arguments["data"] as? String,
            mimeType = arguments["mimeType"] as? String,
            flags = flags,
            extras = extras,
            user = (arguments["user"] as? Number)?.toInt(),
        )
    }

    private fun startActivityAction(
        runtime: IMcpRuntime,
        packageName: String?,
        activity: String?,
        action: String?,
        categories: List<String>?,
        data: String?,
        mimeType: String?,
        flags: List<String>?,
        extras: Map<String, Any?>?,
        user: Int?,
    ): McpToolResult {
        val selected = resolveOnlineDevice(runtime)
            ?: return noDeviceResult("start_activity")
        val adb = selected.adb

        return try {
            val resolvedPackageName = packageName ?: runtime.deployTargetManager.getPackageNameOrNull()
                ?: return McpToolResult.internalErrorResult("start_activity", "packageName is required when deploy target is unavailable")
            val activityPart = normalizeActivity(activity, resolvedPackageName)
            val component = "$resolvedPackageName/$activityPart"
            val command = buildStartActivityCommand(
                component = component,
                action = action,
                categories = categories,
                data = data,
                mimeType = mimeType,
                flags = flags,
                extras = extras,
                user = user,
            )
            adb.execAdbShellCmd(command)

            McpToolResult(
                status = McpToolStatus.OK,
                message = "start_activity executed successfully.",
                data = mapOf(
                    "packageName" to resolvedPackageName,
                    "activity" to activityPart,
                    "component" to component,
                    "command" to command,
                ),
                artifacts = emptyList(),
                errorCode = null,
            )
        } catch (e: Exception) {
            McpToolResult.internalErrorResult("start_activity", e.message ?: "unknown error")
        }
    }

    /**
     * SelectedAdb carries adb and messageDetail.
     */
    private data class SelectedAdb(
        val adb: IDeviceAdb,
    )

    private fun resolveOnlineDevice(runtime: IMcpRuntime): SelectedAdb? {
        val selectionResult = DeviceSelectionResolver().resolve(runtime.deployTargetManager)
        if (selectionResult !is DeviceSelectionResult.Selected) {
            return null
        }
        val adb = PlatformApi.toDeviceAdb(selectionResult.device) ?: return null
        if (!adb.isOnline) {
            return null
        }
        return SelectedAdb(adb = adb)
    }

    private fun normalizeActivity(activity: String?, resolvedPackageName: String): String {
        return when {
            activity.isNullOrBlank() -> ".MainActivity"
            activity.startsWith(".") -> activity
            activity.startsWith("$resolvedPackageName.") -> activity.removePrefix(resolvedPackageName)
            activity.contains(".") -> activity
            else -> ".$activity"
        }
    }

    private fun buildStartActivityCommand(
        component: String,
        action: String?,
        categories: List<String>?,
        data: String?,
        mimeType: String?,
        flags: List<String>?,
        extras: Map<String, Any?>?,
        user: Int?,
    ): String {
        val args = mutableListOf("am", "start", "-n", component)
        if (!action.isNullOrBlank()) {
            args += listOf("-a", action)
        }
        categories.orEmpty().forEach { category ->
            if (category.isNotBlank()) {
                args += listOf("-c", category)
            }
        }
        if (!data.isNullOrBlank()) {
            args += listOf("-d", data)
        }
        if (!mimeType.isNullOrBlank()) {
            args += listOf("-t", mimeType)
        }
        flags.orEmpty().forEach { flag ->
            if (flag.isNotBlank()) {
                args += listOf("-f", flag)
            }
        }
        if (user != null) {
            args += listOf("--user", user.toString())
        }

        extras.orEmpty().forEach { (key, value) ->
            when (value) {
                null -> Unit
                is String -> args += listOf("--es", key, value)
                is Boolean -> args += listOf("--ez", key, value.toString())
                is Int -> args += listOf("--ei", key, value.toString())
                is Long -> args += listOf("--el", key, value.toString())
                is Float -> args += listOf("--ef", key, value.toString())
                is Double -> args += listOf("--ef", key, value.toString())
                is Number -> args += listOf("--el", key, value.toLong().toString())
                is List<*> -> {
                    if (value.all { it is String }) {
                        args += listOf("--esa", key, value.joinToString(",") { it as String })
                    } else if (value.all { it is Number }) {
                        args += listOf("--eia", key, value.joinToString(",") { (it as Number).toInt().toString() })
                    }
                }
            }
        }

        return args.joinToString(" ") { quoteShell(it) }
    }

    private fun quoteShell(value: String): String {
        return "'" + value.replace("'", "'\\''") + "'"
    }

    private fun noDeviceResult(toolName: String): McpToolResult {
        return McpToolResult(
            status = McpToolStatus.ERROR,
            message = "$toolName failed. Reason: No connected device is available.",
            data = emptyMap<String, Any>(),
            artifacts = emptyList(),
            errorCode = McpErrorCode.NO_DEVICE,
        )
    }
}
