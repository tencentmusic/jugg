package com.sickworm.intellij.jugg.mcp

class McpInvoker(
    private val currentProjectDir: String,
    private val toolRegistry: McpToolRegistry = McpToolRegistry(),
    private val resultMapper: McpResultMapper = McpResultMapper(),
) {
    private val requestValidator = McpRequestValidator(currentProjectDir, toolRegistry)

    fun invokeMcp(request: McpJsonRpcRequest): McpJsonRpcResponse {
        return when (val validated = requestValidator.validate(request)) {
            is McpValidationResult.ToolsList -> resultMapper.toolsList(request.id, toolRegistry.listTools())
            is McpValidationResult.ToolsCall -> handleToolsCall(request.id, validated)
            is McpValidationResult.Invalid -> {
                if (validated.isJsonRpcError) {
                    resultMapper.jsonRpcError(request.id, validated.errorCode, validated.message, validated.jsonRpcCode)
                } else {
                    resultMapper.toolError(request.id, validated.errorCode, validated.message)
                }
            }
        }
    }

    private fun handleToolsCall(id: Any?, request: McpValidationResult.ToolsCall): McpJsonRpcResponse {
        if (request.toolName == "list_projects") {
            return resultMapper.toolError(
                id = id,
                errorCode = McpErrorCode.MCP_INTERNAL_ERROR,
                message = "list_projects should be handled at JuggInitializer layer."
            )
        }

        return resultMapper.toolSuccess(
            id = id,
            toolName = request.toolName,
            data = mapOf(
                "tool" to request.toolName,
                "projectDir" to request.projectDir,
                "arguments" to request.arguments,
            )
        )
    }
}
