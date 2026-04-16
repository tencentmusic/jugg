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
import com.sickworm.intellij.jugg.mcp.McpToolResult
import com.sickworm.intellij.jugg.mcp.McpToolStatus
import com.sickworm.intellij.jugg.mcp.viewhierarchy.ViewHierarchyClient
import com.sickworm.intellij.jugg.platform.PlatformApi
import com.sickworm.intellij.jugg.project.JuggPathManager
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
     * On success, result.data["file"] contains the absolute path to the written JSON file.
     */
    fun dump(
        runtime: IMcpRuntime,
        callerToolName: String,
        rootLayout: String? = null,
        isIncludeGone: Boolean = false,
        isAllWindows: Boolean = false,
    ): McpToolResult {
        val logger = runtime.logger.getInstance("LayoutDumpHelper")

        val selected = resolveOnlineDevice(runtime)
            ?: run {
                logger.warn("$callerToolName: no online device")
                return noDeviceResult(callerToolName)
            }

        val preWaitResult = McpAppReadyGuard.waitBeforeRuntimeObserve(runtime, callerToolName)
        if (!preWaitResult.isReady) {
            logger.warn("$callerToolName: app not ready after pre-check retries")
            return preWaitResult.errorResult
                ?: McpToolResult.internalErrorResult(callerToolName, "app is not ready")
        }

        val toolDir = ensureToolDir(runtime, "layout-dump")
            ?: run {
                logger.warn("$callerToolName: unable to prepare artifact directory")
                return McpToolResult.internalErrorResult(callerToolName, "failed to prepare artifact directory")
            }

        val packageName = resolvePackageName(runtime)
            ?: run {
                logger.warn("$callerToolName: package name is empty")
                return McpToolResult.internalErrorResult(
                    callerToolName, "failed to resolve package name for ViewHierarchy server"
                )
            }

        val localJsonFile = File(toolDir, "layout_${System.currentTimeMillis()}.json")
        val localHtmlFile = File(toolDir, "layout_${System.currentTimeMillis()}.html")

        return McpAppReadyGuard.executeWithRetryIfPreWaited(preWaitResult) {
            try {
                val client = ViewHierarchyClient(selected.adb, packageName)
                val excludeGone = !isIncludeGone
                val topWindowOnly = !isAllWindows
                val dumpResult = client.dumpLayout(rootLayout, excludeGone, topWindowOnly)
                    ?: return@executeWithRetryIfPreWaited McpToolResult.internalErrorResult(
                        callerToolName,
                        "ViewHierarchy server is unavailable or returned invalid response"
                    )

                val payloadJson = dumpResult.payloadJson
                val remoteFilePath = dumpResult.remoteFilePath
                if (!payloadJson.isNullOrBlank()) {
                    localJsonFile.writeText(payloadJson, StandardCharsets.UTF_8)
                } else if (!remoteFilePath.isNullOrBlank()) {
                    selected.adb.pull(remoteFilePath, localJsonFile)
                }

                if (!localJsonFile.exists() || localJsonFile.length() <= 0) {
                    return@executeWithRetryIfPreWaited McpToolResult.internalErrorResult(
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

                // Keep intermediate JSON for internal consumers (e.g. FigmaLayoutVerify).
                val convertedJson = jsonElement.toString()
                localJsonFile.writeText(convertedJson, StandardCharsets.UTF_8)

                // Convert to HTML for the MCP artifact (better LLM information density).
                val htmlContent = LayoutHtmlConverter().convert(jsonElement.asJsonObject)
                localHtmlFile.writeText(htmlContent, StandardCharsets.UTF_8)

                val summary = buildSummaryMessage(jsonElement)
                val contentBytes = htmlContent.toByteArray(StandardCharsets.UTF_8).size
                McpToolResult(
                    status = McpToolStatus.OK,
                    message = summary,
                    data = mapOf<String, Any>(
                        "file" to localHtmlFile.absolutePath,
                        "jsonFile" to localJsonFile.absolutePath,
                        "contentBytes" to contentBytes,
                    ),
                    artifacts = listOf(McpArtifact(type = "html", path = localHtmlFile.absolutePath)),
                    errorCode = null,
                )
            } catch (e: Exception) {
                logger.warn("$callerToolName layout dump failed: ${e.message}", e)
                McpToolResult.internalErrorResult(callerToolName, e.message ?: "unknown error")
            }
        }
    }

    // --- Internal utilities ---

    private data class SelectedAdb(val adb: IDeviceAdb)

    private fun resolveOnlineDevice(runtime: IMcpRuntime): SelectedAdb? {
        val selectionResult = DeviceSelectionResolver().resolve(runtime.deployTargetManager)
        if (selectionResult !is DeviceSelectionResult.Selected) return null
        val adb = PlatformApi.toDeviceAdb(selectionResult.device) ?: return null
        return if (adb.isOnline) SelectedAdb(adb) else null
    }

    private fun ensureToolDir(runtime: IMcpRuntime, toolName: String): File? {
        val projectDir = runtime.project.basePath ?: return null
        val dir = File(JuggPathManager(File(projectDir)).mcpFetchDir, toolName)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private fun resolvePackageName(runtime: IMcpRuntime): String? =
        runCatching { runtime.deployTargetManager.getPackageName().takeIf { it.isNotBlank() } }.getOrNull()

    private fun noDeviceResult(toolName: String) = McpToolResult(
        status = McpToolStatus.ERROR,
        message = "$toolName failed. Reason: No connected device is available.",
        data = emptyMap<String, Any>(),
        artifacts = emptyList(),
        errorCode = McpErrorCode.NO_DEVICE,
    )

    private fun buildSummaryMessage(element: JsonElement): String {
        val root = element.asJsonObjectOrNull() ?: return "0 windows (top: unknown), 0 nodes, not truncated"
        val windows = root.getAsJsonArrayOrEmpty("windows")
        val windowCount = windows.size()
        val topTitle = windows.firstOrNullObject()?.get("title")?.asStringOrNull()?.takeIf { it.isNotBlank() } ?: "unknown"
        val nodeCount = countNodes(windows)
        val clickableCount = countClickable(windows)
        val truncated = root.get("truncated")?.runCatching { asBoolean }?.getOrDefault(false) ?: false
        val truncatedText = if (truncated) "truncated" else "not truncated"
        return "$windowCount windows (top: $topTitle), $nodeCount nodes, $clickableCount clickable, $truncatedText"
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
}
