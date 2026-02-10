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

class LayoutDumpMcpToolAction : McpToolAction {
    override val toolName: String = "layout_dump"

    override val definition: McpToolDefinition = McpToolDefinition(
        name = toolName,
        description = "Dump current UI hierarchy XML from target device. Use when you need structured node info before tap automation. Avoid when visual evidence only is needed. Side effects: read-only dump, no build or app mutation.",
        inputSchema = McpJsonSchemaObject(
            properties = mapOf(
                "projectDir" to McpToolSchemas.projectDirProperty,
                "serial" to McpToolSchemas.serialProperty,
            ),
            required = listOf("projectDir"),
            additionalProperties = false,
        ),
        outputSchema = McpToolSchemas.baseOutputSchema.copy(
            properties = McpToolSchemas.baseOutputSchema.properties + mapOf(
                "data" to McpJsonSchemaProperty(
                    type = "object",
                    properties = mapOf(
                        "device" to McpToolSchemas.deviceProperty,
                        "file" to McpJsonSchemaProperty(type = "string", pattern = "^/.+\\.xml$"),
                    ),
                    required = listOf("device", "file"),
                    additionalProperties = false,
                )
            )
        ),
    )

    override fun execute(arguments: Map<String, Any?>, runtime: IMcpRuntime): McpToolResult {
        return layoutDumpAction(runtime, arguments["serial"] as? String)
    }

    private fun layoutDumpAction(runtime: IMcpRuntime, serial: String?): McpToolResult {
        val selected = resolveOnlineDevice(runtime, serial)
            ?: return noDeviceResult("layout_dump")
        val adb = selected.adb
        val toolDir = ensureToolDir(runtime, "layout_dump")
            ?: return McpToolResult.internalErrorResult("layout_dump", "failed to prepare artifact directory")

        val fileName = "layout_${safeName(adb.serial)}_${System.currentTimeMillis()}.xml"
        val localFile = File(toolDir, fileName)
        val remoteDir = "/sdcard/Download/jugg_mcp"
        val remoteFile = "$remoteDir/$fileName"

        return try {
            adb.execAdbShellCmd("mkdir -p $remoteDir")
            adb.execAdbShellCmd("uiautomator dump $remoteFile")
            if (!adb.pull(remoteFile, localFile) || !localFile.exists()) {
                return McpToolResult.internalErrorResult("layout_dump", "failed to pull layout dump file")
            }

            McpToolResult(
                status = McpToolStatus.OK,
                message = "layout_dump executed successfully. ${selected.messageDetail}",
                data = mapOf(
                    "device" to mapOf(
                        "serial" to adb.serial,
                        "name" to adb.displayName,
                        "isOnline" to adb.isOnline,
                    ),
                    "file" to localFile.absolutePath,
                ),
                artifacts = listOf(McpArtifact(type = "xml", path = localFile.absolutePath)),
                errorCode = null,
            )
        } catch (e: Exception) {
            McpToolResult.internalErrorResult("layout_dump", e.message ?: "unknown error")
        }
    }

    private data class SelectedAdb(
        val adb: IDeviceAdb,
        val messageDetail: String,
    )

    private fun resolveOnlineDevice(runtime: IMcpRuntime, serial: String?): SelectedAdb? {
        val selectionResult = DeviceSelectionResolver().resolve(serial, runtime.deployTargetManager)
        if (selectionResult !is DeviceSelectionResult.Selected) {
            return null
        }
        val adb = PlatformApi.toDeviceAdb(selectionResult.device) ?: return null
        if (!adb.isOnline) {
            return null
        }
        return SelectedAdb(adb = adb, messageDetail = selectionResult.messageDetail)
    }

    private fun ensureToolDir(runtime: IMcpRuntime, toolName: String): File? {
        val projectDir = runtime.project.basePath ?: return null
        val dir = File(projectDir, "build/jugg/mcp_fetch/$toolName")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    private fun safeName(value: String): String {
        return value.replace("[^a-zA-Z0-9._-]".toRegex(), "_")
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
