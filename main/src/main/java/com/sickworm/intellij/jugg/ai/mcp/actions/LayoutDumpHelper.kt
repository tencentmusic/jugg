package com.sickworm.intellij.jugg.ai.mcp.actions

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.sickworm.intellij.jugg.logger.getInstance
import com.sickworm.intellij.jugg.ai.mcp.IMcpRuntime
import com.sickworm.intellij.jugg.ai.mcp.McpArtifact
import com.sickworm.intellij.jugg.ai.mcp.McpToolResult
import com.sickworm.intellij.jugg.ai.mcp.McpToolStatus
import com.sickworm.intellij.jugg.ai.mcp.viewhierarchy.ViewHierarchyClient
import com.sickworm.intellij.jugg.project.runtime.JuggPathManager
import java.io.File
import java.nio.charset.StandardCharsets

/**
 * Shared helper that encapsulates the core layout_dump logic: device resolution,
 * app-ready guard, ViewHierarchyClient invocation, px→dp conversion, and file writing.
 *
 * Used by both LayoutDumpMcpToolAction (exposed as MCP tool) and
 * FigmaLayoutVerifyMcpToolAction (calls dump internally without exposing it to the LLM).
 */
internal object LayoutDumpHelper {

    /**
     * Execute a layout dump and return an MCP result.
     * On success, result.data["file"] contains the absolute path to the selected JSON artifact.
     */
    fun dump(
        runtime: IMcpRuntime,
        callerToolName: String,
        rootLayout: String? = null,
        isIncludeGone: Boolean = false,
        isAllWindows: Boolean = false,
        targetDeviceSerial: String? = null,
        mode: String,
    ): McpToolResult {
        return when (val output = dumpInternal(
            runtime, callerToolName, rootLayout, isIncludeGone, isAllWindows, targetDeviceSerial, mode,
        )) {
            is DumpInternalResult.Failure -> output.result
            is DumpInternalResult.Success -> output.toPublicResult()
        }
    }

    /**
     * Executes layout dump and keeps the structured JSON file available for internal tools.
     * Internal consumers default to UI mode so they always receive the captured tree.
     */
    fun dumpInternal(
        runtime: IMcpRuntime,
        callerToolName: String,
        rootLayout: String? = null,
        isIncludeGone: Boolean = false,
        isAllWindows: Boolean = false,
        targetDeviceSerial: String? = null,
        mode: String = "ui",
    ): DumpInternalResult {
        val logger = runtime.logger.getInstance("LayoutDumpHelper")

        val selected = when (val result = resolveMcpSingleDevice(runtime, callerToolName, targetDeviceSerial)) {
            is McpSingleDeviceResult.Selected -> result
            is McpSingleDeviceResult.Failure -> {
                logger.warn("$callerToolName: device selection failed")
                return DumpInternalResult.Failure(result.result)
            }
        }

        val preWaitResult = McpAppReadyGuard.waitBeforeRuntimeObserve(runtime, callerToolName, targetDeviceSerial)
        if (!preWaitResult.isReady) {
            logger.warn("$callerToolName: app not ready after pre-check retries")
            return DumpInternalResult.Failure(preWaitResult.errorResult
                ?: McpToolResult.internalErrorResult(callerToolName, "app is not ready")
            )
        }

        val toolDir = ensureToolDir(runtime, "layout-dump")
            ?: run {
                logger.warn("$callerToolName: unable to prepare artifact directory")
                return DumpInternalResult.Failure(
                    McpToolResult.internalErrorResult(callerToolName, "failed to prepare artifact directory")
                )
            }

        val packageName = resolvePackageName(runtime)
            ?: run {
                logger.warn("$callerToolName: package name is empty")
                return DumpInternalResult.Failure(
                    McpToolResult.internalErrorResult(
                        callerToolName, "failed to resolve package name for ViewHierarchy server"
                    )
                )
            }

        val timestamp = System.currentTimeMillis()
        val localJsonFile = File(toolDir, "layout_$timestamp.json")
        val outputFile = if (mode == "ui") localJsonFile else File(toolDir, "layout_${timestamp}_interaction.json")

        var successOutput: DumpInternalResult.Success? = null
        val result = McpAppReadyGuard.executeWithRuntimeObserveRetry {
            try {
                val client = ViewHierarchyClient(selected.adb, packageName)
                val excludeGone = !isIncludeGone
                val topWindowOnly = !isAllWindows
                val dumpResult = client.dumpLayout(rootLayout, excludeGone, topWindowOnly)
                    ?: return@executeWithRuntimeObserveRetry ViewHierarchyFailureDiagnoser.unavailableResult(
                        toolName = callerToolName,
                        adb = selected.adb,
                        packageName = packageName,
                        fallbackMessage = "ViewHierarchy server is unavailable or returned invalid response",
                    )
                if (!dumpResult.errorMessage.isNullOrBlank()) {
                    return@executeWithRuntimeObserveRetry ViewHierarchyFailureDiagnoser.toolError(
                        callerToolName,
                        dumpResult.errorMessage,
                    )
                }

                val payloadJson = dumpResult.payloadJson
                val remoteFilePath = dumpResult.remoteFilePath
                if (!payloadJson.isNullOrBlank()) {
                    localJsonFile.writeText(payloadJson, StandardCharsets.UTF_8)
                } else if (!remoteFilePath.isNullOrBlank()) {
                    selected.adb.pull(remoteFilePath, localJsonFile)
                }

                if (!localJsonFile.exists() || localJsonFile.length() <= 0) {
                    return@executeWithRuntimeObserveRetry McpToolResult.internalErrorResult(
                        callerToolName,
                        "failed to fetch layout dump from ViewHierarchy server"
                    )
                }

                val jsonContent = localJsonFile.readText(StandardCharsets.UTF_8)
                val jsonElement = JsonParser.parseString(jsonContent)
                val density = extractDensity(jsonElement)
                if (density > 0) {
                    convertPxToDp(jsonElement, density)
                }

                val converter = LayoutJsonConverter()
                val uiJson = converter.convert(jsonElement.asJsonObject, "ui")
                val unit = if (density > 0) "dp" else "px"
                uiJson.add("units", JsonObject().apply {
                    addProperty("bounds", unit)
                    addProperty("padding", unit)
                })
                // Keep the unpruned tree for internal consumers, regardless of public mode.
                val uiContent = converter.serialize(uiJson)
                localJsonFile.writeText(uiContent, StandardCharsets.UTF_8)
                val outputJson = if (mode == "ui") uiJson else converter.convert(uiJson, mode)
                val content = if (mode == "ui") uiContent else converter.serialize(outputJson)
                if (outputFile != localJsonFile) outputFile.writeText(content, StandardCharsets.UTF_8)

                val summary = buildSummaryMessage(outputJson)
                val contentBytes = content.toByteArray(StandardCharsets.UTF_8).size
                val output = DumpInternalResult.Success(
                    summary = summary,
                    jsonFile = localJsonFile,
                    outputFile = outputFile,
                    contentBytes = contentBytes,
                    inlineLayout = outputJson.takeIf { contentBytes <= MAX_INLINE_BYTES },
                    warnings = jsonElement.asJsonObject.getAsJsonArrayOrEmpty("warnings")
                        .mapNotNull { it.asStringOrNull() },
                )
                successOutput = output
                output.toPublicResult()
            } catch (e: Exception) {
                logger.warn("$callerToolName layout dump failed: ${e.message}", e)
                McpToolResult.internalErrorResult(callerToolName, e.message ?: "unknown error")
            }
        }
        return successOutput ?: DumpInternalResult.Failure(result)
    }

    internal sealed class DumpInternalResult {
        data class Success(
            val summary: String,
            val jsonFile: File,
            val outputFile: File,
            val contentBytes: Int,
            val inlineLayout: JsonObject?,
            val warnings: List<String> = emptyList(),
        ) : DumpInternalResult()

        data class Failure(val result: McpToolResult) : DumpInternalResult()
    }

    private fun DumpInternalResult.Success.toPublicResult(): McpToolResult {
        return McpToolResult(
            status = McpToolStatus.OK,
            message = summary,
            data = linkedMapOf<String, Any>(
                "file" to outputFile.absolutePath,
                "contentBytes" to contentBytes,
            ).apply {
                if (inlineLayout != null) put("layout", inlineLayout)
                if (warnings.isNotEmpty()) put("warnings", warnings)
            },
            artifacts = listOf(McpArtifact(type = "json", path = outputFile.absolutePath)),
            errorCode = null,
        )
    }

    // --- Internal utilities ---

    private fun ensureToolDir(runtime: IMcpRuntime, toolName: String): File? {
        val projectDir = runtime.projectDir.takeIf { it.isNotBlank() } ?: return null
        val dir = File(JuggPathManager(File(projectDir)).mcpFetchDir, toolName)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private fun resolvePackageName(runtime: IMcpRuntime): String? =
        runCatching { runtime.deployTargetManager.getPackageName().takeIf { it.isNotBlank() } }.getOrNull()

    private fun buildSummaryMessage(element: JsonElement): String {
        val root = element.asJsonObjectOrNull() ?: return "0 windows (top: unknown), 0 nodes, not truncated"
        val windows = root.getAsJsonArrayOrEmpty("windows")
        val windowCount = windows.size()
        val topTitle = windows.firstOrNullObject()?.get("title")?.asStringOrNull()?.takeIf { it.isNotBlank() } ?: "unknown"
        val nodeCount = countNodes(windows)
        val clickableCount = countClickable(windows)
        val truncated = root.get("truncated")?.runCatching { asBoolean }?.getOrDefault(false) ?: false
        val truncatedText = if (truncated) "truncated" else "not truncated"
        val warnings = root.getAsJsonArrayOrEmpty("warnings").mapNotNull { it.asStringOrNull() }
        val diagnostic = if (warnings.isEmpty()) "" else "; incomplete hierarchy: ${warnings.joinToString("; ")}"
        return "$windowCount windows (top: $topTitle), $nodeCount nodes, $clickableCount clickable, $truncatedText$diagnostic"
    }

    private fun countNodes(windows: JsonArray): Int {
        var total = 0
        windows.forEach { windowElement ->
            val rootNode = windowElement.asJsonObjectOrNull()?.get("root")?.asJsonObjectOrNull() ?: return@forEach
            total += countNodeRecursive(rootNode)
        }
        return total
    }

    private fun countClickable(windows: JsonArray): Int {
        var total = 0
        windows.forEach { windowElement ->
            val rootNode = windowElement.asJsonObjectOrNull()?.get("root")?.asJsonObjectOrNull() ?: return@forEach
            total += countClickableRecursive(rootNode)
        }
        return total
    }

    private fun countNodeRecursive(node: JsonObject): Int {
        if (node.has("jugg_details")) return 0
        var count = 1
        val children = node.getAsJsonArrayOrEmpty("children")
        for (child in children) {
            if (count >= MAX_COUNT_VISIT) return count
            val childObj = child.asJsonObjectOrNull() ?: continue
            count += countNodeRecursive(childObj)
        }
        return count
    }

    private fun countClickableRecursive(node: JsonObject): Int {
        if (node.has("jugg_details")) return 0
        var count = if (node.get("clickable")?.runCatching { asBoolean }?.getOrDefault(false) == true) 1 else 0
        val children = node.getAsJsonArrayOrEmpty("children")
        for (child in children) {
            if (count >= MAX_COUNT_VISIT) return count
            val childObj = child.asJsonObjectOrNull() ?: continue
            count += countClickableRecursive(childObj)
        }
        return count
    }

    private fun extractDensity(element: JsonElement): Double {
        val root = element.asJsonObjectOrNull() ?: return 0.0
        val deviceInfo = root.get("deviceInfo")?.asJsonObjectOrNull() ?: return 0.0
        return deviceInfo.get("density")?.runCatching { asDouble }?.getOrNull() ?: 0.0
    }

    private fun convertPxToDp(element: JsonElement, density: Double) {
        val root = element.asJsonObjectOrNull() ?: return
        val windows = root.getAsJsonArrayOrEmpty("windows")
        windows.forEach { windowElement ->
            val windowObj = windowElement.asJsonObjectOrNull() ?: return@forEach
            val rootNode = windowObj.get("root")?.asJsonObjectOrNull() ?: return@forEach
            convertNodePxToDp(rootNode, density)
        }
    }

    private fun convertNodePxToDp(node: JsonObject, density: Double) {
        node.convertArrayPxToDp("bounds", density)
        node.convertArrayPxToDp("padding", density)
        node.getAsJsonArrayOrEmpty("children").forEach { child ->
            child.asJsonObjectOrNull()?.let { convertNodePxToDp(it, density) }
        }
        node.getAsJsonArrayOrEmpty("composeNodes").forEach { compose ->
            compose.asJsonObjectOrNull()?.let { convertNodePxToDp(it, density) }
        }
    }

    private fun JsonObject.convertArrayPxToDp(key: String, density: Double) {
        val arr = get(key)?.takeIf { it.isJsonArray }?.asJsonArray?.takeIf { it.size() == 4 } ?: return
        val converted = JsonArray()
        for (i in 0 until 4) converted.add(pxToDp(arr.get(i).asInt, density))
        add(key, converted)
    }

    private fun pxToDp(px: Int, density: Double): Int = if (density <= 0) px else (px / density).toInt()

    // --- JsonElement extension helpers ---

    private fun JsonElement.asJsonObjectOrNull(): JsonObject? = if (isJsonObject) asJsonObject else null

    private fun JsonElement.asStringOrNull(): String? = runCatching { asString }.getOrNull()

    private fun JsonObject.getAsJsonArrayOrEmpty(key: String): JsonArray {
        val value = get(key)
        return if (value != null && value.isJsonArray) value.asJsonArray else JsonArray()
    }

    private fun JsonArray.firstOrNullObject(): JsonObject? =
        if (size() == 0) null else get(0).asJsonObjectOrNull()

    private const val MAX_COUNT_VISIT = 10_000
    private const val MAX_INLINE_BYTES = 16 * 1024
}
