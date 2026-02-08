package com.sickworm.intellij.jugg.mcp

class McpResultMapper {

    fun toolsList(id: Any?, tools: List<McpToolDefinition>): McpJsonRpcResponse {
        return McpJsonRpcResponse(id = id, result = McpToolsListResult(tools = tools))
    }

    fun toolSuccess(id: Any?, toolName: String, data: Any? = emptyMap<String, Any>()): McpJsonRpcResponse {
        return McpJsonRpcResponse(
            id = id,
            result = McpToolResult(
                status = McpToolStatus.OK,
                message = "$toolName invoked successfully. Implementation is pending.",
                data = data,
                artifacts = emptyList(),
                errorCode = null,
            )
        )
    }

    fun toolError(id: Any?, errorCode: String, message: String): McpJsonRpcResponse {
        return McpJsonRpcResponse(
            id = id,
            result = McpToolResult(
                status = McpToolStatus.ERROR,
                message = message,
                data = emptyMap<String, Any>(),
                artifacts = emptyList(),
                errorCode = errorCode,
            )
        )
    }

    fun jsonRpcError(id: Any?, errorCode: String, message: String, jsonRpcCode: Int): McpJsonRpcResponse {
        return McpJsonRpcResponse(
            id = id,
            error = McpJsonRpcError(
                code = jsonRpcCode,
                message = message,
                data = mapOf("errorCode" to errorCode),
            )
        )
    }
}
