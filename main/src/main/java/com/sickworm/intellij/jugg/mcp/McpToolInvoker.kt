package com.sickworm.intellij.jugg.mcp

import com.intellij.openapi.diagnostic.Logger

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
                errorCode = McpErrorCode.MCP_TOOL_NOT_FOUND,
                message = "Tool not found: ${request.toolName}",
            )

        val toolResult = action.execute(request.arguments, runtime)
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
    }

}
