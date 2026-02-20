package com.sickworm.intellij.jugg.mcp.actions

import com.sickworm.intellij.jugg.deploy.IDeviceAdb
import com.sickworm.intellij.jugg.mcp.DeviceSelectionResolver
import com.sickworm.intellij.jugg.mcp.DeviceSelectionResult
import com.sickworm.intellij.jugg.mcp.IMcpRuntime
import com.sickworm.intellij.jugg.mcp.McpArtifact
import com.sickworm.intellij.jugg.mcp.McpErrorCode
import com.sickworm.intellij.jugg.mcp.McpJsonSchemaObject
import com.sickworm.intellij.jugg.mcp.McpJsonSchemaProperty
import com.sickworm.intellij.jugg.mcp.McpToolDefinition
import com.sickworm.intellij.jugg.mcp.McpToolResult
import com.sickworm.intellij.jugg.mcp.McpToolStatus
import com.sickworm.intellij.jugg.platform.PlatformApi
import java.io.File

class ScreenshotMcpToolAction : McpToolAction {
    override val toolName: String = "screenshot"

    override val definition: McpToolDefinition = McpToolDefinition(
        name = toolName,
        description = "Capture a screenshot from target device. Use when you need current visual UI state for debugging or coordinate planning. Avoid when UI hierarchy XML is required instead. Side effects: read-only capture, no app state change.",
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
                        "file" to McpJsonSchemaProperty(type = "string", pattern = "^/.+\\.png$"),
                    ),
                    required = listOf("file"),
                    additionalProperties = false,
                )
            )
        ),
    )

    override fun execute(arguments: Map<String, Any?>, runtime: IMcpRuntime): McpToolResult {
        return screenshotAction(runtime)
    }

    private fun screenshotAction(runtime: IMcpRuntime): McpToolResult {
        val selected = resolveOnlineDevice(runtime)
            ?: return noDeviceResult("screenshot")
        val adb = selected.adb
        val toolDir = ensureToolDir(runtime, "screenshot")
            ?: return McpToolResult.internalErrorResult("screenshot", "failed to prepare artifact directory")

        val fileName = "screenshot_${System.currentTimeMillis()}.png"
        val localFile = File(toolDir, fileName)
        val remoteDir = "/sdcard/Download/jugg_mcp"
        val remoteFile = "$remoteDir/$fileName"

        return try {
            adb.execAdbShellCmd("mkdir -p $remoteDir")
            adb.execAdbShellCmd("screencap -p $remoteFile")
            if (!adb.pull(remoteFile, localFile) || !localFile.exists()) {
                return McpToolResult.internalErrorResult("screenshot", "failed to pull screenshot file")
            }

            McpToolResult(
                status = McpToolStatus.OK,
                message = "screenshot executed successfully.",
                data = mapOf(
                    "file" to localFile.absolutePath,
                ),
                artifacts = listOf(McpArtifact(type = "image", path = localFile.absolutePath)),
                errorCode = null,
            )
        } catch (e: Exception) {
            McpToolResult.internalErrorResult("screenshot", e.message ?: "unknown error")
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

    private fun ensureToolDir(runtime: IMcpRuntime, toolName: String): File? {
        val projectDir = runtime.project.basePath ?: return null
        val dir = File(projectDir, "build/jugg/mcp_fetch/$toolName")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
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
