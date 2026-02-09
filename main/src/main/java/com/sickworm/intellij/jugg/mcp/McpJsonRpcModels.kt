package com.sickworm.intellij.jugg.mcp

// see https://modelcontextprotocol.io/specification/2025-06-18/basic/index

object McpJsonRpc {
    const val Version = "2.0"
    const val ProtocolVersion = "2025-06-18"

    object Method {
        const val Initialize = "initialize"
        const val NotificationsInitialized = "notifications/initialized"
        const val Ping = "ping"
        const val PromptsList = "prompts/list"
        const val ResourcesList = "resources/list"
        const val ResourcesTemplatesList = "resources/templates/list"
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

/** CAN NOT HOT_RELOAD. UPDATE NEEDS isNeedReinstall */
data class McpJsonRpcRequest(
    val jsonrpc: String = McpJsonRpc.Version,
    val id: Any? = null,
    val method: String,
    val params: Any? = null,
)

/** CAN NOT HOT_RELOAD. UPDATE NEEDS isNeedReinstall */
/** TOOLS CALL DO NOT CREATE DIRECTLY. USE [McpResultMapper] */
data class McpJsonRpcResponse(
    val jsonrpc: String = McpJsonRpc.Version,
    val id: Any? = null,
    val result: Any? = null,
    val error: McpJsonRpcError? = null,
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

data class McpInitializeParams(
    val protocolVersion: String? = null,
    val capabilities: Map<String, Any?>? = null,
    val clientInfo: McpPeerInfo? = null,
)

data class McpInitializeResult(
    val protocolVersion: String = McpJsonRpc.ProtocolVersion,
    val capabilities: Map<String, Any?>,
    val serverInfo: McpPeerInfo,
    val instructions: String? = null,
)

data class McpPeerInfo(
    val name: String,
    val version: String,
)
