package com.sickworm.intellij.jugg.mcp.actions

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.sickworm.intellij.jugg.deploy.IDeviceAdb
import com.sickworm.intellij.jugg.logger.getInstance
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
        description = "Dump UI hierarchy from app-side ViewHierarchy server to a local JSON artifact. " +
            "Returns data.file and optional inline data.content. Supports rootLayout subtree dump, isIncludeGone, and inlineMaxKb.",
        inputSchema = McpJsonSchemaObject(
            properties = mapOf(
                "projectDir" to McpToolSchemas.projectDirProperty,
                "rootLayout" to McpJsonSchemaProperty(
                    type = "string",
                    description = "Optional node id to dump only that subtree. Pass the `id` value from previous layout_dump (prefer short id, e.g. \"content\").",
                ),
                "isIncludeGone" to McpJsonSchemaProperty(
                    type = "boolean",
                    description = "When true, include GONE nodes in the output for diagnostics. Default is false.",
                ),
                "inlineMaxKb" to McpJsonSchemaProperty(
                    type = "number",
                    description = "Max inline content size in KB. Default 16, clamped to 4..128.",
                    minimum = 4.0,
                    maximum = 128.0,
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
                        "content" to McpJsonSchemaProperty(type = "object", description = "Inline layout hierarchy JSON data (omitted when oversized)"),
                        "contentBytes" to McpJsonSchemaProperty(type = "number", minimum = 0.0),
                        "inlineOmitted" to McpJsonSchemaProperty(type = "boolean"),
                        "inlineThresholdKb" to McpJsonSchemaProperty(type = "number", minimum = 4.0, maximum = 128.0),
                    ),
                    required = listOf("file", "contentBytes", "inlineOmitted", "inlineThresholdKb"),
                    additionalProperties = false,
                )
            )
        ),
    )

    override fun execute(arguments: Map<String, Any?>, runtime: IMcpRuntime): McpToolResult {
        val rootLayout = arguments["rootLayout"] as? String
        val isIncludeGone = arguments["isIncludeGone"] as? Boolean ?: false
        val inlineMaxKb = (arguments["inlineMaxKb"] as? Number)?.toInt() ?: DEFAULT_INLINE_THRESHOLD_KB
        return layoutDumpAction(runtime, rootLayout, isIncludeGone, inlineMaxKb)
    }

    private fun layoutDumpAction(
        runtime: IMcpRuntime,
        rootLayout: String? = null,
        isIncludeGone: Boolean = false,
        inlineMaxKb: Int,
    ): McpToolResult {
        val logger = runtime.logger.getInstance("LayoutDumpMcpToolAction")
        val selected = resolveOnlineDevice(runtime)
            ?: run {
                logger.warn("layout_dump failed: no online device")
                return noDeviceResult("layout_dump")
            }
        val preWaitResult = McpAppReadyGuard.waitBeforeRuntimeObserve(runtime, toolName)
        if (!preWaitResult.isReady) {
            logger.warn("layout_dump failed: app not ready after pre-check retries")
            return preWaitResult.errorResult ?: McpToolResult.internalErrorResult("layout_dump", "app is not ready")
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

        return McpAppReadyGuard.executeWithRetryIfPreWaited(preWaitResult) {
            try {
                val client = ViewHierarchyClient(adb, packageName)
                val excludeGone = !isIncludeGone
                val dumpResult = client.dumpLayout(rootLayout, excludeGone)
                    ?: return@executeWithRetryIfPreWaited McpToolResult.internalErrorResult(
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
                    return@executeWithRetryIfPreWaited McpToolResult.internalErrorResult(
                        "layout_dump",
                        "failed to fetch layout dump from ViewHierarchy server"
                    )
                }

                val jsonContent = localJsonFile.readText(StandardCharsets.UTF_8)
                val jsonElement = JsonParser.parseString(jsonContent)
                val summary = buildSummaryMessage(jsonElement)
                val contentBytes = jsonContent.toByteArray(StandardCharsets.UTF_8).size
                val thresholdKb = clampInlineMaxKb(inlineMaxKb)
                val inlineOmitted = contentBytes > thresholdKb * 1024
                val data = mutableMapOf<String, Any>(
                    "file" to localJsonFile.absolutePath,
                    "contentBytes" to contentBytes,
                    "inlineOmitted" to inlineOmitted,
                    "inlineThresholdKb" to thresholdKb,
                )
                if (!inlineOmitted) {
                    data["content"] = jsonElement
                }

                McpToolResult(
                    status = McpToolStatus.OK,
                    message = summary,
                    data = data,
                    artifacts = listOf(McpArtifact(type = "json", path = localJsonFile.absolutePath)),
                    errorCode = null,
                )
            } catch (e: Exception) {
                logger.warn("layout_dump failed with exception: ${e.message}", e)
                McpToolResult.internalErrorResult("layout_dump", e.message ?: "unknown error")
            }
        }
    }

    private fun buildSummaryMessage(element: JsonElement): String {
        val root = element.asJsonObjectOrNull() ?: return "0 windows (top: unknown), 0 nodes, not truncated"
        val windows = root.getAsJsonArrayOrEmpty("windows")
        val windowCount = windows.size()
        val topTitle = windows.firstOrNullObject()?.get("title")?.asStringOrNull()?.takeIf { it.isNotBlank() } ?: "unknown"
        val nodeCount = countNodes(windows)
        val truncated = root.get("truncated")?.asBooleanOrFalse() ?: false
        val truncatedText = if (truncated) "truncated" else "not truncated"
        return "$windowCount windows (top: $topTitle), $nodeCount nodes, $truncatedText"
    }

    private fun countNodes(windows: JsonArray): Int {
        var total = 0
        windows.forEach { windowElement ->
            val rootNode = windowElement.asJsonObjectOrNull()?.get("root")?.asJsonObjectOrNull() ?: return@forEach
            total += countNodeRecursive(rootNode)
        }
        return total
    }

    private fun countNodeRecursive(node: JsonObject): Int {
        var count = 1
        val children = node.getAsJsonArrayOrEmpty("children")
        for (child in children) {
            if (count >= MAX_COUNT_VISIT) {
                return count
            }
            val childObj = child.asJsonObjectOrNull() ?: continue
            count += countNodeRecursive(childObj)
        }
        return count
    }

    private fun clampInlineMaxKb(value: Int): Int {
        return value.coerceIn(MIN_INLINE_THRESHOLD_KB, MAX_INLINE_THRESHOLD_KB)
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

    private fun JsonElement.asJsonObjectOrNull(): JsonObject? {
        return if (isJsonObject) asJsonObject else null
    }

    private fun JsonElement.asBooleanOrFalse(): Boolean {
        return runCatching { asBoolean }.getOrDefault(false)
    }

    private fun JsonElement.asStringOrNull(): String? {
        return runCatching { asString }.getOrNull()
    }

    private fun JsonObject.getAsJsonArrayOrEmpty(key: String): JsonArray {
        val value = get(key)
        return if (value != null && value.isJsonArray) value.asJsonArray else JsonArray()
    }

    private fun JsonArray.firstOrNullObject(): JsonObject? {
        if (size() == 0) {
            return null
        }
        return get(0).asJsonObjectOrNull()
    }

    companion object {
        private const val MIN_INLINE_THRESHOLD_KB = 4
        private const val DEFAULT_INLINE_THRESHOLD_KB = 16
        private const val MAX_INLINE_THRESHOLD_KB = 128
        private const val MAX_COUNT_VISIT = 10_000
    }
}
