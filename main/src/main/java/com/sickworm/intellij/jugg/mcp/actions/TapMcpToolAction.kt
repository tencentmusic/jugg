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
import org.w3c.dom.Element
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory

/**
 * TapMcpToolAction implements MCP tool `tap` with three tap modes:
 * 1. coordinate mode (x + y): tap exact pixel coordinates
 * 2. percent mode (xPercent + yPercent): tap by screen percentage, auto-resolves screen size
 * 3. element mode (text / resourceId / contentDesc): find UI element via uiautomator dump and tap its center
 *
 * Priority: coordinate > percent > element. If no mode matches, returns MCP_INVALID_PARAMS.
 */
class TapMcpToolAction : McpToolAction {
    override val toolName: String = "tap"

    override val definition: McpToolDefinition = McpToolDefinition(
        name = toolName,
        description = "Tap on target device screen. Supports three modes: " +
            "(1) coordinate mode with x+y pixel values, " +
            "(2) percent mode with xPercent+yPercent (0-100) auto-resolved to pixels, " +
            "(3) element mode with text/resourceId/contentDesc to find UI element and tap its center " +
            "(exact match only; if multiple elements match, returns all candidates without tapping — " +
            "use coordinate or percent mode to tap the intended one). " +
            "Priority: coordinate > percent > element.",
        inputSchema = McpJsonSchemaObject(
            properties = mapOf(
                "projectDir" to McpToolSchemas.projectDirProperty,
                "x" to McpJsonSchemaProperty(
                    type = "number",
                    description = "X coordinate in device screen space (coordinate mode).",
                    minimum = 0.0,
                    examples = listOf(200),
                ),
                "y" to McpJsonSchemaProperty(
                    type = "number",
                    description = "Y coordinate in device screen space (coordinate mode).",
                    minimum = 0.0,
                    examples = listOf(400),
                ),
                "xPercent" to McpJsonSchemaProperty(
                    type = "number",
                    description = "X position as percentage of screen width, 0-100 (percent mode).",
                    minimum = 0.0,
                    maximum = 100.0,
                    examples = listOf(50),
                ),
                "yPercent" to McpJsonSchemaProperty(
                    type = "number",
                    description = "Y position as percentage of screen height, 0-100 (percent mode).",
                    minimum = 0.0,
                    maximum = 100.0,
                    examples = listOf(50),
                ),
                "text" to McpJsonSchemaProperty(
                    type = "string",
                    description = "UI element text to match (element mode). Exact match only.",
                ),
                "resourceId" to McpJsonSchemaProperty(
                    type = "string",
                    description = "UI element resource-id to match (element mode). Exact match only.",
                ),
                "contentDesc" to McpJsonSchemaProperty(
                    type = "string",
                    description = "UI element content-desc to match (element mode). Exact match only.",
                ),
                "className" to McpJsonSchemaProperty(
                    type = "string",
                    description = "Additional class name filter for element mode (AND logic with other selectors). Exact match only.",
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
                        "x" to McpJsonSchemaProperty(type = "number", minimum = 0.0),
                        "y" to McpJsonSchemaProperty(type = "number", minimum = 0.0),
                        "mode" to McpJsonSchemaProperty(type = "string"),
                        "screenWidth" to McpJsonSchemaProperty(type = "number"),
                        "screenHeight" to McpJsonSchemaProperty(type = "number"),
                        "matchedElement" to McpJsonSchemaProperty(type = "string"),
                        "matchCount" to McpJsonSchemaProperty(type = "number"),
                        "matches" to McpJsonSchemaProperty(type = "array",
                            description = "All matched elements with bounds/center when matchCount > 1.",
                            items = McpJsonSchemaProperty(type = "object", additionalProperties = true),
                        ),
                    ),
                    required = listOf("mode"),
                    additionalProperties = false,
                )
            )
        ),
    )

    override fun execute(arguments: Map<String, Any?>, runtime: IMcpRuntime): McpToolResult {
        val selected = resolveOnlineDevice(runtime)
            ?: return noDeviceResult("tap")
        val adb = selected.adb

        val x = (arguments["x"] as? Number)?.toInt()
        val y = (arguments["y"] as? Number)?.toInt()
        val xPercent = (arguments["xPercent"] as? Number)?.toDouble()
        val yPercent = (arguments["yPercent"] as? Number)?.toDouble()
        val text = arguments["text"] as? String
        val resourceId = arguments["resourceId"] as? String
        val contentDesc = arguments["contentDesc"] as? String
        val className = arguments["className"] as? String

        return when {
            x != null && y != null -> tapByCoordinate(adb, x, y)
            xPercent != null && yPercent != null -> tapByPercent(adb, xPercent, yPercent)
            text != null || resourceId != null || contentDesc != null ->
                tapByElement(adb, text, resourceId, contentDesc, className)
            else -> McpToolResult(
                status = McpToolStatus.ERROR,
                message = "tap failed. Reason: No valid tap mode detected. " +
                    "Provide (x+y), (xPercent+yPercent), or (text/resourceId/contentDesc).",
                data = emptyMap<String, Any>(),
                artifacts = emptyList(),
                errorCode = McpErrorCode.MCP_INVALID_PARAMS,
            )
        }
    }

    private fun tapByCoordinate(adb: IDeviceAdb, x: Int, y: Int): McpToolResult {
        return try {
            adb.execAdbShellCmd("input tap $x $y")
            McpToolResult(
                status = McpToolStatus.OK,
                message = "tap executed successfully.",
                data = mapOf(
                    "x" to x,
                    "y" to y,
                    "mode" to "coordinate",
                ),
                artifacts = emptyList(),
                errorCode = null,
            )
        } catch (e: Exception) {
            McpToolResult.internalErrorResult("tap", e.message ?: "unknown error")
        }
    }

    private fun tapByPercent(adb: IDeviceAdb, xPercent: Double, yPercent: Double): McpToolResult {
        return try {
            val screenSize = getScreenSize(adb)
                ?: return McpToolResult(
                    status = McpToolStatus.ERROR,
                    message = "tap failed. Reason: Unable to determine screen size from device.",
                    data = emptyMap<String, Any>(),
                    artifacts = emptyList(),
                    errorCode = McpErrorCode.MCP_INTERNAL_ERROR,
                )
            val tapX = (screenSize.width * xPercent / 100.0).toInt()
            val tapY = (screenSize.height * yPercent / 100.0).toInt()
            adb.execAdbShellCmd("input tap $tapX $tapY")
            McpToolResult(
                status = McpToolStatus.OK,
                message = "tap executed successfully.",
                data = mapOf(
                    "x" to tapX,
                    "y" to tapY,
                    "mode" to "percent",
                    "screenWidth" to screenSize.width,
                    "screenHeight" to screenSize.height,
                ),
                artifacts = emptyList(),
                errorCode = null,
            )
        } catch (e: Exception) {
            McpToolResult.internalErrorResult("tap", e.message ?: "unknown error")
        }
    }

    private fun tapByElement(
        adb: IDeviceAdb,
        text: String?,
        resourceId: String?,
        contentDesc: String?,
        className: String?,
    ): McpToolResult {
        return try {
            val xml = dumpUiHierarchy(adb)
            if (xml.isBlank()) {
                return McpToolResult(
                    status = McpToolStatus.ERROR,
                    message = "tap failed. Reason: UI hierarchy dump returned empty content.",
                    data = emptyMap<String, Any>(),
                    artifacts = emptyList(),
                    errorCode = McpErrorCode.MCP_INTERNAL_ERROR,
                )
            }
            val matchedNodes = findMatchingNodes(xml, text, resourceId, contentDesc, className)
            if (matchedNodes.isEmpty()) {
                val candidates = findClickableCandidates(xml, 5)
                val candidateDesc = if (candidates.isNotEmpty()) {
                    " Available clickable elements: " + candidates.joinToString("; ") { it.describe() }
                } else {
                    ""
                }
                return McpToolResult(
                    status = McpToolStatus.ERROR,
                    message = "tap failed. Reason: No matching UI element found.$candidateDesc",
                    data = mapOf(
                        "matchCount" to 0,
                        "mode" to "element",
                    ),
                    artifacts = emptyList(),
                    errorCode = McpErrorCode.MCP_INTERNAL_ERROR,
                )
            }
            if (matchedNodes.size > 1) {
                val matchesSummary = matchedNodes.mapIndexed { i, node ->
                    mapOf(
                        "index" to i,
                        "text" to node.text,
                        "resourceId" to node.resourceId,
                        "contentDesc" to node.contentDesc,
                        "className" to node.className,
                        "bounds" to node.bounds,
                        "centerX" to (node.center?.x ?: -1),
                        "centerY" to (node.center?.y ?: -1),
                    )
                }
                return McpToolResult(
                    status = McpToolStatus.ERROR,
                    message = "tap failed. Reason: ${matchedNodes.size} elements matched. " +
                        "Use coordinate mode (x+y) or percent mode (xPercent+yPercent) to tap the intended element.",
                    data = mapOf(
                        "matchCount" to matchedNodes.size,
                        "mode" to "element",
                        "matches" to matchesSummary,
                    ),
                    artifacts = emptyList(),
                    errorCode = McpErrorCode.MCP_INVALID_PARAMS,
                )
            }
            val targetNode = matchedNodes[0]
            val center = targetNode.center
                ?: return McpToolResult(
                    status = McpToolStatus.ERROR,
                    message = "tap failed. Reason: Unable to parse bounds for matched element.",
                    data = emptyMap<String, Any>(),
                    artifacts = emptyList(),
                    errorCode = McpErrorCode.MCP_INTERNAL_ERROR,
                )
            adb.execAdbShellCmd("input tap ${center.x} ${center.y}")
            McpToolResult(
                status = McpToolStatus.OK,
                message = "tap executed successfully.",
                data = mapOf(
                    "x" to center.x,
                    "y" to center.y,
                    "mode" to "element",
                    "matchedElement" to targetNode.describe(),
                    "matchCount" to 1,
                ),
                artifacts = emptyList(),
                errorCode = null,
            )
        } catch (e: Exception) {
            McpToolResult.internalErrorResult("tap", e.message ?: "unknown error")
        }
    }

    /**
     * Parses screen size from `adb shell wm size` output.
     * Prefers Override size over Physical size.
     */
    private fun getScreenSize(adb: IDeviceAdb): ScreenSize? {
        val output = adb.execAdbShellCmd("wm size")
        var width: Int? = null
        var height: Int? = null
        for (line in output.lines()) {
            val sizeMatch = SIZE_PATTERN.find(line) ?: continue
            val w = sizeMatch.groupValues[1].toIntOrNull() ?: continue
            val h = sizeMatch.groupValues[2].toIntOrNull() ?: continue
            width = w
            height = h
            if (line.trimStart().startsWith("Override")) {
                break
            }
        }
        return if (width != null && height != null) ScreenSize(width, height) else null
    }

    /**
     * Dumps UI hierarchy via uiautomator and reads XML content from device.
     */
    private fun dumpUiHierarchy(adb: IDeviceAdb): String {
        adb.execAdbShellCmd("uiautomator dump /sdcard/Download/jugg_mcp/tap_layout.xml")
        return adb.execAdbShellCmd("cat /sdcard/Download/jugg_mcp/tap_layout.xml")
    }

    /**
     * Parses XML and finds nodes matching the given selectors with AND logic.
     */
    private fun findMatchingNodes(
        xml: String,
        text: String?,
        resourceId: String?,
        contentDesc: String?,
        className: String?,
    ): List<UiNode> {
        val doc = try {
            val factory = DocumentBuilderFactory.newInstance()
            factory.isNamespaceAware = false
            val builder = factory.newDocumentBuilder()
            builder.parse(org.xml.sax.InputSource(StringReader(xml)))
        } catch (_: Exception) {
            return emptyList()
        }
        val allNodes = mutableListOf<Element>()
        collectNodes(doc.documentElement, allNodes)
        return allNodes.mapNotNull { element ->
            val nodeText = element.getAttribute("text").orEmpty()
            val nodeResId = element.getAttribute("resource-id").orEmpty()
            val nodeContentDesc = element.getAttribute("content-desc").orEmpty()
            val nodeClassName = element.getAttribute("class").orEmpty()
            val nodeBounds = element.getAttribute("bounds").orEmpty()
            val matches = (text == null || nodeText == text) &&
                (resourceId == null || nodeResId == resourceId) &&
                (contentDesc == null || nodeContentDesc == contentDesc) &&
                (className == null || nodeClassName == className)
            if (matches) {
                UiNode(
                    text = nodeText,
                    resourceId = nodeResId,
                    contentDesc = nodeContentDesc,
                    className = nodeClassName,
                    bounds = nodeBounds,
                    center = parseBoundsCenter(nodeBounds),
                )
            } else {
                null
            }
        }
    }

    /**
     * Recursively collects all `node` elements from the DOM tree.
     */
    private fun collectNodes(element: Element, result: MutableList<Element>) {
        if (element.tagName == "node") {
            result.add(element)
        }
        val children = element.childNodes
        for (i in 0 until children.length) {
            val child = children.item(i)
            if (child is Element) {
                collectNodes(child, result)
            }
        }
    }

    /**
     * Parses bounds string `[left,top][right,bottom]` and returns center point.
     */
    private fun parseBoundsCenter(bounds: String): Point? {
        val match = BOUNDS_PATTERN.find(bounds) ?: return null
        val left = match.groupValues[1].toIntOrNull() ?: return null
        val top = match.groupValues[2].toIntOrNull() ?: return null
        val right = match.groupValues[3].toIntOrNull() ?: return null
        val bottom = match.groupValues[4].toIntOrNull() ?: return null
        return Point((left + right) / 2, (top + bottom) / 2)
    }

    /**
     * Returns a limited list of clickable candidate elements for debugging when no match is found.
     */
    private fun findClickableCandidates(xml: String, limit: Int): List<UiNode> {
        val doc = try {
            val factory = DocumentBuilderFactory.newInstance()
            factory.isNamespaceAware = false
            val builder = factory.newDocumentBuilder()
            builder.parse(org.xml.sax.InputSource(StringReader(xml)))
        } catch (_: Exception) {
            return emptyList()
        }
        val allNodes = mutableListOf<Element>()
        collectNodes(doc.documentElement, allNodes)
        return allNodes.filter { it.getAttribute("clickable") == "true" }
            .take(limit)
            .map { element ->
                UiNode(
                    text = element.getAttribute("text").orEmpty(),
                    resourceId = element.getAttribute("resource-id").orEmpty(),
                    contentDesc = element.getAttribute("content-desc").orEmpty(),
                    className = element.getAttribute("class").orEmpty(),
                    bounds = element.getAttribute("bounds").orEmpty(),
                    center = parseBoundsCenter(element.getAttribute("bounds").orEmpty()),
                )
            }
    }

    private data class UiNode(
        val text: String,
        val resourceId: String,
        val contentDesc: String,
        val className: String,
        val bounds: String,
        val center: Point?,
    ) {
        fun describe(): String {
            val parts = mutableListOf<String>()
            if (text.isNotBlank()) parts.add("text=\"$text\"")
            if (resourceId.isNotBlank()) parts.add("resource-id=\"$resourceId\"")
            if (contentDesc.isNotBlank()) parts.add("content-desc=\"$contentDesc\"")
            if (className.isNotBlank()) parts.add("class=\"$className\"")
            if (bounds.isNotBlank()) parts.add("bounds=$bounds")
            return parts.joinToString(", ")
        }
    }

    private data class Point(val x: Int, val y: Int)

    private data class ScreenSize(val width: Int, val height: Int)

    private data class SelectedAdb(val adb: IDeviceAdb)

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

    companion object {
        private val BOUNDS_PATTERN = Regex("""\[(\d+),(\d+)]\[(\d+),(\d+)]""")
        private val SIZE_PATTERN = Regex("""(\d+)x(\d+)""")
    }
}
