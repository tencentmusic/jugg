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
 * TapMcpToolAction implements MCP tool `tap` and converts request arguments into tool execution and MCP result payloads.
 */
class TapMcpToolAction : McpToolAction {
    override val toolName: String = "tap"

    override val definition: McpToolDefinition = McpToolDefinition(
        name = toolName,
        description = "Tap a screen coordinate on target device.",
        inputSchema = McpJsonSchemaObject(
            properties = mapOf(
                "projectDir" to McpToolSchemas.projectDirProperty,
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
        outputSchema = McpToolSchemas.baseOutputSchema.copy(
            properties = McpToolSchemas.baseOutputSchema.properties + mapOf(
                "data" to McpJsonSchemaProperty(
                    type = "object",
                    properties = mapOf(
                        "x" to McpJsonSchemaProperty(type = "number", minimum = 0.0),
                        "y" to McpJsonSchemaProperty(type = "number", minimum = 0.0),
                    ),
                    required = listOf("x", "y"),
                    additionalProperties = false,
                )
            )
        ),
    )

    override fun execute(arguments: Map<String, Any?>, runtime: IMcpRuntime): McpToolResult {
        return tapAction(
            runtime,
            x = (arguments["x"] as? Number)?.toInt(),
            y = (arguments["y"] as? Number)?.toInt(),
        )
    }

    private fun tapAction(runtime: IMcpRuntime, x: Int?, y: Int?): McpToolResult {
        val selected = resolveOnlineDevice(runtime)
            ?: return noDeviceResult("tap")
        val adb = selected.adb

        if (x == null || y == null) {
            return McpToolResult(
                status = McpToolStatus.ERROR,
                message = "tap failed. Reason: x and y are required.",
                data = emptyMap<String, Any>(),
                artifacts = emptyList(),
                errorCode = McpErrorCode.MCP_INVALID_PARAMS,
            )
        }

        return try {
            adb.execAdbShellCmd("input tap $x $y")
            McpToolResult(
                status = McpToolStatus.OK,
                message = "tap executed successfully.",
                data = mapOf(
                    "x" to x,
                    "y" to y,
                ),
                artifacts = emptyList(),
                errorCode = null,
            )
        } catch (e: Exception) {
            McpToolResult.internalErrorResult("tap", e.message ?: "unknown error")
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
