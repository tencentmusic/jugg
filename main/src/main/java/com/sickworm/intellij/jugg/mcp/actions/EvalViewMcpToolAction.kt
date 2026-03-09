package com.sickworm.intellij.jugg.mcp.actions

import com.sickworm.intellij.jugg.deploy.IDeviceAdb
import com.sickworm.intellij.jugg.logger.getInstance
import com.sickworm.intellij.jugg.mcp.DeviceSelectionResolver
import com.sickworm.intellij.jugg.mcp.DeviceSelectionResult
import com.sickworm.intellij.jugg.mcp.IMcpRuntime
import com.sickworm.intellij.jugg.mcp.McpErrorCode
import com.sickworm.intellij.jugg.mcp.McpJsonSchemaObject
import com.sickworm.intellij.jugg.mcp.McpJsonSchemaProperty
import com.sickworm.intellij.jugg.mcp.McpToolDefinition
import com.sickworm.intellij.jugg.mcp.McpToolResult
import com.sickworm.intellij.jugg.mcp.McpToolStatus
import com.sickworm.intellij.jugg.mcp.viewhierarchy.ViewHierarchyClient
import com.sickworm.intellij.jugg.platform.PlatformApi

/**
 * EvalViewMcpToolAction implements MCP tool `eval_view` that evaluates getter method
 * expressions on a matched View element via reflective invocation on the app side.
 */
class EvalViewMcpToolAction : McpToolAction {
    override val toolName: String = "eval_view"

    override val definition: McpToolDefinition = McpToolDefinition(
        name = toolName,
        description = "Evaluate getter method expressions on a matched View element. " +
            "Use Android SDK View/TextView/ImageView public getter methods. " +
            "Returns raw values for the agent to interpret. " +
            "Only read-only getter methods are allowed (no side effects).",
        inputSchema = McpJsonSchemaObject(
            properties = mapOf(
                "projectDir" to McpToolSchemas.projectDirProperty,
                "target" to McpJsonSchemaProperty(
                    type = "object",
                    description = "Element selector. Same selector format as layout_verify/tap element mode.",
                    properties = mapOf(
                        "resourceId" to McpJsonSchemaProperty(
                            type = "string",
                            description = "Resource ID to match. Prefer short id (e.g. 'btn_play').",
                        ),
                        "text" to McpJsonSchemaProperty(
                            type = "string",
                            description = "Text content to match. Exact match only.",
                        ),
                        "contentDesc" to McpJsonSchemaProperty(
                            type = "string",
                            description = "Content description to match. Exact match only.",
                        ),
                        "className" to McpJsonSchemaProperty(
                            type = "string",
                            description = "Class name filter (AND logic with other selectors).",
                        ),
                    ),
                    additionalProperties = false,
                ),
                "expressions" to McpJsonSchemaProperty(
                    type = "array",
                    description = "Getter method expressions to evaluate on the matched View. " +
                        "Each expression is a dot-separated method chain. " +
                        "Examples: 'getText()', 'getCurrentTextColor()', " +
                        "'getBackground().getClass().getSimpleName()', 'isEnabled()', 'getMaxLines()'",
                    items = McpJsonSchemaProperty(type = "string"),
                ),
            ),
            required = listOf("projectDir", "target", "expressions"),
            additionalProperties = false,
        ),
        outputSchema = McpToolSchemas.baseOutputSchema,
    )

    @OptIn(ExperimentalStdlibApi::class)
    override fun execute(arguments: Map<String, Any?>, runtime: IMcpRuntime): McpToolResult {
        val logger = runtime.logger.getInstance("EvalViewMcpToolAction")

        // Validate target
        val targetRaw = arguments["target"]
        if (targetRaw == null || targetRaw !is Map<*, *>) {
            return McpToolResult(
                status = McpToolStatus.ERROR,
                message = "eval_view failed. Reason: 'target' is required and must be an object.",
                errorCode = McpErrorCode.MCP_INVALID_PARAMS,
            )
        }
        @Suppress("UNCHECKED_CAST")
        val target = targetRaw as Map<String, Any?>
        val resourceId = target["resourceId"] as? String
        val text = target["text"] as? String
        val contentDesc = target["contentDesc"] as? String
        val className = target["className"] as? String

        if (resourceId.isNullOrBlank() && text.isNullOrBlank()
            && contentDesc.isNullOrBlank() && className.isNullOrBlank()
        ) {
            return McpToolResult(
                status = McpToolStatus.ERROR,
                message = "eval_view failed. Reason: target must have at least one selector " +
                    "(resourceId, text, contentDesc, or className).",
                errorCode = McpErrorCode.MCP_INVALID_PARAMS,
            )
        }

        // Validate expressions
        val expressionsRaw = arguments["expressions"]
        if (expressionsRaw == null || expressionsRaw !is List<*> || expressionsRaw.isEmpty()) {
            return McpToolResult(
                status = McpToolStatus.ERROR,
                message = "eval_view failed. Reason: 'expressions' is required and must be a non-empty array.",
                errorCode = McpErrorCode.MCP_INVALID_PARAMS,
            )
        }
        val expressions = expressionsRaw.filterIsInstance<String>()
        if (expressions.isEmpty()) {
            return McpToolResult(
                status = McpToolStatus.ERROR,
                message = "eval_view failed. Reason: 'expressions' must contain string values.",
                errorCode = McpErrorCode.MCP_INVALID_PARAMS,
            )
        }
        if (expressions.size > MAX_EXPRESSIONS) {
            return McpToolResult(
                status = McpToolStatus.ERROR,
                message = "eval_view failed. Reason: expressions count ${expressions.size} " +
                    "exceeds maximum $MAX_EXPRESSIONS.",
                errorCode = McpErrorCode.MCP_INVALID_PARAMS,
            )
        }

        // Resolve device
        val selected = resolveOnlineDevice(runtime)
            ?: run {
                logger.warn("eval_view failed: no online device")
                return McpToolResult(
                    status = McpToolStatus.ERROR,
                    message = "eval_view failed. Reason: No connected device is available.",
                    errorCode = McpErrorCode.MCP_NO_DEVICE,
                )
            }

        // Wait for app ready
        val preWaitResult = McpAppReadyGuard.waitBeforeRuntimeObserve(runtime, toolName)
        if (!preWaitResult.isReady) {
            logger.warn("eval_view failed: app not ready")
            return preWaitResult.errorResult
                ?: McpToolResult.internalErrorResult("eval_view", "app is not ready")
        }

        val packageName = resolvePackageName(runtime)
            ?: run {
                logger.warn("eval_view failed: package name is empty")
                return McpToolResult.internalErrorResult(
                    "eval_view",
                    "failed to resolve package name for ViewHierarchy server"
                )
            }

        return McpAppReadyGuard.executeWithRetryIfPreWaited(preWaitResult) {
            try {
                val client = ViewHierarchyClient(selected.adb, packageName)
                val evalResult = client.evalView(text, resourceId, contentDesc, className, expressions)
                    ?: return@executeWithRetryIfPreWaited McpToolResult.internalErrorResult(
                        "eval_view",
                        "ViewHierarchy server is unavailable or returned invalid response"
                    )

                if (evalResult.errorMessage != null) {
                    return@executeWithRetryIfPreWaited McpToolResult(
                        status = McpToolStatus.ERROR,
                        message = "eval_view failed. Reason: ${evalResult.errorMessage}",
                        errorCode = McpErrorCode.MCP_INTERNAL_ERROR,
                    )
                }

                val valuesData = expressions.mapIndexed { index, _ ->
                    val v = evalResult.values.getOrNull(index)
                    if (v != null) {
                        buildMap {
                            put("expression", v.expression)
                            put("value", v.value?.toString())
                            put("type", v.type)
                            if (v.error != null) {
                                put("error", v.error)
                            }
                        }
                    } else {
                        mapOf(
                            "expression" to expressions[index],
                            "value" to null,
                            "type" to "error",
                            "error" to "no result returned",
                        )
                    }
                }

                val data = mapOf(
                    "className" to evalResult.className,
                    "resourceId" to evalResult.resourceId,
                    "density" to evalResult.density,
                    "values" to valuesData,
                )

                val errorCount = evalResult.values.count { it.error != null }
                val message = if (errorCount == 0) {
                    "eval_view executed successfully. ${evalResult.values.size} expressions evaluated."
                } else {
                    "eval_view completed with $errorCount error(s) out of ${evalResult.values.size} expressions."
                }

                McpToolResult(
                    status = McpToolStatus.OK,
                    message = message,
                    data = data,
                )
            } catch (e: Exception) {
                logger.warn("eval_view failed with exception: ${e.message}", e)
                McpToolResult.internalErrorResult("eval_view", e.message ?: "unknown error")
            }
        }
    }

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

    private fun resolvePackageName(runtime: IMcpRuntime): String? {
        return try {
            runtime.deployTargetManager.getPackageName().takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        private const val MAX_EXPRESSIONS = 20
    }
}
