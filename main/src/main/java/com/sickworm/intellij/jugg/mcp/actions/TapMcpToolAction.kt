package com.sickworm.intellij.jugg.mcp.actions

import com.sickworm.intellij.jugg.deploy.IDeviceAdb
import com.sickworm.intellij.jugg.mcp.DeviceSelectionResolver
import com.sickworm.intellij.jugg.mcp.DeviceSelectionResult
import com.sickworm.intellij.jugg.mcp.IMcpRuntime
import com.sickworm.intellij.jugg.mcp.McpErrorCode
import com.sickworm.intellij.jugg.mcp.McpJsonSchemaObject
import com.sickworm.intellij.jugg.mcp.McpToolDefinition
import com.sickworm.intellij.jugg.mcp.McpToolResult
import com.sickworm.intellij.jugg.mcp.McpToolStatus
import com.sickworm.intellij.jugg.mcp.viewhierarchy.FindAndTapResult
import com.sickworm.intellij.jugg.mcp.viewhierarchy.MatchedElementData
import com.sickworm.intellij.jugg.mcp.viewhierarchy.ViewHierarchyClient
import com.sickworm.intellij.jugg.platform.PlatformApi
import com.sickworm.intellij.jugg.logger.getInstance
import kotlin.math.roundToInt

/**
 * TapMcpToolAction implements MCP tool `tap` and supports tap, longPress and swipe actions.
 */
class TapMcpToolAction : McpToolAction {
    override val toolName: String = McpToolActionRegistry.ToolNames.TAP

    override val definition: McpToolDefinition = McpToolDefinition(
        name = toolName,
        description = "Tap, long press, or swipe on target device. Modes: coordinate (x/y), percent (xPercent/yPercent), " +
            "or element selectors (text/id/desc, optional class). id=resourceId alias, desc=contentDesc alias, class=className alias. " +
            "Mode priority: coordinate > percent > element.",
        inputSchema = McpJsonSchemaObject(
            properties = mapOf("projectDir" to McpToolSchemas.projectDirProperty) + McpToolSchemas.tapActionProperties,
            required = listOf("projectDir"),
            additionalProperties = false,
        ),
        outputSchema = McpToolSchemas.baseOutputSchema,
    )

    override fun execute(arguments: Map<String, Any?>, runtime: IMcpRuntime): McpToolResult {
        val logger = runtime.logger.getInstance("TapMcpToolAction")
        val action = (arguments["action"] as? String)?.ifBlank { "tap" } ?: "tap"
        val validationError = validateArgumentsBeforeRuntimeCheck(action, arguments)
        if (validationError != null) {
            return validationError
        }
        val selected = resolveOnlineDevice(runtime)
            ?: run {
                logger.warn("tap failed: no online device")
                return noDeviceResult("tap")
            }
        val preWaitResult = McpAppReadyGuard.waitBeforeRuntimeObserve(runtime, toolName)
        if (!preWaitResult.isReady) {
            logger.warn("tap failed: app not ready after pre-check timeout")
            return preWaitResult.errorResult ?: McpToolResult.internalErrorResult("tap", "app is not ready")
        }
        val adb = selected.adb
        val packageName = resolvePackageName(runtime)
        val topActivityStabilityResult = waitTopActivityOnResumeStable(adb)
        val actionResult = McpAppReadyGuard.executeWithRetryIfPreWaited(preWaitResult) {
            when (action) {
                "tap" -> executeTap(arguments, adb, packageName, logger)
                "long-press" -> executeLongPress(arguments, adb, packageName, logger)
                "swipe" -> executeSwipe(arguments, adb, logger)
                else -> McpToolResult(
                    status = McpToolStatus.ERROR,
                    message = "tap failed. Reason: Unsupported action: $action. Use tap, long-press, or swipe.",
                    data = emptyMap<String, Any>(),
                    artifacts = emptyList(),
                    errorCode = McpErrorCode.INVALID_PARAMS,
                )
            }
        }
        if (!topActivityStabilityResult.isStable) {
            return appendTopActivityNotStableHintIfNeeded(actionResult, topActivityStabilityResult)
        }
        return actionResult
    }

    private fun validateArgumentsBeforeRuntimeCheck(
        action: String,
        arguments: Map<String, Any?>,
    ): McpToolResult? {
        val x = arguments.numberAsInt("x")
        val y = arguments.numberAsInt("y")
        val endX = arguments.numberAsInt("endX")
        val endY = arguments.numberAsInt("endY")
        val xPercent = arguments.numberAsDouble("xPercent")
        val yPercent = arguments.numberAsDouble("yPercent")
        val endXPercent = arguments.numberAsDouble("endXPercent")
        val endYPercent = arguments.numberAsDouble("endYPercent")
        val text = arguments["text"] as? String
        val resourceId = arguments.resolveId()
        val contentDesc = arguments.resolveDesc()

        return when (action) {
            "tap", "long-press" -> {
                when {
                    x != null && y != null -> null
                    xPercent != null && yPercent != null -> null
                    hasElementSelector(text, resourceId, contentDesc) -> null
                    else -> invalidModeResult()
                }
            }

            "swipe" -> {
                when {
                    x != null || y != null || endX != null || endY != null -> {
                        if (x == null || y == null || endX == null || endY == null) {
                            swipeMissingEndResult()
                        } else {
                            null
                        }
                    }

                    xPercent != null || yPercent != null || endXPercent != null || endYPercent != null -> {
                        if (xPercent == null || yPercent == null || endXPercent == null || endYPercent == null) {
                            swipeMissingEndResult()
                        } else {
                            null
                        }
                    }

                    hasElementSelector(text, resourceId, contentDesc) -> McpToolResult(
                        status = McpToolStatus.ERROR,
                        message = "tap failed. Reason: swipe action does not support element mode.",
                        data = emptyMap<String, Any>(),
                        artifacts = emptyList(),
                        errorCode = McpErrorCode.INVALID_PARAMS,
                    )

                    else -> invalidModeResult()
                }
            }

            else -> McpToolResult(
                status = McpToolStatus.ERROR,
                message = "tap failed. Reason: Unsupported action: $action. Use tap, long-press, or swipe.",
                data = emptyMap<String, Any>(),
                artifacts = emptyList(),
                errorCode = McpErrorCode.INVALID_PARAMS,
            )
        }
    }

    private fun executeTap(
        arguments: Map<String, Any?>,
        adb: IDeviceAdb,
        packageName: String?,
        logger: com.intellij.openapi.diagnostic.Logger,
    ): McpToolResult {
        val x = arguments.numberAsInt("x")
        val y = arguments.numberAsInt("y")
        val xPercent = arguments.numberAsDouble("xPercent")
        val yPercent = arguments.numberAsDouble("yPercent")
        val text = arguments["text"] as? String
        val resourceId = arguments.resolveId()
        val contentDesc = arguments.resolveDesc()
        val className = arguments.resolveClass()

        return when {
            x != null && y != null -> tapByCoordinate(adb, x, y, logger)
            xPercent != null && yPercent != null -> tapByPercent(adb, xPercent, yPercent, logger)
            hasElementSelector(text, resourceId, contentDesc) ->
                tapByElement(adb, packageName, text, resourceId, contentDesc, className)
            else -> invalidModeResult()
        }
    }

    private fun executeLongPress(
        arguments: Map<String, Any?>,
        adb: IDeviceAdb,
        packageName: String?,
        logger: com.intellij.openapi.diagnostic.Logger,
    ): McpToolResult {
        val x = arguments.numberAsInt("x")
        val y = arguments.numberAsInt("y")
        val xPercent = arguments.numberAsDouble("xPercent")
        val yPercent = arguments.numberAsDouble("yPercent")
        val text = arguments["text"] as? String
        val resourceId = arguments.resolveId()
        val contentDesc = arguments.resolveDesc()
        val className = arguments.resolveClass()
        val duration = sanitizeDuration(arguments.numberAsInt("duration") ?: DEFAULT_LONG_PRESS_DURATION_MS)

        return when {
            x != null && y != null -> longPressByCoordinate(adb, x, y, duration, logger)
            xPercent != null && yPercent != null -> longPressByPercent(adb, xPercent, yPercent, duration, logger)
            hasElementSelector(text, resourceId, contentDesc) ->
                longPressByElement(adb, packageName, text, resourceId, contentDesc, className, duration)
            else -> invalidModeResult()
        }
    }

    private fun executeSwipe(
        arguments: Map<String, Any?>,
        adb: IDeviceAdb,
        logger: com.intellij.openapi.diagnostic.Logger,
    ): McpToolResult {
        val x = arguments.numberAsInt("x")
        val y = arguments.numberAsInt("y")
        val endX = arguments.numberAsInt("endX")
        val endY = arguments.numberAsInt("endY")
        val xPercent = arguments.numberAsDouble("xPercent")
        val yPercent = arguments.numberAsDouble("yPercent")
        val endXPercent = arguments.numberAsDouble("endXPercent")
        val endYPercent = arguments.numberAsDouble("endYPercent")
        val text = arguments["text"] as? String
        val resourceId = arguments.resolveId()
        val contentDesc = arguments.resolveDesc()
        val duration = sanitizeDuration(arguments.numberAsInt("duration") ?: DEFAULT_SWIPE_DURATION_MS)

        return when {
            x != null || y != null || endX != null || endY != null -> {
                if (x == null || y == null || endX == null || endY == null) {
                    return swipeMissingEndResult()
                }
                swipeByCoordinate(adb, x, y, endX, endY, duration, logger)
            }

            xPercent != null || yPercent != null || endXPercent != null || endYPercent != null -> {
                if (xPercent == null || yPercent == null || endXPercent == null || endYPercent == null) {
                    return swipeMissingEndResult()
                }
                swipeByPercent(adb, xPercent, yPercent, endXPercent, endYPercent, duration, logger)
            }

            hasElementSelector(text, resourceId, contentDesc) -> McpToolResult(
                status = McpToolStatus.ERROR,
                message = "tap failed. Reason: swipe action does not support element mode.",
                data = emptyMap<String, Any>(),
                artifacts = emptyList(),
                errorCode = McpErrorCode.INVALID_PARAMS,
            )

            else -> invalidModeResult()
        }
    }

    private fun tapByCoordinate(adb: IDeviceAdb, x: Int, y: Int, logger: com.intellij.openapi.diagnostic.Logger): McpToolResult {
        logger.debug("tap coordinate mode: device=${adb.serial}, x=$x, y=$y")
        return try {
            adb.execAdbShellCmd("input tap $x $y")
            McpToolResult(
                status = McpToolStatus.OK,
                message = "tap executed successfully.",
                data = mapOf(
                    "action" to "tap",
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
        return try {
            val screenSize = getScreenSize(adb)
                ?: return screenSizeErrorResult()
            val tapX = percentToCoordinate(xPercent, screenSize.width)
            val tapY = percentToCoordinate(yPercent, screenSize.height)
            adb.execAdbShellCmd("input tap $tapX $tapY")
            McpToolResult(
                status = McpToolStatus.OK,
                message = "tap executed successfully.",
                data = mapOf(
                    "action" to "tap",
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
    ): McpToolResult {
        if (packageName.isNullOrBlank()) {
            return packageNameMissingResult()
        }

        val serverResult = ViewHierarchyClient(adb, packageName)
            .findAndTap(text = text, resourceId = resourceId, contentDesc = contentDesc, className = className)
            ?: return serverUnavailableResult("tap")

        return serverResultToToolResult(
            serverResult = serverResult,
            action = "tap",
            selectorContext = ElementSelectorContext.from(text, resourceId, contentDesc, className),
        )
    }

    private fun longPressByCoordinate(
        adb: IDeviceAdb,
        x: Int,
        y: Int,
        duration: Int,
        logger: com.intellij.openapi.diagnostic.Logger,
    ): McpToolResult {
        return try {
            adb.execAdbShellCmd("input swipe $x $y $x $y $duration")
            McpToolResult(
                status = McpToolStatus.OK,
                message = "long-press executed successfully.",
                data = mapOf(
                    "action" to "long-press",
                    "x" to x,
                    "y" to y,
                    "duration" to duration,
                    "mode" to "coordinate",
                ),
                artifacts = emptyList(),
                errorCode = null,
            )
        } catch (e: Exception) {
            logger.warn("long-press coordinate failed: ${e.message}", e)
            McpToolResult.internalErrorResult("tap", e.message ?: "unknown error")
        }
    }

    private fun longPressByPercent(
        adb: IDeviceAdb,
        xPercent: Double,
        yPercent: Double,
        duration: Int,
        logger: com.intellij.openapi.diagnostic.Logger,
    ): McpToolResult {
        return try {
            val screenSize = getScreenSize(adb)
                ?: return screenSizeErrorResult()
            val x = percentToCoordinate(xPercent, screenSize.width)
            val y = percentToCoordinate(yPercent, screenSize.height)
            adb.execAdbShellCmd("input swipe $x $y $x $y $duration")
            McpToolResult(
                status = McpToolStatus.OK,
                message = "long-press executed successfully.",
                data = mapOf(
                    "action" to "long-press",
                    "x" to x,
                    "y" to y,
                    "duration" to duration,
                    "mode" to "percent",
                    "screenWidth" to screenSize.width,
                    "screenHeight" to screenSize.height,
                ),
                artifacts = emptyList(),
                errorCode = null,
            )
        } catch (e: Exception) {
            logger.warn("long-press percent failed: ${e.message}", e)
            McpToolResult.internalErrorResult("tap", e.message ?: "unknown error")
        }
    }

    private fun longPressByElement(
        adb: IDeviceAdb,
        packageName: String?,
        text: String?,
        resourceId: String?,
        contentDesc: String?,
        className: String?,
        duration: Int,
    ): McpToolResult {
        if (packageName.isNullOrBlank()) {
            return packageNameMissingResult()
        }
        val serverResult = ViewHierarchyClient(adb, packageName)
            .findAndLongPress(
                text = text,
                resourceId = resourceId,
                contentDesc = contentDesc,
                className = className,
                duration = duration,
            )
            ?: return serverUnavailableResult("long-press")

        return serverResultToToolResult(
            serverResult = serverResult,
            action = "long-press",
            duration = duration,
            selectorContext = ElementSelectorContext.from(text, resourceId, contentDesc, className),
        )
    }

    private fun swipeByCoordinate(
        adb: IDeviceAdb,
        x: Int,
        y: Int,
        endX: Int,
        endY: Int,
        duration: Int,
        logger: com.intellij.openapi.diagnostic.Logger,
    ): McpToolResult {
        return try {
            adb.execAdbShellCmd("input swipe $x $y $endX $endY $duration")
            McpToolResult(
                status = McpToolStatus.OK,
                message = "swipe executed successfully.",
                data = mapOf(
                    "action" to "swipe",
                    "x" to x,
                    "y" to y,
                    "endX" to endX,
                    "endY" to endY,
                    "duration" to duration,
                    "mode" to "coordinate",
                ),
                artifacts = emptyList(),
                errorCode = null,
            )
        } catch (e: Exception) {
            logger.warn("swipe coordinate failed: ${e.message}", e)
            McpToolResult.internalErrorResult("tap", e.message ?: "unknown error")
        }
    }

    private fun swipeByPercent(
        adb: IDeviceAdb,
        xPercent: Double,
        yPercent: Double,
        endXPercent: Double,
        endYPercent: Double,
        duration: Int,
        logger: com.intellij.openapi.diagnostic.Logger,
    ): McpToolResult {
        return try {
            val screenSize = getScreenSize(adb)
                ?: return screenSizeErrorResult()
            val x = percentToCoordinate(xPercent, screenSize.width)
            val y = percentToCoordinate(yPercent, screenSize.height)
            val endX = percentToCoordinate(endXPercent, screenSize.width)
            val endY = percentToCoordinate(endYPercent, screenSize.height)
            adb.execAdbShellCmd("input swipe $x $y $endX $endY $duration")
            McpToolResult(
                status = McpToolStatus.OK,
                message = "swipe executed successfully.",
                data = mapOf(
                    "action" to "swipe",
                    "x" to x,
                    "y" to y,
                    "endX" to endX,
                    "endY" to endY,
                    "duration" to duration,
                    "mode" to "percent",
                    "screenWidth" to screenSize.width,
                    "screenHeight" to screenSize.height,
                ),
                artifacts = emptyList(),
                errorCode = null,
            )
        } catch (e: Exception) {
            logger.warn("swipe percent failed: ${e.message}", e)
            McpToolResult.internalErrorResult("tap", e.message ?: "unknown error")
        }
    }

    private fun serverResultToToolResult(
        serverResult: FindAndTapResult,
        action: String,
        duration: Int? = null,
        selectorContext: ElementSelectorContext = ElementSelectorContext(),
    ): McpToolResult {
        return when (serverResult) {
            is FindAndTapResult.Success -> {
                val baseData = mutableMapOf<String, Any>(
                    "action" to action,
                    "x" to serverResult.x,
                    "y" to serverResult.y,
                    "mode" to "element",
                    "matchedElement" to toMatchedElementMap(serverResult.matchedElement),
                    "matchCount" to serverResult.matchCount,
                )
                if (duration != null) {
                    baseData["duration"] = duration
                }
                McpToolResult(
                    status = McpToolStatus.OK,
                    message = "$action executed successfully.",
                    data = baseData,
                    artifacts = emptyList(),
                    errorCode = null,
                )
            }

            is FindAndTapResult.Multiple -> {
                val matchesSummary = serverResult.matches.mapIndexed { i, match ->
                    mapOf(
                        "index" to i,
                        "text" to match.text,
                        "resourceId" to match.resourceId,
                        "contentDesc" to match.contentDesc,
                        "className" to match.className,
                        "bounds" to (match.bounds ?: emptyList<Int>()),
                        "centerX" to match.centerX,
                        "centerY" to match.centerY,
                    )
                }
                McpToolResult(
                    status = McpToolStatus.ERROR,
                    message = "tap failed. Reason: ${serverResult.matchCount} elements matched. " +
                        "Use coordinate mode (x+y) or percent mode (xPercent+yPercent) to tap the intended element.",
                    data = mapOf(
                        "action" to action,
                        "matchCount" to serverResult.matchCount,
                        "mode" to "element",
                        "matches" to matchesSummary,
                    ),
                    artifacts = emptyList(),
                    errorCode = McpErrorCode.INVALID_PARAMS,
                )
            }

            is FindAndTapResult.NotFound -> {
                val candidateDesc = buildNotFoundCandidateDesc(serverResult.candidates, selectorContext)
                McpToolResult(
                    status = McpToolStatus.ERROR,
                    message = "tap failed. Reason: No matching UI element found.$candidateDesc",
                    data = mapOf(
                        "action" to action,
                        "matchCount" to 0,
                        "mode" to "element",
                    ),
                    artifacts = emptyList(),
                    errorCode = McpErrorCode.INTERNAL_ERROR,
                )
            }

            is FindAndTapResult.Failure -> {
                McpToolResult(
                    status = McpToolStatus.ERROR,
                    message = "tap failed. Reason: ViewHierarchy server error: ${serverResult.message}",
                    data = mapOf(
                        "action" to action,
                        "mode" to "element",
                    ),
                    artifacts = emptyList(),
                    errorCode = McpErrorCode.INTERNAL_ERROR,
                )
            }
        }
    }

    private fun buildNotFoundCandidateDesc(
        candidates: List<com.sickworm.intellij.jugg.mcp.viewhierarchy.MatchCandidate>,
        selectorContext: ElementSelectorContext,
    ): String {
        if (candidates.isEmpty()) {
            return ""
        }
        val candidateSummaries = candidates.mapNotNull { candidate ->
            buildNotFoundCandidateSummary(candidate, selectorContext)
        }
        if (candidateSummaries.isEmpty()) {
            return ""
        }
        val label = selectorContext.describeSelectedFields()
        return " Available $label candidates: ${candidateSummaries.joinToString("; ")}"
    }

    private fun buildNotFoundCandidateSummary(
        candidate: com.sickworm.intellij.jugg.mcp.viewhierarchy.MatchCandidate,
        selectorContext: ElementSelectorContext,
    ): String? {
        val parts = mutableListOf<String>()
        if (selectorContext.byText && candidate.text.isNotBlank()) {
            parts.add("text=\"${candidate.text}\"")
        }
        if (selectorContext.byResourceId && candidate.resourceId.isNotBlank()) {
            parts.add("resource-id=\"${candidate.resourceId}\"")
        }
        if (selectorContext.byContentDesc && candidate.contentDesc.isNotBlank()) {
            parts.add("content-desc=\"${candidate.contentDesc}\"")
        }
        if (selectorContext.byClassName && candidate.className.isNotBlank()) {
            parts.add("class=\"${candidate.className}\"")
        }
        return if (parts.isEmpty()) null else parts.joinToString(", ")
    }

    private fun toMatchedElementMap(element: MatchedElementData): Map<String, Any> {
        return mapOf(
            "text" to element.text,
            "className" to element.className,
            "resourceId" to element.resourceId,
            "contentDesc" to element.contentDesc,
            "bounds" to element.bounds,
            "centerX" to element.centerX,
            "centerY" to element.centerY,
        )
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
            if (line.trimStart().lowercase().startsWith("override")) {
                break
            }
        }
        return if (width != null && height != null) ScreenSize(width, height) else null
    }

    private fun percentToCoordinate(percent: Double, edgeSize: Int): Int {
        if (edgeSize <= 0) {
            return 0
        }
        val raw = (edgeSize.toDouble() * percent / 100.0).roundToInt()
        return raw.coerceIn(0, edgeSize - 1)
    }

    private fun sanitizeDuration(duration: Int): Int {
        return if (duration < MIN_DURATION_MS) MIN_DURATION_MS else duration
    }

    private fun hasElementSelector(text: String?, resourceId: String?, contentDesc: String?): Boolean {
        return !text.isNullOrBlank() || !resourceId.isNullOrBlank() || !contentDesc.isNullOrBlank()
    }

    private fun invalidModeResult(): McpToolResult {
        return McpToolResult(
            status = McpToolStatus.ERROR,
            message = "tap failed. Reason: No valid tap mode detected. Provide (x+y), (xPercent+yPercent), or (text/resourceId/contentDesc).",
            data = emptyMap<String, Any>(),
            artifacts = emptyList(),
            errorCode = McpErrorCode.INVALID_PARAMS,
        )
    }

    private fun swipeMissingEndResult(): McpToolResult {
        return McpToolResult(
            status = McpToolStatus.ERROR,
            message = "tap failed. Reason: swipe requires both start and end coordinates in the same mode.",
            data = emptyMap<String, Any>(),
            artifacts = emptyList(),
            errorCode = McpErrorCode.INVALID_PARAMS,
        )
    }

    private fun screenSizeErrorResult(): McpToolResult {
        return McpToolResult(
            status = McpToolStatus.ERROR,
            message = "tap failed. Reason: Unable to determine screen size from device.",
            data = emptyMap<String, Any>(),
            artifacts = emptyList(),
            errorCode = McpErrorCode.INTERNAL_ERROR,
        )
    }

    private fun packageNameMissingResult(): McpToolResult {
        return McpToolResult(
            status = McpToolStatus.ERROR,
            message = "tap failed. Reason: Unable to resolve package name for ViewHierarchy server.",
            data = mapOf("mode" to "element"),
            artifacts = emptyList(),
            errorCode = McpErrorCode.INTERNAL_ERROR,
        )
    }

    private fun serverUnavailableResult(action: String): McpToolResult {
        return McpToolResult(
            status = McpToolStatus.ERROR,
            message = "tap failed. Reason: ViewHierarchy server is unavailable.",
            data = mapOf(
                "action" to action,
                "mode" to "element",
            ),
            artifacts = emptyList(),
            errorCode = McpErrorCode.INTERNAL_ERROR,
        )
    }

    private data class ScreenSize(val width: Int, val height: Int)

    private data class SelectedAdb(val adb: IDeviceAdb)

    private data class ElementSelectorContext(
        val byText: Boolean = false,
        val byResourceId: Boolean = false,
        val byContentDesc: Boolean = false,
        val byClassName: Boolean = false,
    ) {
        fun describeSelectedFields(): String {
            val selected = mutableListOf<String>()
            if (byText) selected.add("text")
            if (byResourceId) selected.add("resource-id")
            if (byContentDesc) selected.add("content-desc")
            if (byClassName) selected.add("class")
            if (selected.isEmpty()) {
                return "selector-related"
            }
            return selected.joinToString("+")
        }

        companion object {
            fun from(
                text: String?,
                resourceId: String?,
                contentDesc: String?,
                className: String?,
            ): ElementSelectorContext {
                return ElementSelectorContext(
                    byText = !text.isNullOrBlank(),
                    byResourceId = !resourceId.isNullOrBlank(),
                    byContentDesc = !contentDesc.isNullOrBlank(),
                    byClassName = !className.isNullOrBlank(),
                )
            }
        }
    }

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
            errorCode = McpErrorCode.NO_DEVICE,
        )
    }

    private fun waitTopActivityOnResumeStable(adb: IDeviceAdb): TopActivityStabilityResult {
        val deadline = System.currentTimeMillis() + TOP_ACTIVITY_STABLE_TIMEOUT_MS
        var checks = 0
        var previousStableCandidate: TopActivitySnapshot? = null
        var lastSnapshot: TopActivitySnapshot? = null

        while (System.currentTimeMillis() <= deadline) {
            val snapshot = queryTopActivitySnapshot(adb)
            checks++
            lastSnapshot = snapshot
            if (snapshot != null && snapshot.isOnResume) {
                if (previousStableCandidate != null && previousStableCandidate.activity == snapshot.activity) {
                    return TopActivityStabilityResult(
                        isStable = true,
                        checks = checks,
                        activity = snapshot.activity,
                        state = snapshot.state,
                    )
                }
                previousStableCandidate = snapshot
            } else {
                previousStableCandidate = null
            }
            Thread.sleep(TOP_ACTIVITY_STABLE_INTERVAL_MS)
        }

        return TopActivityStabilityResult(
            isStable = false,
            checks = checks,
            activity = lastSnapshot?.activity,
            state = lastSnapshot?.state,
        )
    }

    private fun queryTopActivitySnapshot(adb: IDeviceAdb): TopActivitySnapshot? {
        val output = adb.execAdbShellCmd(ACTIVITY_DUMP_COMMAND)
        val parsedEntries = ActivityStackMcpToolAction.parseActivityEntries(output)
        val activityLine = TOP_ACTIVITY_KEYWORDS.asSequence()
            .mapNotNull { keyword ->
                output.lineSequence()
                    .firstOrNull { it.contains(keyword) }
                    ?.let { line -> keyword to line }
            }
            .firstOrNull()
        if (activityLine == null) {
            val fallbackEntry = parsedEntries.firstOrNull() ?: return null
            return TopActivitySnapshot(
                activity = fallbackEntry.component,
                state = "unknown",
                isOnResume = false,
            )
        }
        val line = activityLine.second.trim()
        val activity = parsedEntries.firstOrNull { entry -> entry.line == line }?.component
            ?: parsedEntries.firstOrNull { entry -> line.contains(entry.component) }?.component
            ?: return null
        val state = resolveTopActivityState(activityLine.first, line)
        return TopActivitySnapshot(activity = activity, state = state, isOnResume = isOnResumeState(state))
    }

    private fun resolveTopActivityState(keyword: String, line: String): String {
        val stateFromLine = STATE_PATTERN.find(line)?.groupValues?.getOrNull(1)
        if (!stateFromLine.isNullOrBlank()) {
            return stateFromLine
        }
        return if (keyword == "topResumedActivity" || keyword == "mResumedActivity") {
            ON_RESUME_STATE
        } else {
            "unknown"
        }
    }

    private fun isOnResumeState(state: String): Boolean {
        val normalized = state.lowercase()
        return normalized == "resumed" || normalized == "onresume"
    }

    private fun appendTopActivityNotStableHintIfNeeded(
        result: McpToolResult,
        notStable: TopActivityStabilityResult,
    ): McpToolResult {
        if (result.status != McpToolStatus.ERROR) {
            return result
        }
        if (result.errorCode == McpErrorCode.INVALID_PARAMS) {
            return result
        }
        val hint = " topActivity/state is currently not stable (checks=${notStable.checks}, " +
            "topActivity=${notStable.activity ?: ""}, state=${notStable.state ?: "unknown"})."
        val mergedData = (result.data as? Map<*, *>)?.toMutableMap() ?: mutableMapOf()
        mergedData["topActivityStable"] = false
        mergedData["topActivityStableChecks"] = notStable.checks
        mergedData["topActivity"] = notStable.activity ?: ""
        mergedData["topActivityState"] = notStable.state ?: "unknown"
        return result.copy(
            message = result.message + hint,
            data = mergedData,
        )
    }

    private fun resolvePackageName(runtime: IMcpRuntime): String? {
        return try {
            runtime.deployTargetManager.getPackageName().takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            null
        }
    }

    private fun Map<String, Any?>.numberAsInt(key: String): Int? = (this[key] as? Number)?.toInt()

    private fun Map<String, Any?>.numberAsDouble(key: String): Double? = (this[key] as? Number)?.toDouble()

    /** Resolve resourceId, accepting both "resourceId" and short alias "id". */
    private fun Map<String, Any?>.resolveId(): String? =
        (this["resourceId"] as? String)?.takeIf { it.isNotBlank() }
            ?: (this["id"] as? String)?.takeIf { it.isNotBlank() }

    /** Resolve contentDesc, accepting both "contentDesc" and short alias "desc". */
    private fun Map<String, Any?>.resolveDesc(): String? =
        (this["contentDesc"] as? String)?.takeIf { it.isNotBlank() }
            ?: (this["desc"] as? String)?.takeIf { it.isNotBlank() }

    /** Resolve className, accepting both "className" and short alias "class". */
    private fun Map<String, Any?>.resolveClass(): String? =
        (this["className"] as? String)?.takeIf { it.isNotBlank() }
            ?: (this["class"] as? String)?.takeIf { it.isNotBlank() }

    private data class TopActivitySnapshot(
        val activity: String,
        val state: String,
        val isOnResume: Boolean,
    )

    private data class TopActivityStabilityResult(
        val isStable: Boolean,
        val checks: Int,
        val activity: String?,
        val state: String?,
    )

    companion object {
        private const val ACTIVITY_DUMP_COMMAND = "dumpsys activity activities"
        private const val TOP_ACTIVITY_STABLE_TIMEOUT_MS = 5_000L
        private const val TOP_ACTIVITY_STABLE_INTERVAL_MS = 1_000L
        private const val ON_RESUME_STATE = "onResume"
        private val TOP_ACTIVITY_KEYWORDS = listOf("topResumedActivity", "mResumedActivity", "mFocusedActivity")
        private val STATE_PATTERN = Regex("\\bstate=([A-Za-z_]+)\\b")
        private val SIZE_PATTERN = Regex("""(\d+)\s*x\s*(\d+)""")
        private const val DEFAULT_LONG_PRESS_DURATION_MS = 500
        private const val DEFAULT_SWIPE_DURATION_MS = 300
        private const val MIN_DURATION_MS = 50
    }
}
