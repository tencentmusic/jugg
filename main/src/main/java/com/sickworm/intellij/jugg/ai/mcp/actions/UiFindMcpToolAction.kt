package com.sickworm.intellij.jugg.ai.mcp.actions

import com.sickworm.intellij.jugg.ai.mcp.DeviceSelectionResolver
import com.sickworm.intellij.jugg.ai.mcp.DeviceSelectionResult
import com.sickworm.intellij.jugg.ai.mcp.IMcpRuntime
import com.sickworm.intellij.jugg.ai.mcp.McpErrorCode
import com.sickworm.intellij.jugg.ai.mcp.McpJsonSchemaObject
import com.sickworm.intellij.jugg.ai.mcp.McpJsonSchemaProperty
import com.sickworm.intellij.jugg.ai.mcp.McpToolDefinition
import com.sickworm.intellij.jugg.ai.mcp.McpToolResult
import com.sickworm.intellij.jugg.ai.mcp.McpToolStatus
import com.sickworm.intellij.jugg.ai.mcp.viewhierarchy.FindElementsResult
import com.sickworm.intellij.jugg.ai.mcp.viewhierarchy.MatchCandidate
import com.sickworm.intellij.jugg.ai.mcp.viewhierarchy.SourceLocation
import com.sickworm.intellij.jugg.ai.mcp.viewhierarchy.ViewHierarchyClient
import com.sickworm.intellij.jugg.deploy.IDeviceAdb
import com.sickworm.intellij.jugg.logger.getInstance
import com.sickworm.intellij.jugg.platform.PlatformApi

/**
 * UiFindMcpToolAction locates live UI nodes with the shared selector contract.
 */
@OptIn(ExperimentalStdlibApi::class)
class UiFindMcpToolAction : McpToolAction {
    override val toolName: String = McpToolActionRegistry.ToolNames.VIEW_LOCATE

    override val definition: McpToolDefinition = McpToolDefinition(
        name = toolName,
        description = "Locate live UI elements and return bounds, position, size, and best-effort source location. " +
            "All non-empty selectors use AND logic. " +
            "✅ Use for: spacing calculation, alignment checks, and locating elements on screen. " +
            "❌ Do NOT use for: View-internal properties such as maxLines or ellipsize — use view-inspect instead.",
        inputSchema = McpJsonSchemaObject(
            properties = mapOf(
                "projectDir" to McpToolSchemas.projectDirProperty,
                "target" to McpJsonSchemaProperty(
                    type = "object",
                    description = "Element selector. All non-empty fields use AND logic.",
                    properties = mapOf(
                        "text" to McpJsonSchemaProperty(
                            type = "string",
                            description = "Visible text. Exact match only.",
                        ),
                        "resourceId" to McpJsonSchemaProperty(
                            type = "string",
                            description = "Full or short resource ID. Exact match only.",
                        ),
                        "contentDesc" to McpJsonSchemaProperty(
                            type = "string",
                            description = "Content description. Exact match only.",
                        ),
                        "className" to McpJsonSchemaProperty(
                            type = "string",
                            description = "Full or simple class name. Exact match only.",
                        ),
                    ),
                    additionalProperties = false,
                ),
                "visibleOnly" to McpJsonSchemaProperty(
                    type = "boolean",
                    description = "Only return visible elements.",
                    default = true,
                ),
                "maxResults" to McpJsonSchemaProperty(
                    type = "integer",
                    description = "Maximum number of matches returned.",
                    default = 10,
                    minimum = 1.0,
                    maximum = 100.0,
                ),
                "figmaNode" to McpJsonSchemaProperty(
                    type = "object",
                    description = "Deprecated compatibility field. Ignored by view-locate.",
                ),
            ),
            required = listOf("projectDir", "target"),
            additionalProperties = false,
        ),
        outputSchema = McpToolSchemas.baseOutputSchema,
    )

    override fun execute(arguments: Map<String, Any?>, runtime: IMcpRuntime): McpToolResult {
        val target = arguments["target"] as? Map<*, *>
            ?: return invalidParams("'target' is required and must be an object")
        val maxResults = parseMaxResults(arguments["maxResults"])
        if (maxResults == null) {
            return invalidParams(
                "maxResults must be an integer between $MIN_MAX_RESULTS and $MAX_MAX_RESULTS"
            )
        }
        val request = parseRequest(
            target,
            arguments["visibleOnly"] as? Boolean ?: true,
            maxResults,
        ) ?: return invalidParams("target must have at least one selector")
        val adb = resolveOnlineAdb(runtime) ?: return noDeviceResult()
        val preWaitResult = McpAppReadyGuard.waitBeforeRuntimeObserve(runtime, toolName)
        if (!preWaitResult.isReady) {
            return preWaitResult.errorResult
                ?: McpToolResult.internalErrorResult(toolName, "app is not ready")
        }
        val packageName = runCatching { runtime.deployTargetManager.getPackageName() }
            .getOrNull()?.takeIf { it.isNotBlank() }
            ?: return McpToolResult.internalErrorResult(toolName, "failed to resolve package name")
        return executeLookup(runtime, adb, packageName, request)
    }

    private fun parseRequest(
        target: Map<*, *>,
        visibleOnly: Boolean,
        maxResults: Int,
    ): LocateRequest? {
        val text = target.nonBlankString("text")
        val resourceId = target.nonBlankString("resourceId")
        val contentDesc = target.nonBlankString("contentDesc")
        val className = target.nonBlankString("className")
        if (text == null && resourceId == null && contentDesc == null && className == null) {
            return null
        }
        return LocateRequest(text, resourceId, contentDesc, className, visibleOnly, maxResults)
    }

    private fun resolveOnlineAdb(runtime: IMcpRuntime) =
        (DeviceSelectionResolver().resolve(runtime.deployTargetManager) as? DeviceSelectionResult.Selected)
            ?.device
            ?.let { PlatformApi.toDeviceAdb(it) }
            ?.takeIf { it.isOnline }

    private fun executeLookup(
        runtime: IMcpRuntime,
        adb: IDeviceAdb,
        packageName: String,
        request: LocateRequest,
    ): McpToolResult {
        val logger = runtime.logger.getInstance("UiFindMcpToolAction")
        return McpAppReadyGuard.executeWithRuntimeObserveRetry {
            try {
                val result = ViewHierarchyClient(adb, packageName).findElements(
                    request.text,
                    request.resourceId,
                    request.contentDesc,
                    request.className,
                    request.visibleOnly,
                    request.maxResults,
                ) ?: return@executeWithRuntimeObserveRetry ViewHierarchyFailureDiagnoser.unavailableResult(
                    toolName = toolName,
                    adb = adb,
                    packageName = packageName,
                    fallbackMessage = "ViewHierarchy server is unavailable or returned invalid response",
                )
                if (result.errorMessage != null) {
                    return@executeWithRuntimeObserveRetry ViewHierarchyFailureDiagnoser.toolError(
                        toolName,
                        result.errorMessage,
                    )
                }
                buildResult(result)
            } catch (e: Exception) {
                logger.warn("$toolName failed: ${e.message}", e)
                McpToolResult.internalErrorResult(toolName, e.message ?: "unknown error")
            }
        }
    }

    private data class LocateRequest(
        val text: String?,
        val resourceId: String?,
        val contentDesc: String?,
        val className: String?,
        val visibleOnly: Boolean,
        val maxResults: Int,
    )

    private fun buildResult(result: FindElementsResult): McpToolResult {
        if (result.matchCount == 0) {
            return McpToolResult(
                status = McpToolStatus.ERROR,
                message = "Element not found",
                data = mapOf(
                    "found" to false,
                    "matchCount" to 0,
                    "returnedCount" to result.returnedCount,
                    "truncated" to result.truncated,
                    "matches" to emptyList<Any>(),
                ),
                errorCode = "ELEMENT_NOT_FOUND",
            )
        }

        val data = linkedMapOf<String, Any?>(
            "found" to true,
            "matchCount" to result.matchCount,
            "returnedCount" to result.returnedCount,
            "truncated" to result.truncated,
            "matches" to result.matches.map { it.toOutput(result.density) },
        )
        if (result.matchCount == 1) {
            result.matches.singleOrNull()?.let { match ->
                match.bounds?.let { bounds ->
                    val boundsDp = bounds.map { pxToDp(it, result.density) }
                    data["bounds"] = boundsDp
                    data["position"] = mapOf("x" to boundsDp[0], "y" to boundsDp[1])
                    data["size"] = mapOf(
                        "width" to boundsDp[2] - boundsDp[0],
                        "height" to boundsDp[3] - boundsDp[1],
                    )
                }
                data["className"] = match.className
                data["resourceId"] = match.resourceId
                match.source?.toOutput()?.let { data["source"] = it }
            }
        }
        return McpToolResult(
            status = McpToolStatus.OK,
            message = if (result.matchCount == 1) "Element found" else {
                "Element found with ${result.matchCount} matches"
            },
            data = data,
        )
    }

    private fun MatchCandidate.toOutput(density: Double): Map<String, Any?> {
        return buildMap {
            put("resourceId", resourceId)
            put("text", text)
            put("contentDesc", contentDesc)
            put("className", className)
            bounds?.let { put("bounds", it.map { value -> pxToDp(value, density) }) }
            source?.toOutput()?.let { put("source", it) }
        }
    }

    private fun SourceLocation.toOutput(): Map<String, Any> {
        return buildMap {
            file?.takeIf { it.isNotBlank() }?.let { put("file", it) }
            line?.takeIf { it > 0 }?.let { put("line", it) }
        }
    }

    private fun Map<*, *>.nonBlankString(key: String): String? {
        return (this[key] as? String)?.trim()?.takeIf { it.isNotEmpty() }
    }

    private fun parseMaxResults(value: Any?): Int? {
        if (value == null) {
            return DEFAULT_MAX_RESULTS
        }
        val number = value as? Number ?: return null
        val doubleValue = number.toDouble()
        if (doubleValue % 1.0 != 0.0) {
            return null
        }
        val parsed = doubleValue.toInt()
        return parsed.takeIf { it in MIN_MAX_RESULTS..MAX_MAX_RESULTS }
    }

    private fun pxToDp(px: Int, density: Double): Int {
        return if (density > 0.0) (px / density).toInt() else px
    }

    private fun invalidParams(reason: String): McpToolResult {
        return McpToolResult(
            status = McpToolStatus.ERROR,
            message = "$toolName failed. Reason: $reason.",
            errorCode = McpErrorCode.INVALID_PARAMS,
        )
    }

    private fun noDeviceResult(): McpToolResult {
        return McpToolResult(
            status = McpToolStatus.ERROR,
            message = "$toolName failed. Reason: No connected device is available.",
            errorCode = McpErrorCode.NO_DEVICE,
        )
    }

    companion object {
        private const val DEFAULT_MAX_RESULTS = 10
        private const val MIN_MAX_RESULTS = 1
        private const val MAX_MAX_RESULTS = 100
    }
}
