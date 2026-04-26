package com.sickworm.intellij.jugg.ai.mcp

// see https://modelcontextprotocol.io/specification/2025-06-18/basic/index

/**
 * McpJsonRpc groups protocol constants for MCP JSON-RPC handling.
 */
object McpJsonRpc {
    const val Version = "2.0"
    const val ProtocolVersion = "2025-06-18"

    /**
     * Method enumerates JSON-RPC method names supported by this server.
     */
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

    /**
     * ErrorCode mirrors standard JSON-RPC error codes.
     */
    object ErrorCode {
        const val ParseError = -32700
        const val InvalidRequest = -32600
        const val MethodNotFound = -32601
        const val InvalidParams = -32602
        const val InternalError = -32603
    }
}

/** McpJsonRpcRequest is runtime-only and should not rely on hot-reload compatibility. */
data class McpJsonRpcRequest(
    val jsonrpc: String = McpJsonRpc.Version,
    val id: Any? = null,
    val method: String,
    val params: Any? = null,
)

/** Build through [McpResultMapper] to keep response shape and id normalization consistent. */
data class McpJsonRpcResponse(
    val jsonrpc: String = McpJsonRpc.Version,
    val id: Any? = null,
    val result: Any? = null,
    val error: McpJsonRpcError? = null,
)

/**
 * McpJsonRpcError carries code, message, and data.
 */
data class McpJsonRpcError(
    val code: Int,
    val message: String,
    val data: Any? = null,
)

/**
 * McpJsonRpcErrorResponse carries jsonrpc, id, and error.
 */
data class McpJsonRpcErrorResponse(
    val jsonrpc: String = McpJsonRpc.Version,
    val id: Any? = null,
    val error: McpJsonRpcError,
)

/**
 * McpInitializeParams carries protocolVersion, capabilities, and clientInfo.
 */
data class McpInitializeParams(
    val protocolVersion: String? = null,
    val capabilities: Map<String, Any?>? = null,
    val clientInfo: McpPeerInfo? = null,
)

/**
 * McpInitializeResult carries protocolVersion, capabilities, serverInfo, and instructions.
 */
data class McpInitializeResult(
    val protocolVersion: String = McpJsonRpc.ProtocolVersion,
    val capabilities: Map<String, Any?>,
    val serverInfo: McpPeerInfo,
    val instructions: String? = null,
)

/**
 * McpPeerInfo carries name and version.
 */
data class McpPeerInfo(
    val name: String,
    val version: String,
)
