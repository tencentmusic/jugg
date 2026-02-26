package com.sickworm.intellij.jugg.mcp.actions

import com.sickworm.intellij.jugg.mcp.IMcpRuntime
import com.sickworm.intellij.jugg.mcp.McpJsonSchemaObject
import com.sickworm.intellij.jugg.mcp.McpJsonSchemaProperty
import com.sickworm.intellij.jugg.mcp.McpToolDefinition
import com.sickworm.intellij.jugg.mcp.McpToolResult
import com.sickworm.intellij.jugg.mcp.McpToolStatus
import com.sickworm.intellij.jugg.platform.PlatformApi

/**
 * DeviceListMcpToolAction implements MCP tool `device_list` and converts request arguments into tool execution and MCP result payloads.
 */
class DeviceListMcpToolAction : McpToolAction {
    override val toolName: String = "device_list"

    override val definition: McpToolDefinition = McpToolDefinition(
        name = toolName,
        description = "List currently connected Android devices and selected target.",
        inputSchema = McpJsonSchemaObject(
            properties = mapOf(
                "projectDir" to McpToolSchemas.projectDirProperty,
            ),
            required = listOf("projectDir"),
            additionalProperties = false,
        ),
        outputSchema = McpToolSchemas.baseOutputSchema.copy(
            properties = McpToolSchemas.baseOutputSchema.properties + mapOf(
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
    )

    override fun execute(arguments: Map<String, Any?>, runtime: IMcpRuntime): McpToolResult {
        return deviceListAction(runtime)
    }

    private fun deviceListAction(runtime: IMcpRuntime): McpToolResult {
        val selectedSerials = runtime.deployTargetManager.getSelectedDevices()
            .mapNotNull { PlatformApi.toDeviceAdb(it)?.serial }
            .toSet()
        val connectedDevices = runtime.deployTargetManager.getConnectedDevices()
            .mapNotNull { PlatformApi.toDeviceAdb(it) }

        val devices = connectedDevices.map { adb ->
            mapOf(
                "serial" to adb.serial,
                "name" to adb.displayName,
                "isOnline" to adb.isOnline,
                "api" to adb.api,
                "isSelected" to selectedSerials.contains(adb.serial),
            )
        }

        return McpToolResult(
            status = McpToolStatus.OK,
            message = "device_list executed successfully.",
            data = mapOf("devices" to devices),
            artifacts = emptyList(),
            errorCode = null,
        )
    }
}
