package com.sickworm.intellij.jugg.ai.mcp

import com.intellij.openapi.diagnostic.Logger

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
) : IMcpInvoker {
    private val logger = Logger.getInstance("McpToolInvoker")
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

        val toolResult = action.execute(request.arguments, runtime)
        // Business-level errors (where the tool executed but results were unexpected) should consistently use
        // toolSuccess, with success/failure distinguished via structuredContent.status.
        // This prevents isError=true from causing the MCP client to display them as framework-level
        // errors, while still preserving full data like artifacts.
        return resultMapper.toolSuccess(id = id, toolResult = toolResult)
    }

}
