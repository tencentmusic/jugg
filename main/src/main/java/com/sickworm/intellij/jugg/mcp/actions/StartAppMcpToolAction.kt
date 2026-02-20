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

class StartAppMcpToolAction : McpToolAction {
    override val toolName: String = "start_app"

    override val definition: McpToolDefinition = McpToolDefinition(
        name = toolName,
        description = "Start app on target device, optionally with explicit package and activity. Use when app must be brought to foreground before interaction. Avoid when app is already in expected state and restart is unnecessary. Side effects: launches activity and changes app foreground state.",
        inputSchema = McpJsonSchemaObject(
            properties = mapOf(
                "projectDir" to McpToolSchemas.projectDirProperty,
                "packageName" to McpJsonSchemaProperty(
                    type = "string",
                    description = "Optional package name. If absent, uses current Jugg package name.",
                    pattern = "^[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]*)+$",
                    examples = listOf("com.example.app"),
                ),
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
                    ),
                    required = listOf("packageName", "activity", "component"),
                    additionalProperties = false,
                )
            )
        ),
    )

    override fun execute(arguments: Map<String, Any?>, runtime: IMcpRuntime): McpToolResult {
        return startAppAction(
            runtime,
            packageName = arguments["packageName"] as? String,
        )
    }

    private fun startAppAction(runtime: IMcpRuntime, packageName: String?): McpToolResult {
        val selected = resolveOnlineDevice(runtime)
            ?: return noDeviceResult("start_app")
        val adb = selected.adb

        return try {
            val resolvedPackageName = packageName ?: runtime.deployTargetManager.getPackageNameOrNull()
                ?: return McpToolResult.internalErrorResult("start_app", "packageName is required when deploy target is unavailable")
            val component = "$resolvedPackageName/.MainActivity"
            adb.execAdbShellCmd("am start -n $component")

            McpToolResult(
                status = McpToolStatus.OK,
                message = "start_app executed successfully.",
                data = mapOf(
                    "packageName" to resolvedPackageName,
                    "activity" to ".MainActivity",
                    "component" to component,
                ),
                artifacts = emptyList(),
                errorCode = null,
            )
        } catch (e: Exception) {
            McpToolResult.internalErrorResult("start_app", e.message ?: "unknown error")
        }
    }

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

    private fun noDeviceResult(toolName: String): McpToolResult {
        return McpToolResult(
            status = McpToolStatus.ERROR,
            message = "$toolName failed. Reason: No connected device is available.",
            data = emptyMap<String, Any>(),
            artifacts = emptyList(),
            errorCode = McpErrorCode.MCP_NO_DEVICE,
        )
    }
}
