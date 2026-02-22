package com.sickworm.intellij.jugg.mcp

import com.google.gson.Gson
import com.google.gson.JsonParser
import com.google.gson.JsonSyntaxException
import com.sickworm.intellij.jugg.logger.JuggLogger
import com.sickworm.intellij.jugg.platform.PlatformApi
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpHandler
import com.sun.net.httpserver.HttpServer
import java.io.IOException
import java.net.BindException
import java.net.InetSocketAddress
import java.util.concurrent.Executors

/**
 * McpLocalServer hosts the local MCP HTTP endpoint, validates protocol headers, and routes JSON-RPC requests to tool handlers.
 */
object McpLocalServer {
    private const val PORT_START = 12320
    private const val PORT_END = 12329
    private const val CONTEXT_PATH = "/jugg-mcp"

    private const val HEADER_CONTENT_TYPE = "Content-Type"
    private const val HEADER_ORIGIN = "Origin"
    private const val HEADER_MCP_PROTOCOL_VERSION = "MCP-Protocol-Version"

    private val SUPPORTED_PROTOCOL_VERSIONS = setOf("2025-06-18", "2025-11-25")

    private var server: HttpServer? = null
    private var actualPort: Int = PORT_START
    private val gson = Gson()

    private val logger = JuggLogger.getGlobalLogger("McpLocalServer")

    fun start() {
        if (server != null) {
            logger.debug("start: Server is already running")
            return
        }

        var lastException: IOException? = null
        for (port in PORT_START..PORT_END) {
            try {
                server = HttpServer.create(InetSocketAddress(port), 0)
                server?.let { httpServer ->
                    httpServer.createContext(CONTEXT_PATH, McpRequestHandler())
                    httpServer.executor = Executors.newFixedThreadPool(4)
                    httpServer.start()
                    actualPort = port
                    logger.debug("start: MCP Local Server started on port $port")
                    return
                }
            } catch (e: BindException) {
                logger.debug("start: Port $port is already in use, trying next port")
                lastException = e
                server = null
            } catch (e: IOException) {
                logger.debug("start: Failed to start MCP Local Server on port $port: ${e.message}")
                lastException = e
                server = null
            }
        }

        logger.debug("start: Failed to start MCP Local Server on any port in range $PORT_START-$PORT_END. Last error: ${lastException?.message}")
    }

    fun stop() {
        server?.let { httpServer ->
            httpServer.stop(0)
            server = null
            logger.debug("MCP Local Server stopped")
        }
    }

    fun isRunning(): Boolean {
        return server != null
    }

    fun getPort(): Int {
        return actualPort
    }

    /**
     * McpRequestHandler processes one HTTP exchange:
     * origin/protocol checks, JSON-RPC parsing, method dispatch, and error mapping.
     */
    private class McpRequestHandler : HttpHandler {
        override fun handle(exchange: HttpExchange) {
            try {
                if (!isOriginAllowed(exchange)) {
                    val errorResponse = McpJsonRpcResponse(
                        error = McpJsonRpcError(
                            code = McpJsonRpc.ErrorCode.InvalidRequest,
                            message = "Forbidden origin",
                            data = mapOf("errorCode" to McpErrorCode.MCP_INVALID_JSON_RPC),
                        )
                    )
                    sendJsonResponse(exchange, 403, errorResponse)
                    return
                }

                when (exchange.requestMethod) {
                    "GET" -> handleGetRequest(exchange)
                    "POST" -> handlePostRequest(exchange)
                    "DELETE" -> sendMethodNotAllowed(exchange)
                    else -> sendMethodNotAllowed(exchange)
                }
            } catch (e: Exception) {
                logger.warn("[MCP] request handling failed", e)
                try {
                    val errorResponse = McpJsonRpcResponse(
                        error = McpJsonRpcError(
                            code = McpJsonRpc.ErrorCode.InternalError,
                            message = "Internal Server Error: ${e.message}",
                            data = mapOf("errorCode" to McpErrorCode.MCP_INTERNAL_ERROR),
                        )
                    )
                    sendJsonResponse(exchange, 500, errorResponse)
                } catch (_: Exception) {
                }
            } finally {
                exchange.close()
            }
        }

        private fun handleGetRequest(exchange: HttpExchange) {
            sendMethodNotAllowed(exchange)
        }

        private fun handlePostRequest(exchange: HttpExchange) {
            val protocolVersionHeader = exchange.requestHeaders.getFirst(HEADER_MCP_PROTOCOL_VERSION)
            if (!protocolVersionHeader.isNullOrBlank() && protocolVersionHeader !in SUPPORTED_PROTOCOL_VERSIONS) {
                val errorResponse = McpJsonRpcResponse(
                    error = McpJsonRpcError(
                        code = McpJsonRpc.ErrorCode.InvalidRequest,
                        message = "Unsupported MCP protocol version: $protocolVersionHeader",
                        data = mapOf("errorCode" to McpErrorCode.MCP_INVALID_JSON_RPC),
                    )
                )
                sendJsonResponse(exchange, 400, errorResponse)
                return
            }

            val requestBody = exchange.requestBody.bufferedReader().use { it.readText() }
            logger.debug("[MCP][IN ] $requestBody")

            if (requestBody.isBlank()) {
                val errorResponse = McpJsonRpcResponse(
                    error = McpJsonRpcError(
                        code = McpJsonRpc.ErrorCode.InvalidRequest,
                        message = "Empty request body",
                        data = mapOf("errorCode" to McpErrorCode.MCP_INVALID_JSON_RPC),
                    )
                )
                sendJsonResponse(exchange, 400, errorResponse)
                return
            }

            try {
                val jsonElement = JsonParser.parseString(requestBody)
                if (!jsonElement.isJsonObject) {
                    val errorResponse = McpJsonRpcResponse(
                        error = McpJsonRpcError(
                            code = McpJsonRpc.ErrorCode.InvalidRequest,
                            message = "Batch or non-object JSON-RPC payload is not supported",
                            data = mapOf("errorCode" to McpErrorCode.MCP_INVALID_JSON_RPC),
                        )
                    )
                    sendJsonResponse(exchange, 400, errorResponse)
                    return
                }

                val jsonObject = jsonElement.asJsonObject

                if (!jsonObject.has("method")) {
                    logger.debug("[MCP] client response or notification envelope without method; return 202")
                    sendNoBodyResponse(exchange, 202)
                    return
                }

                val request = gson.fromJson(jsonObject, McpJsonRpcRequest::class.java)

                val isNotification = request.id == null
                if (isNotification) {
                    PlatformApi.invokeMcp(request)
                    logger.debug("[MCP][OUT][202] notification accepted")
                    sendNoBodyResponse(exchange, 202)
                    return
                }

                val response = PlatformApi.invokeMcp(request)
                sendJsonResponse(exchange, 200, response)
            } catch (e: JsonSyntaxException) {
                val errorResponse = McpJsonRpcResponse(
                    error = McpJsonRpcError(
                        code = McpJsonRpc.ErrorCode.ParseError,
                        message = "Invalid JSON format: ${e.message}",
                        data = mapOf("errorCode" to McpErrorCode.MCP_INVALID_JSON_RPC),
                    )
                )
                sendJsonResponse(exchange, 400, errorResponse)
            } catch (e: Throwable) {
                logger.warn("handlePostRequest error", e)
                val errorResponse = McpJsonRpcResponse(
                    error = McpJsonRpcError(
                        code = McpJsonRpc.ErrorCode.InternalError,
                        message = "Error processing request: $e",
                        data = mapOf("errorCode" to McpErrorCode.MCP_INTERNAL_ERROR),
                    )
                )
                sendJsonResponse(exchange, 500, errorResponse)
            }
        }

        private fun sendMethodNotAllowed(exchange: HttpExchange) {
            val errorResponse = McpJsonRpcResponse(
                error = McpJsonRpcError(
                    code = McpJsonRpc.ErrorCode.MethodNotFound,
                    message = "Method Not Allowed",
                    data = mapOf("errorCode" to McpErrorCode.MCP_METHOD_NOT_SUPPORTED),
                )
            )
            sendJsonResponse(exchange, 405, errorResponse)
        }

        private fun sendNoBodyResponse(exchange: HttpExchange, statusCode: Int) {
            logger.debug("[MCP][OUT][$statusCode] <empty>")
            exchange.sendResponseHeaders(statusCode, -1)
        }

        private fun sendJsonResponse(exchange: HttpExchange, statusCode: Int, response: McpJsonRpcResponse) {
            val responseJson = gson.toJson(response)
            logger.debug("[MCP][OUT][${statusCode}] $responseJson")
            val responseBytes = responseJson.toByteArray(Charsets.UTF_8)

            exchange.responseHeaders.add(HEADER_CONTENT_TYPE, "application/json")
            exchange.sendResponseHeaders(statusCode, responseBytes.size.toLong())
            exchange.responseBody.use { os ->
                os.write(responseBytes)
            }
        }

        private fun isOriginAllowed(exchange: HttpExchange): Boolean {
            val origin = exchange.requestHeaders.getFirst(HEADER_ORIGIN) ?: return true
            if (origin == "null") {
                return true
            }
            return origin.startsWith("http://localhost") ||
                origin.startsWith("http://127.0.0.1") ||
                origin.startsWith("https://localhost") ||
                origin.startsWith("https://127.0.0.1")
        }
    }
}
