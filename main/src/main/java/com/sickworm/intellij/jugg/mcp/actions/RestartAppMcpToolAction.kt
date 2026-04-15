package com.sickworm.intellij.jugg.mcp.actions

import com.android.ddmlib.IDevice
import com.sickworm.intellij.jugg.mcp.DeviceSelectionResolver
import com.sickworm.intellij.jugg.mcp.DeviceSelectionResult
import com.sickworm.intellij.jugg.mcp.IMcpRuntime
import com.sickworm.intellij.jugg.mcp.McpErrorCode
import com.sickworm.intellij.jugg.mcp.McpJsonSchemaObject
import com.sickworm.intellij.jugg.mcp.McpJsonSchemaProperty
import com.sickworm.intellij.jugg.mcp.McpToolDefinition
import com.sickworm.intellij.jugg.mcp.McpToolResult
import com.sickworm.intellij.jugg.mcp.McpToolStatus

/**
 * RestartAppMcpToolAction implements MCP tool `restart` and converts request arguments into tool execution and MCP result payloads.
 */
class RestartAppMcpToolAction : McpToolAction {
    override val toolName: String = McpToolActionRegistry.ToolNames.RESTART

    override val definition: McpToolDefinition = McpToolDefinition(
        name = toolName,
        description = "Restart app process on IDE selected device(s).",
        inputSchema = McpJsonSchemaObject(
            properties = mapOf(
                "projectDir" to McpToolSchemas.projectDirProperty,
                "tap_actions" to McpJsonSchemaProperty(
                    type = "array",
                    description = "Optional post-restart touch steps. Each step supports the same action/mode arguments as tool tap " +
                        "(tap/longPress/swipe, coordinate/percent/element selectors) and runs sequentially.",
                    items = McpToolSchemas.tapActionStepProperty,
                ),
            ),
            required = listOf("projectDir"),
            additionalProperties = false,
        ),
        outputSchema = McpToolSchemas.baseOutputSchema,
    )

    override fun execute(arguments: Map<String, Any?>, runtime: IMcpRuntime): McpToolResult {
        return restartAppAction(arguments, runtime)
    }

    private fun restartAppAction(arguments: Map<String, Any?>, runtime: IMcpRuntime): McpToolResult {
        val targetDevice = resolveOnlineDevice(runtime) ?: return noDeviceResult()
        val isSuccess = runtime.deployTargetManager.restartApp(targetDevice)
        if (!isSuccess) {
            return McpToolResult(
                status = McpToolStatus.ERROR,
                message = "restart failed. Reason: Failed to restart app. Please check log in \\\$PROJECT_DIR/build/jugg/log/compile_latest.log\"",
                data = emptyMap<String, Any>(),
                artifacts = emptyList(),
                errorCode = McpErrorCode.MCP_INTERNAL_ERROR,
            )
        }
        val waitResult = McpAppReadyGuard.waitAfterMutating(runtime, toolName)
        if (!waitResult.isReady) {
            return McpToolResult(
                status = McpToolStatus.ERROR,
                message = waitResult.reason ?: "restart failed. Reason: app is not ready after restart.",
                data = mapOf("readyChecks" to waitResult.checks),
                artifacts = emptyList(),
                errorCode = McpErrorCode.MCP_INTERNAL_ERROR,
            )
        }
        val tapActions = parseTapActions(arguments)
        if (tapActions.isNotEmpty()) {
            val postTapError = executeTapActions(arguments, tapActions, runtime)
            if (postTapError != null) {
                return postTapError
            }
        }

        val successMessage = if (tapActions.isEmpty()) {
            "restart executed successfully."
        } else {
            "restart executed successfully. tap_actions executed: ${tapActions.size}."
        }
        return McpToolResult(
            status = McpToolStatus.OK,
            message = successMessage,
            data = emptyMap<String, Any>(),
            artifacts = emptyList(),
            errorCode = null,
        )
    }

    /**
     * Execute post-restart tap_actions with small retries for transient element-not-found cases.
     */
    private fun executeTapActions(
        arguments: Map<String, Any?>,
        tapActions: List<Map<String, Any?>>,
        runtime: IMcpRuntime,
    ): McpToolResult? {
        val projectDir = arguments["projectDir"] as? String
            ?:             return McpToolResult(
                status = McpToolStatus.ERROR,
                message = "restart failed. Reason: projectDir is required for tap_actions.",
                data = emptyMap<String, Any>(),
                artifacts = emptyList(),
                errorCode = McpErrorCode.MCP_INVALID_PARAMS,
            )
        for ((index, tapAction) in tapActions.withIndex()) {
            val step = index + 1
            val tapResult = executeTapActionWithRetry(projectDir, tapAction, runtime)
            if (tapResult.status != McpToolStatus.OK) {
                return tapActionFailedResult(step, tapResult)
            }
        }
        return null
    }

    private fun executeTapActionWithRetry(
        projectDir: String,
        tapAction: Map<String, Any?>,
        runtime: IMcpRuntime,
    ): McpToolResult {
        var lastResult: McpToolResult? = null
        repeat(MAX_TAP_ACTION_ATTEMPTS) { attempt ->
            val tapArguments = mutableMapOf<String, Any?>("projectDir" to projectDir)
            tapArguments.putAll(tapAction)
            val tapResult = TapMcpToolAction().execute(tapArguments, runtime)
            if (tapResult.status == McpToolStatus.OK) {
                return tapResult
            }
            lastResult = tapResult
            val isLastAttempt = attempt == MAX_TAP_ACTION_ATTEMPTS - 1
            if (!shouldRetryTapAction(tapResult) || isLastAttempt) {
                return tapResult
            }
            sleepForTapActionRetry(TAP_ACTION_RETRY_DELAY_MS)
        }
        return lastResult ?: McpToolResult.internalErrorResult("restart", "tap_actions failed without result")
    }

    private fun shouldRetryTapAction(result: McpToolResult): Boolean {
        if (result.status != McpToolStatus.ERROR || result.errorCode != McpErrorCode.MCP_INTERNAL_ERROR) {
            return false
        }
        val data = result.data as? Map<*, *> ?: return false
        if (data["mode"] != "element") {
            return false
        }
        return result.message.contains(NO_MATCHING_ELEMENT_HINT, ignoreCase = true)
    }

    private fun sleepForTapActionRetry(delayMs: Long) {
        val testSleep = McpAppReadyGuard.sleepForTest
        if (testSleep != null) {
            testSleep(delayMs)
            return
        }
        Thread.sleep(delayMs)
    }

    private fun tapActionFailedResult(step: Int, tapResult: McpToolResult): McpToolResult {
        return McpToolResult(
            status = McpToolStatus.ERROR,
            message = "restart failed. Reason: tap_actions step $step failed. ${tapResult.message}",
            data = mapOf(
                "failedStep" to step,
                "stepMessage" to tapResult.message,
            ),
            artifacts = emptyList(),
            errorCode = tapResult.errorCode ?: McpErrorCode.MCP_INTERNAL_ERROR,
        )
    }

    private fun parseTapActions(arguments: Map<String, Any?>): List<Map<String, Any?>> {
        val raw = arguments["tap_actions"] as? List<*> ?: return emptyList()
        return raw.mapNotNull { item ->
            val map = item as? Map<*, *> ?: return@mapNotNull null
            map.entries
                .filter { it.key is String }
                .associate { it.key as String to it.value }
        }
    }

    private fun resolveOnlineDevice(runtime: IMcpRuntime): IDevice? {
        val selectionResult = DeviceSelectionResolver().resolve(runtime.deployTargetManager)
        if (selectionResult !is DeviceSelectionResult.Selected) {
            return null
        }
        return selectionResult.device
    }

    private fun noDeviceResult(): McpToolResult {
        return McpToolResult(
            status = McpToolStatus.ERROR,
            message = "restart failed. Reason: No connected device is available.",
            data = emptyMap<String, Any>(),
            artifacts = emptyList(),
            errorCode = McpErrorCode.MCP_NO_DEVICE,
        )
    }

    companion object {
        private const val MAX_TAP_ACTION_ATTEMPTS = 3
        private const val TAP_ACTION_RETRY_DELAY_MS = 300L
        private const val NO_MATCHING_ELEMENT_HINT = "No matching UI element found"
    }
}
