package com.sickworm.intellij.jugg.ai.mcp

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.jugg.ai.mcp.actions.GlobalMcpToolAction
import com.sickworm.intellij.jugg.ide.controlpanel.JuggControlPanelModel
import com.sickworm.intellij.jugg.ide.controlpanel.JuggEvent
import java.util.UUID

/**
 * McpToolInvoker validates MCP JSON-RPC requests, dispatches tool actions, and maps execution results to JSON-RPC responses.
 * Collaboration: Uses [McpRequestValidator] for protocol/argument validation, resolves executors from [McpToolRegistry], and serializes success/error payloads through [McpResultMapper].
 * Data Contract: Every request is validated before dispatch; missing tools map to [McpErrorCode.TOOL_NOT_FOUND]; action results (including [McpToolStatus.ERROR]) are returned via toolSuccess (isError=false) so that structuredContent carries full artifacts and data, while protocol-level errors use toolError (isError=true).
 */
class McpToolInvoker(
    currentProjectDir: String,
    private val runtime: IMcpRuntime,
    private val toolRegistry: McpToolRegistry = McpToolRegistry(),
    private val resultMapper: McpResultMapper = McpResultMapper(),
    private val eventModel: JuggControlPanelModel? = null,
) : IMcpInvoker {
    companion object {
        private const val MAX_PANEL_CONTENT_LENGTH = 4_096
        private const val REDACTED = "[REDACTED]"
        private val SENSITIVE_KEYS = setOf(
            "password",
            "token",
            "secret",
            "authorization",
            "apikey",
            "privatekey",
            "credential",
            "environmentvariables",
        )
    }

    private val logger = Logger.getInstance("McpToolInvoker")
    private val gson = Gson()
    private val requestValidator = McpRequestValidator(currentProjectDir, toolRegistry)

    @Synchronized
    override fun invokeMcp(request: McpJsonRpcRequest): McpJsonRpcResponse {
        logger.debug("[MCP][TOOL][IN ] method=${request.method}, id=${request.id}")

        return when (val validated = requestValidator.validate(request)) {
            is McpValidationResult.ToolsList -> resultMapper.toolsList(request.id, toolRegistry.listTools())
            is McpValidationResult.ToolsCall -> handleToolsCall(request.id, validated)
            is McpValidationResult.Invalid -> {
                logger.debug("[MCP][TOOL] request invalid: ${validated.message}, code=${validated.errorCode}")
                if (validated.isJsonRpcError) {
                    resultMapper.jsonRpcError(request.id, validated.errorCode, validated.message, validated.jsonRpcCode)
                } else {
                    resultMapper.toolError(request.id, validated.errorCode, validated.message)
                }
            }
        }
    }

    private fun handleToolsCall(id: Any?, request: McpValidationResult.ToolsCall): McpJsonRpcResponse {
        logger.debug("[MCP][TOOL] tools/call name=${request.toolName}, projectDir=${request.projectDir}")
        val action = toolRegistry.getAction(request.toolName)
            ?: return resultMapper.toolError(
                id = id,
                errorCode = McpErrorCode.TOOL_NOT_FOUND,
                message = "Tool not found: ${request.toolName}",
            )

        val taskId = UUID.randomUUID().toString()
        val startTime = System.currentTimeMillis()
        recordStarted(taskId, request, startTime)
        val toolResult = try {
            if (action is GlobalMcpToolAction) {
                action.executeGlobal(toolRegistry)
            } else {
                action.execute(request.arguments, runtime)
            }
        } catch (e: Throwable) {
            recordCompleted(taskId, request.toolName, startTime, McpToolResult.internalErrorResult(request.toolName, e.message.orEmpty()))
            throw e
        }
        recordCompleted(taskId, request.toolName, startTime, toolResult)
        // Business-level errors (where the tool executed but results were unexpected) should consistently use
        // toolSuccess, with success/failure distinguished via structuredContent.status.
        // This prevents isError=true from causing the MCP client to display them as framework-level
        // errors, while still preserving full data like artifacts.
        return resultMapper.toolSuccess(id = id, toolResult = toolResult)
    }

    private fun recordStarted(taskId: String, request: McpValidationResult.ToolsCall, startTime: Long) {
        eventModel?.record(JuggEvent(
            taskId = taskId,
            source = JuggEvent.Source.MCP,
            category = JuggEvent.Category.MCP,
            phase = JuggEvent.Phase.PREPARING,
            status = JuggEvent.Status.STARTED,
            level = JuggEvent.Level.INFO,
            title = "MCP request",
            detail = "${request.toolName} · ${formatPanelContent(request.arguments)}",
            timestamp = startTime,
        ))
    }

    private fun recordCompleted(taskId: String, toolName: String, startTime: Long, toolResult: McpToolResult) {
        val succeeded = toolResult.status != McpToolStatus.ERROR
        val response = linkedMapOf<String, Any?>(
            "status" to toolResult.status,
            "message" to toolResult.message,
            "data" to toolResult.data,
            "artifacts" to toolResult.artifacts,
            "errorCode" to toolResult.errorCode,
        )
        eventModel?.record(JuggEvent(
            taskId = taskId,
            source = JuggEvent.Source.MCP,
            category = JuggEvent.Category.MCP,
            phase = JuggEvent.Phase.COMPLETED,
            status = if (succeeded) JuggEvent.Status.SUCCEEDED else JuggEvent.Status.FAILED,
            level = if (succeeded) JuggEvent.Level.INFO else JuggEvent.Level.WARN,
            title = "MCP response",
            detail = "$toolName · ${formatPanelContent(response)}",
            durationMillis = System.currentTimeMillis() - startTime,
            isTaskTerminal = true,
        ))
    }

    private fun formatPanelContent(content: Any?): String {
        val text = gson.toJson(sanitize(gson.toJsonTree(content)))
        return if (text.length <= MAX_PANEL_CONTENT_LENGTH) text else text.take(MAX_PANEL_CONTENT_LENGTH - 1) + "…"
    }

    private fun sanitize(element: JsonElement): JsonElement {
        return when {
            element.isJsonObject -> JsonObject().apply {
                element.asJsonObject.entrySet().forEach { (key, value) ->
                    val normalizedKey = key.lowercase().replace("_", "").replace("-", "")
                    when {
                        normalizedKey == "projectdir" -> Unit
                        SENSITIVE_KEYS.any(normalizedKey::contains) -> add(key, JsonPrimitive(REDACTED))
                        else -> add(key, sanitize(value))
                    }
                }
            }
            element.isJsonArray -> JsonArray().apply { element.asJsonArray.forEach { add(sanitize(it)) } }
            else -> element
        }
    }

}
