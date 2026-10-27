package com.sickworm.intellij.jugg.ai.mcp.actions

import com.sickworm.intellij.jugg.ai.mcp.*
import com.sickworm.intellij.jugg.ide.bean.InvalidSettingValue
import com.sickworm.intellij.jugg.ide.bean.JuggSettingsEditor
import com.sickworm.intellij.jugg.ide.bean.SettingConfirmationRequired
import com.sickworm.intellij.jugg.logger.JuggLogger
import com.sickworm.intellij.jugg.platform.PlatformApi
import com.sickworm.intellij.jugg.project.runtime.JuggGlobalPathManager

/** Project-independent access to the same global settings edited by the control panel. */
class SettingsMcpToolAction : McpToolAction, GlobalMcpToolAction {
    override val toolName = McpToolActionRegistry.ToolNames.SETTINGS
    override val definition = McpToolDefinition(toolName, "List, read, set, or reset global Jugg settings.",
        McpJsonSchemaObject(properties = mapOf(
            "action" to McpJsonSchemaProperty("string", `enum` = listOf("list", "get", "set", "reset")),
            "key" to McpJsonSchemaProperty("string", description = "Exact camelCase setting property name."),
            "value" to McpJsonSchemaProperty("string", description = "Value parsed according to the setting type; JSON text for structured settings."),
            "confirm" to McpJsonSchemaProperty("boolean", description = "Explicitly accept the shared IDE confirmation."),
        ), required = listOf("action"), additionalProperties = false))

    override fun execute(arguments: Map<String, Any?>, runtime: IMcpRuntime): McpToolResult =
        throw UnsupportedOperationException("Settings requires global execution.")

    override fun executeGlobal(toolRegistry: McpToolRegistry, arguments: Map<String, Any?>): McpToolResult {
        return try {
            val result = apply(arguments) + mapOf("scope" to "global",
                "settingsPath" to JuggGlobalPathManager.settingsFile.absolutePath,
                "runtimeType" to PlatformApi.getRuntimeInfo().runtimeType,
                "runtimeVersion" to PlatformApi.getRuntimeInfo().runtimeVersion)
            McpToolResult(McpToolStatus.OK, "settings executed successfully.", result)
        } catch (e: SettingConfirmationRequired) {
            McpToolResult(McpToolStatus.ERROR, e.confirmation.message, mapOf("key" to e.key,
                "confirmation" to e.confirmation, "requiredArgument" to mapOf("confirm" to true)), errorCode = McpErrorCode.CONFIRMATION_REQUIRED)
        } catch (e: InvalidSettingValue) {
            McpToolResult(McpToolStatus.ERROR, e.message.orEmpty(), mapOf("key" to e.key,
                "expectedType" to e.expectedType, "fieldPath" to e.fieldPath), errorCode = McpErrorCode.INVALID_PARAMS)
        } catch (e: IllegalArgumentException) {
            McpToolResult(McpToolStatus.ERROR, e.message ?: "Invalid settings arguments.", errorCode = McpErrorCode.INVALID_PARAMS)
        } catch (e: Exception) {
            JuggLogger.getGlobalLogger("Settings").warn("Failed to access global settings", e)
            McpToolResult(McpToolStatus.ERROR, "Could not read or persist global settings; inspect the runtime log.", errorCode = McpErrorCode.INTERNAL_ERROR)
        }
    }

    private fun apply(arguments: Map<String, Any?>): Map<String, Any?> {
        val action = arguments["action"] as? String ?: throw IllegalArgumentException("action is required.")
        val allowed = when (action) {
            "list" -> setOf("action")
            "get" -> setOf("action", "key")
            "set" -> setOf("action", "key", "value", "confirm")
            "reset" -> setOf("action", "key", "confirm")
            else -> throw IllegalArgumentException("Unknown settings action.")
        }
        require(arguments.keys.all { it in allowed }) { "Unexpected settings arguments." }
        val editor = JuggSettingsEditor()
        if (action == "list") return editor.list()
        val key = arguments["key"] as? String ?: throw IllegalArgumentException("key is required.")
        val confirm = if (arguments.containsKey("confirm")) arguments["confirm"] as? Boolean
            ?: throw IllegalArgumentException("confirm must be a boolean.") else false
        return when (action) {
            "get" -> editor.get(key)
            "reset" -> editor.reset(key, confirm)
            else -> editor.set(key, arguments["value"] as? String ?: throw IllegalArgumentException("value must be a string."), confirm)
        }
    }
}
