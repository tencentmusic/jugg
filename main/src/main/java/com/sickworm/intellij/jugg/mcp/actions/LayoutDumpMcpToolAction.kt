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
import com.sickworm.intellij.jugg.logger.getInstance
import com.google.gson.JsonParser
import java.io.File
import java.nio.charset.StandardCharsets

/**
 * LayoutDumpMcpToolAction implements MCP tool `layout_dump` and converts request arguments into tool execution and MCP result payloads.
 */
class LayoutDumpMcpToolAction : McpToolAction {
    override val toolName: String = "layout_dump"

    override val definition: McpToolDefinition = McpToolDefinition(
        name = toolName,
        description = "Dump current UI hierarchy from app-side ViewHierarchy server and export JSON artifact. " +
            "Returns inline JSON in `data.content` (no extra file read needed) plus file path in `data.file`. " +
            "Optional `rootLayout` parameter: pass a node `id` value from a previous layout_dump " +
            "(e.g. \"com.example:id/content\") to dump only that subtree; omit for full hierarchy. " +
            "By default GONE nodes are excluded; set `isIncludeGone=true` to include them for diagnostics. " +
            "Includes INVISIBLE views (with visibility field). Server-side pruning: MAX_DEPTH=60, MAX_NODE_COUNT=5000; " +
            "if exceeded, root has \"truncated\":true and truncated nodes carry tag " +
            "\"truncated:node_limit\" or \"truncated:depth_limit\". " +
            "Root JSON: {windows:[{windowType, title, root:<node>}], truncated}. " +
            "**Compressed output**: default/empty fields are omitted to reduce payload size. " +
            "className uses simple class name only (package stripped). " +
            "id strips package prefix before slash (e.g. \"com.example:id/btn\" -> \"btn\"). " +
            "bounds and padding use compact array format [left,top,right,bottom]. " +
            "Omitted when default: id/text/contentDesc/tag (when \"\"), " +
            "visibility (when \"visible\"), alpha (when 1.0), clickable (when false), " +
            "enabled (when true), padding (when all zero), children/composeNodes (when empty). " +
            "Node fields: {className, id?, text?, contentDesc?, tag?, " +
            "bounds:[l,t,r,b], visibility?, " +
            "alpha?, clickable?, enabled?, " +
            "padding?:[l,t,r,b], children?:[], composeNodes?:[]}.",
        inputSchema = McpJsonSchemaObject(
            properties = mapOf(
                "projectDir" to McpToolSchemas.projectDirProperty,
                "rootLayout" to McpJsonSchemaProperty(
                    type = "string",
                    description = "Optional node id to dump only that subtree. Pass the `id` value from a previous layout_dump node (e.g. \"com.example:id/content\"). When omitted, dumps the full hierarchy.",
                ),
                "isIncludeGone" to McpJsonSchemaProperty(
                    type = "boolean",
                    description = "When true, include GONE nodes in the output for diagnostics. Default is false (GONE nodes are excluded to reduce payload size).",
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
                        "file" to McpJsonSchemaProperty(type = "string", pattern = "^/.+\\.json$"),
                        "content" to McpJsonSchemaProperty(type = "object", description = "Inline layout hierarchy JSON data"),
                    ),
                    required = listOf("file", "content"),
                    additionalProperties = false,
                )
            )
        ),
    )

    override fun execute(arguments: Map<String, Any?>, runtime: IMcpRuntime): McpToolResult {
        val rootLayout = arguments["rootLayout"] as? String
        val isIncludeGone = arguments["isIncludeGone"] as? Boolean ?: false
        return layoutDumpAction(runtime, rootLayout, isIncludeGone)
    }

    private fun layoutDumpAction(runtime: IMcpRuntime, rootLayout: String? = null, isIncludeGone: Boolean = false): McpToolResult {
        val logger = runtime.logger.getInstance("LayoutDumpMcpToolAction")
        logger.debug("layout_dump start")
        val selected = resolveOnlineDevice(runtime)
            ?: run {
                logger.warn("layout_dump failed: no online device")
                return noDeviceResult("layout_dump")
            }
        val adb = selected.adb
        val toolDir = ensureToolDir(runtime, "layout_dump")
            ?: run {
                logger.warn("layout_dump failed: unable to prepare artifact directory")
                return McpToolResult.internalErrorResult("layout_dump", "failed to prepare artifact directory")
            }
        val packageName = resolvePackageName(runtime)
            ?: run {
                logger.warn("layout_dump failed: package name is empty")
                return McpToolResult.internalErrorResult("layout_dump", "failed to resolve package name for ViewHierarchy server")
            }

        val jsonFileName = "layout_${System.currentTimeMillis()}.json"
        val localJsonFile = File(toolDir, jsonFileName)
        logger.debug("layout_dump device=${adb.serial}, package=$packageName, output=${localJsonFile.absolutePath}")

        return try {
            val client = ViewHierarchyClient(adb, packageName)
            val excludeGone = !isIncludeGone
            val dumpResult = client.dumpLayout(rootLayout, excludeGone)
                ?: return McpToolResult.internalErrorResult(
                    "layout_dump",
                    "ViewHierarchy server is unavailable or returned invalid response"
                ).also {
                    logger.warn("layout_dump failed: ViewHierarchy server unavailable for package=$packageName")
                }
            val payloadJson = dumpResult.payloadJson
            val remoteFilePath = dumpResult.remoteFilePath
            if (!payloadJson.isNullOrBlank()) {
                localJsonFile.writeText(payloadJson, StandardCharsets.UTF_8)
                logger.debug("layout_dump wrote inline payload to ${localJsonFile.absolutePath}")
            } else if (!remoteFilePath.isNullOrBlank()) {
                adb.pull(remoteFilePath, localJsonFile)
                logger.debug("layout_dump pulled remote file from $remoteFilePath")
            }
            if (!localJsonFile.exists() || localJsonFile.length() <= 0) {
                return McpToolResult.internalErrorResult("layout_dump", "failed to fetch layout dump from ViewHierarchy server")
                    .also { logger.warn("layout_dump failed: output file missing or empty, path=${localJsonFile.absolutePath}") }
            }
            logger.info("layout_dump success: ${localJsonFile.absolutePath}, size=${localJsonFile.length()}")
            val jsonContent = localJsonFile.readText(StandardCharsets.UTF_8)
            McpToolResult(
                status = McpToolStatus.OK,
                message = "layout_dump executed successfully.",
                data = mapOf(
                    "file" to localJsonFile.absolutePath,
                    "content" to JsonParser.parseString(jsonContent),
                ),
                artifacts = listOf(McpArtifact(type = "json", path = localJsonFile.absolutePath)),
                errorCode = null,
            )
        } catch (e: Exception) {
            logger.warn("layout_dump failed with exception: ${e.message}", e)
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
            runtime.deployTargetManager.getPackageName().takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            null
        }
    }
}
