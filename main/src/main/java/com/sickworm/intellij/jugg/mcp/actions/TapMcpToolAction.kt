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
import com.sickworm.intellij.jugg.mcp.viewhierarchy.FindAndTapResult
import com.sickworm.intellij.jugg.mcp.viewhierarchy.ViewHierarchyClient
import com.sickworm.intellij.jugg.platform.PlatformApi
import com.sickworm.intellij.jugg.logger.getInstance

/**
 * TapMcpToolAction implements MCP tool `tap` with three tap modes:
 * 1. coordinate mode (x + y): tap exact pixel coordinates
 * 2. percent mode (xPercent + yPercent): tap by screen percentage, auto-resolves screen size
 * 3. element mode (text / resourceId / contentDesc): app-side atomic find_and_tap only (no legacy fallback)
 *
 * Parse priority when multiple mode parameters are provided together:
 * coordinate > percent > element. If no mode matches, returns MCP_INVALID_PARAMS.
 */
class TapMcpToolAction : McpToolAction {
    override val toolName: String = "tap"

    override val definition: McpToolDefinition = McpToolDefinition(
        name = toolName,
        description = "Tap on target device screen. Supports three modes: " +
            "(1) coordinate mode with x+y pixel values, " +
            "(2) percent mode with xPercent+yPercent (0-100) auto-resolved to pixels, " +
            "(3) element mode with text/resourceId/contentDesc to find UI element and tap its center " +
            "(app-side atomic find_and_tap only; no legacy uiautomator fallback), " +
            "(exact match only; if multiple elements match, returns all candidates without tapping — " +
            "use coordinate or percent mode to tap the intended one). " +
            "Parameter parse priority when multiple mode parameters are provided: " +
            "coordinate > percent > element.",
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
                        "matches" to McpJsonSchemaProperty(
                            type = "array",
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
        val logger = runtime.logger.getInstance("TapMcpToolAction")
        logger.debug(
            "tap start: hasCoordinate=${arguments["x"] != null && arguments["y"] != null}, " +
                "hasPercent=${arguments["xPercent"] != null && arguments["yPercent"] != null}, " +
                "hasElementSelector=${arguments["text"] != null || arguments["resourceId"] != null || arguments["contentDesc"] != null}"
        )
        val selected = resolveOnlineDevice(runtime)
            ?: run {
                logger.warn("tap failed: no online device")
                return noDeviceResult("tap")
            }
        val adb = selected.adb

        val x = (arguments["x"] as? Number)?.toInt()
        val y = (arguments["y"] as? Number)?.toInt()
        val xPercent = (arguments["xPercent"] as? Number)?.toDouble()
        val yPercent = (arguments["yPercent"] as? Number)?.toDouble()
        val text = arguments["text"] as? String
        val resourceId = arguments["resourceId"] as? String
        val contentDesc = arguments["contentDesc"] as? String
        val className = arguments["className"] as? String
        val packageName = resolvePackageName(runtime)

        return when {
            x != null && y != null -> tapByCoordinate(adb, x, y, logger)
            xPercent != null && yPercent != null -> tapByPercent(adb, xPercent, yPercent, logger)
            text != null || resourceId != null || contentDesc != null ->
                tapByElement(adb, packageName, text, resourceId, contentDesc, className, logger)
            else -> {
                logger.warn("tap failed: no valid tap mode detected in arguments")
                McpToolResult(
                    status = McpToolStatus.ERROR,
                    message = "tap failed. Reason: No valid tap mode detected. " +
                        "Provide (x+y), (xPercent+yPercent), or (text/resourceId/contentDesc).",
                    data = emptyMap<String, Any>(),
                    artifacts = emptyList(),
                    errorCode = McpErrorCode.MCP_INVALID_PARAMS,
                )
            }
        }
    }

    private fun tapByCoordinate(adb: IDeviceAdb, x: Int, y: Int, logger: com.intellij.openapi.diagnostic.Logger): McpToolResult {
        logger.debug("tap coordinate mode: device=${adb.serial}, x=$x, y=$y")
        return try {
            adb.execAdbShellCmd("input tap $x $y")
            logger.info("tap coordinate success: device=${adb.serial}, x=$x, y=$y")
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
            logger.warn("tap coordinate failed: ${e.message}", e)
            McpToolResult.internalErrorResult("tap", e.message ?: "unknown error")
        }
    }

    private fun tapByPercent(
        adb: IDeviceAdb,
        xPercent: Double,
        yPercent: Double,
        logger: com.intellij.openapi.diagnostic.Logger,
    ): McpToolResult {
        logger.debug("tap percent mode: device=${adb.serial}, xPercent=$xPercent, yPercent=$yPercent")
        return try {
            val screenSize = getScreenSize(adb)
                ?: return McpToolResult(
                    status = McpToolStatus.ERROR,
                    message = "tap failed. Reason: Unable to determine screen size from device.",
                    data = emptyMap<String, Any>(),
                    artifacts = emptyList(),
                    errorCode = McpErrorCode.MCP_INTERNAL_ERROR,
                )
                    .also { logger.warn("tap percent failed: unable to resolve screen size from device=${adb.serial}") }
            val tapX = (screenSize.width * xPercent / 100.0).toInt()
            val tapY = (screenSize.height * yPercent / 100.0).toInt()
            adb.execAdbShellCmd("input tap $tapX $tapY")
            logger.info(
                "tap percent success: device=${adb.serial}, xPercent=$xPercent, yPercent=$yPercent, " +
                    "x=$tapX, y=$tapY, screen=${screenSize.width}x${screenSize.height}"
            )
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
            logger.warn("tap percent failed: ${e.message}", e)
            McpToolResult.internalErrorResult("tap", e.message ?: "unknown error")
        }
    }

    private fun tapByElement(
        adb: IDeviceAdb,
        packageName: String?,
        text: String?,
        resourceId: String?,
        contentDesc: String?,
        className: String?,
        logger: com.intellij.openapi.diagnostic.Logger,
    ): McpToolResult {
        logger.debug(
            "tap element mode: device=${adb.serial}, package=$packageName, text=$text, " +
                "resourceId=$resourceId, contentDesc=$contentDesc, className=$className"
        )
        return try {
            if (packageName.isNullOrBlank()) {
                logger.warn("tap element failed: package name is empty")
                return McpToolResult(
                    status = McpToolStatus.ERROR,
                    message = "tap failed. Reason: Unable to resolve package name for ViewHierarchy server.",
                    data = mapOf("mode" to "element"),
                    artifacts = emptyList(),
                    errorCode = McpErrorCode.MCP_INTERNAL_ERROR,
                )
            }

            val serverResult = ViewHierarchyClient(adb, packageName)
                .findAndTap(text = text, resourceId = resourceId, contentDesc = contentDesc, className = className)
                ?: return McpToolResult(
                    status = McpToolStatus.ERROR,
                    message = "tap failed. Reason: ViewHierarchy server is unavailable.",
                    data = mapOf("mode" to "element"),
                    artifacts = emptyList(),
                    errorCode = McpErrorCode.MCP_INTERNAL_ERROR,
                )
                    .also { logger.warn("tap element failed: ViewHierarchy server unavailable for package=$packageName") }

            when (serverResult) {
                is FindAndTapResult.Success -> {
                    logger.info(
                        "tap element success: device=${adb.serial}, x=${serverResult.x}, y=${serverResult.y}, " +
                            "matchCount=${serverResult.matchCount}"
                    )
                    return McpToolResult(
                        status = McpToolStatus.OK,
                        message = "tap executed successfully.",
                        data = mapOf(
                            "x" to serverResult.x,
                            "y" to serverResult.y,
                            "mode" to "element",
                            "matchedElement" to serverResult.matchedElement,
                            "matchCount" to serverResult.matchCount,
                        ),
                        artifacts = emptyList(),
                        errorCode = null,
                    )
                }

                is FindAndTapResult.Multiple -> {
                    logger.warn("tap element failed: multiple matches=${serverResult.matchCount}")
                    val matchesSummary = serverResult.matches.mapIndexed { i, match ->
                        mapOf(
                            "index" to i,
                            "text" to match.text,
                            "resourceId" to match.resourceId,
                            "contentDesc" to match.contentDesc,
                            "className" to match.className,
                            "bounds" to (match.bounds?.toString() ?: ""),
                            "centerX" to match.centerX,
                            "centerY" to match.centerY,
                        )
                    }
                    return McpToolResult(
                        status = McpToolStatus.ERROR,
                        message = "tap failed. Reason: ${serverResult.matchCount} elements matched. " +
                            "Use coordinate mode (x+y) or percent mode (xPercent+yPercent) to tap the intended element.",
                        data = mapOf(
                            "matchCount" to serverResult.matchCount,
                            "mode" to "element",
                            "matches" to matchesSummary,
                        ),
                        artifacts = emptyList(),
                        errorCode = McpErrorCode.MCP_INVALID_PARAMS,
                    )
                }

                is FindAndTapResult.NotFound -> {
                    logger.warn("tap element failed: no matching element")
                    val candidateDesc = if (serverResult.candidates.isNotEmpty()) {
                        " Available clickable elements: " + serverResult.candidates.joinToString("; ") { candidate ->
                            buildString {
                                if (candidate.text.isNotBlank()) {
                                    append("text=\"")
                                    append(candidate.text)
                                    append("\"")
                                }
                                if (candidate.resourceId.isNotBlank()) {
                                    if (isNotEmpty()) append(", ")
                                    append("resource-id=\"")
                                    append(candidate.resourceId)
                                    append("\"")
                                }
                                if (candidate.className.isNotBlank()) {
                                    if (isNotEmpty()) append(", ")
                                    append("class=\"")
                                    append(candidate.className)
                                    append("\"")
                                }
                            }
                        }
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

                is FindAndTapResult.Failure -> {
                    logger.warn("tap element failed: server error=${serverResult.message}")
                    return McpToolResult(
                        status = McpToolStatus.ERROR,
                        message = "tap failed. Reason: ViewHierarchy server error: ${serverResult.message}",
                        data = mapOf("mode" to "element"),
                        artifacts = emptyList(),
                        errorCode = McpErrorCode.MCP_INTERNAL_ERROR,
                    )
                }
            }
        } catch (e: Exception) {
            logger.warn("tap element failed with exception: ${e.message}", e)
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

    private fun resolvePackageName(runtime: IMcpRuntime): String? {
        return try {
            runtime.deployTargetManager.getPackageName().takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        private val SIZE_PATTERN = Regex("""(\d+)x(\d+)""")
    }
}
