package com.sickworm.intellij.jugg.ai.mcp

import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.jugg.ai.mcp.actions.GlobalMcpToolAction
import com.sickworm.intellij.jugg.ai.mcp.actions.McpToolActionRegistry

/**
 * McpBaseInvoker invokes mcp operations and maps outputs/errors.
 */
class McpBaseInvoker(
    private val toolRegistry: McpToolRegistry = McpToolRegistry(),
    private val resultMapper: McpResultMapper = McpResultMapper(),
) : IMcpInvoker {
    private val logger = Logger.getInstance("GlobalMcpInvoker")
    private val requestValidator = McpRequestValidator("", toolRegistry)

    @Synchronized
    override fun invokeMcp(request: McpJsonRpcRequest): McpJsonRpcResponse {
        logger.debug("[MCP][GLOBAL][IN ] method=${request.method}, id=${request.id}")
        val commonResponse = handleCommonMethods(request) ?: return commonResponseOrValidate(request)
        return commonResponse
    }

    private fun commonResponseOrValidate(request: McpJsonRpcRequest): McpJsonRpcResponse {
        return when (val validated = requestValidator.validate(request)) {
            is McpValidationResult.ToolsList -> resultMapper.toolsList(request.id, toolRegistry.listTools())
            is McpValidationResult.ToolsCall -> handleToolsCallWithoutRuntime(request.id, validated)
            is McpValidationResult.Invalid -> {
                logger.debug("[MCP][GLOBAL] request invalid: ${validated.message}, code=${validated.errorCode}")
                if (validated.isJsonRpcError) {
                    resultMapper.jsonRpcError(request.id, validated.errorCode, validated.message, validated.jsonRpcCode)
                } else {
                    resultMapper.toolError(request.id, validated.errorCode, validated.message)
                }
            }
        }
    }

    private fun handleToolsCallWithoutRuntime(id: Any?, request: McpValidationResult.ToolsCall): McpJsonRpcResponse {
        logger.debug("[MCP][GLOBAL] tools/call name=${request.toolName}, projectDir=${request.projectDir}")

        if (request.toolName !in McpToolActionRegistry.noProjectDirTools) {
            return resultMapper.toolError(
                id = id,
                errorCode = McpErrorCode.INTERNAL_ERROR,
                message = "MCP runtime is not initialized.",
            )
        }

        val action = toolRegistry.getAction(request.toolName) as? GlobalMcpToolAction
            ?: return resultMapper.toolError(
                id = id,
                errorCode = McpErrorCode.TOOL_NOT_FOUND,
                message = "Tool not found: ${request.toolName}",
            )

        val toolResult = action.executeGlobal()
        return toToolResponse(id, toolResult)
    }

    private fun toToolResponse(id: Any?, toolResult: McpToolResult): McpJsonRpcResponse {
        return if (toolResult.status == McpToolStatus.ERROR) {
            resultMapper.toolError(
                id = id,
                errorCode = toolResult.errorCode ?: McpErrorCode.INTERNAL_ERROR,
                message = toolResult.message,
                data = toolResult.data,
            )
        } else {
            resultMapper.toolSuccess(id = id, toolResult = toolResult)
        }
    }

    private fun handleCommonMethods(request: McpJsonRpcRequest): McpJsonRpcResponse? {
        if (request.jsonrpc != McpJsonRpc.Version) {
            logger.debug("[MCP][GLOBAL] invalid jsonrpc version: ${request.jsonrpc}")
            return resultMapper.jsonRpcError(
                request.id,
                McpErrorCode.INVALID_JSON_RPC,
                "Invalid jsonrpc version",
                McpJsonRpc.ErrorCode.InvalidRequest,
            )
        }

        return when (request.method) {
            McpJsonRpc.Method.Initialize -> resultMapper.initialize(request.id)
            McpJsonRpc.Method.NotificationsInitialized -> resultMapper.notificationAck()
            McpJsonRpc.Method.Ping -> resultMapper.ping(request.id)
            McpJsonRpc.Method.PromptsList -> resultMapper.promptsList(request.id)
            McpJsonRpc.Method.ResourcesList -> resultMapper.resourcesList(request.id)
            McpJsonRpc.Method.ResourcesTemplatesList -> resultMapper.resourcesTemplatesList(request.id)
            else -> null
        }
    }

    companion object {
        val mcpBaseInvoker = McpBaseInvoker()
    }
}
