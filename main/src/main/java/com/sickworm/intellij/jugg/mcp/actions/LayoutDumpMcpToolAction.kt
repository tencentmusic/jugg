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
import com.sickworm.intellij.jugg.mcp.viewhierarchy.ViewHierarchyClient
import com.sickworm.intellij.jugg.platform.PlatformApi
import com.sickworm.intellij.jugg.project.JuggPathManager
import java.io.File
import java.nio.charset.StandardCharsets

/**
 * LayoutDumpMcpToolAction implements MCP tool `layout_dump` and converts request arguments into tool execution and MCP result payloads.
 */
class LayoutDumpMcpToolAction : McpToolAction {
    override val toolName: String = "layout_dump"

    override val definition: McpToolDefinition = McpToolDefinition(
        name = toolName,
        description = "Dump current UI hierarchy from app-side ViewHierarchy server and export JSON artifact.",
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
                        "file" to McpJsonSchemaProperty(type = "string", pattern = "^/.+\\.json$"),
                    ),
                    required = listOf("file"),
                    additionalProperties = false,
                )
            )
        ),
    )

    override fun execute(arguments: Map<String, Any?>, runtime: IMcpRuntime): McpToolResult {
        return layoutDumpAction(runtime)
    }

    private fun layoutDumpAction(runtime: IMcpRuntime): McpToolResult {
        val selected = resolveOnlineDevice(runtime)
            ?: return noDeviceResult("layout_dump")
        val adb = selected.adb
        val toolDir = ensureToolDir(runtime, "layout_dump")
            ?: return McpToolResult.internalErrorResult("layout_dump", "failed to prepare artifact directory")
        val packageName = resolvePackageName(runtime)
            ?: return McpToolResult.internalErrorResult("layout_dump", "failed to resolve package name for ViewHierarchy server")

        val jsonFileName = "layout_${System.currentTimeMillis()}.json"
        val localJsonFile = File(toolDir, jsonFileName)

        return try {
            val client = ViewHierarchyClient(adb, packageName)
            val dumpResult = client.dumpLayout()
                ?: return McpToolResult.internalErrorResult(
                    "layout_dump",
                    "ViewHierarchy server is unavailable or returned invalid response"
                )
            val payloadJson = dumpResult.payloadJson
            val remoteFilePath = dumpResult.remoteFilePath
            if (!payloadJson.isNullOrBlank()) {
                localJsonFile.writeText(payloadJson, StandardCharsets.UTF_8)
            } else if (!remoteFilePath.isNullOrBlank()) {
                adb.pull(remoteFilePath, localJsonFile)
            }
            if (!localJsonFile.exists() || localJsonFile.length() <= 0) {
                return McpToolResult.internalErrorResult("layout_dump", "failed to fetch layout dump from ViewHierarchy server")
            }
            McpToolResult(
                status = McpToolStatus.OK,
                message = "layout_dump executed successfully.",
                data = mapOf(
                    "file" to localJsonFile.absolutePath,
                ),
                artifacts = listOf(McpArtifact(type = "json", path = localJsonFile.absolutePath)),
                errorCode = null,
            )
        } catch (e: Exception) {
            McpToolResult.internalErrorResult("layout_dump", e.message ?: "unknown error")
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

    private fun ensureToolDir(runtime: IMcpRuntime, toolName: String): File? {
        val projectDir = runtime.project.basePath ?: return null
        val dir = File(JuggPathManager(File(projectDir)).mcpFetchDir, toolName)
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

    private fun resolvePackageName(runtime: IMcpRuntime): String? {
        return try {
            runtime.deployTargetManager.getPackageName()?.takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            null
        }
    }
}
