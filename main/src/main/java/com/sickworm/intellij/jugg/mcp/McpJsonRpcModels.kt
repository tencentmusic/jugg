package com.sickworm.intellij.jugg.mcp

object McpJsonRpc {
    const val Version = "2.0"

    object Method {
        const val ToolsList = "tools/list"
        const val ToolsCall = "tools/call"
    }

    object ErrorCode {
        const val ParseError = -32700
        const val InvalidRequest = -32600
        const val MethodNotFound = -32601
        const val InvalidParams = -32602
        const val InternalError = -32603
    }
}

data class McpJsonRpcRequest(
    val jsonrpc: String = McpJsonRpc.Version,
    val id: Any? = null,
    val method: String,
    val params: Any? = null,
)

data class McpJsonRpcSuccessResponse(
    val jsonrpc: String = McpJsonRpc.Version,
    val id: Any? = null,
    val result: Any? = null,
)

data class McpJsonRpcError(
    val code: Int,
    val message: String,
    val data: Any? = null,
)

data class McpJsonRpcErrorResponse(
    val jsonrpc: String = McpJsonRpc.Version,
    val id: Any? = null,
    val error: McpJsonRpcError,
)
