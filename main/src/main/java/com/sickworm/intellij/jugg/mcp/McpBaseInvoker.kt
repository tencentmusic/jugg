package com.sickworm.intellij.jugg.mcp

import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.jugg.mcp.actions.ListProjectsMcpToolAction

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

        if (request.toolName == "list_projects") {
            val toolResult = ListProjectsMcpToolAction().listProjectsAction()
            return if (toolResult.status == McpToolStatus.ERROR) {
                resultMapper.toolError(
                    id = id,
                    errorCode = toolResult.errorCode ?: McpErrorCode.MCP_INTERNAL_ERROR,
                    message = toolResult.message,
                    data = toolResult.data,
                )
            } else {
                resultMapper.toolSuccess(id = id, toolResult = toolResult)
            }
        } else {
            return resultMapper.toolError(
                id = id,
                errorCode = McpErrorCode.MCP_INTERNAL_ERROR,
                message = "MCP runtime is not initialized.",
            )
        }
    }

    private fun handleCommonMethods(request: McpJsonRpcRequest): McpJsonRpcResponse? {
        if (request.jsonrpc != McpJsonRpc.Version) {
            logger.debug("[MCP][GLOBAL] invalid jsonrpc version: ${request.jsonrpc}")
            return resultMapper.jsonRpcError(
                request.id,
                McpErrorCode.MCP_INVALID_JSON_RPC,
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