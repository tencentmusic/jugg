package com.sickworm.intellij.jugg.mcp

class McpRequestValidator(
    private val currentProjectDir: String,
    private val toolRegistry: McpToolRegistry,
) {

    fun validate(request: McpJsonRpcRequest): McpValidationResult {
        return when (request.method) {
            McpJsonRpc.Method.ToolsList -> McpValidationResult.ToolsList
            McpJsonRpc.Method.ToolsCall -> validateToolsCall(request)
            else -> McpValidationResult.Invalid(
                errorCode = McpErrorCode.MCP_METHOD_NOT_SUPPORTED,
                message = "Method not supported: ${request.method}",
                isJsonRpcError = true,
                jsonRpcCode = McpJsonRpc.ErrorCode.MethodNotFound,
            )
        }
    }

    private fun validateToolsCall(request: McpJsonRpcRequest): McpValidationResult {
        val params = request.params as? Map<*, *>
            ?: return McpValidationResult.Invalid(
                errorCode = McpErrorCode.MCP_INVALID_PARAMS,
                message = "tools/call params is required",
            )

        val toolName = params["name"] as? String
            ?: return McpValidationResult.Invalid(
                errorCode = McpErrorCode.MCP_INVALID_PARAMS,
                message = "Tool name is required",
            )

        if (!toolRegistry.hasTool(toolName)) {
            return McpValidationResult.Invalid(
                errorCode = McpErrorCode.MCP_TOOL_NOT_FOUND,
                message = "Tool not found: $toolName",
            )
        }

        @Suppress("UNCHECKED_CAST")
        val args = params["arguments"] as? Map<String, Any?> ?: emptyMap()
        val projectDir = args["projectDir"] as? String

        if (toolName == "list_projects") {
            return McpValidationResult.ToolsCall(
                toolName = toolName,
                arguments = args,
                projectDir = projectDir.orEmpty(),
            )
        }

        if (projectDir.isNullOrBlank()) {
            return McpValidationResult.Invalid(
                errorCode = McpErrorCode.MCP_INVALID_PARAMS,
                message = "$toolName failed. Reason: projectDir is required.",
            )
        }

        if (projectDir != currentProjectDir) {
            return McpValidationResult.Invalid(
                errorCode = McpErrorCode.MCP_PROJECT_NOT_INITIALIZED,
                message = "$toolName failed. Reason: project is not initialized.",
            )
        }

        return McpValidationResult.ToolsCall(
            toolName = toolName,
            arguments = args,
            projectDir = projectDir,
        )
    }
}

sealed class McpValidationResult {
    data object ToolsList : McpValidationResult()

    data class ToolsCall(
        val toolName: String,
        val arguments: Map<String, Any?>,
        val projectDir: String,
    ) : McpValidationResult()

    data class Invalid(
        val errorCode: String,
        val message: String,
        val isJsonRpcError: Boolean = false,
        val jsonRpcCode: Int = McpJsonRpc.ErrorCode.InternalError,
    ) : McpValidationResult()
}
