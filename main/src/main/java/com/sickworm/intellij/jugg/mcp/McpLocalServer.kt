package com.sickworm.intellij.jugg.mcp

import com.google.gson.Gson
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

object McpLocalServer {
    private const val PORT_START = 12320
    private const val PORT_END = 12329
    private const val CONTEXT_PATH = "/mcp"

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

    private class McpRequestHandler : HttpHandler {
        override fun handle(exchange: HttpExchange) {
            try {
                when (exchange.requestMethod) {
                    "GET" -> handleGetRequest(exchange)
                    "POST" -> handlePostRequest(exchange)
                    else -> {
                        val errorResponse = McpJsonRpcResponse(
                            error = McpJsonRpcError(
                                code = McpJsonRpc.ErrorCode.MethodNotFound,
                                message = "Method Not Allowed",
                                data = mapOf("errorCode" to McpErrorCode.MCP_METHOD_NOT_SUPPORTED),
                            )
                        )
                        sendJsonResponse(exchange, 405, errorResponse)
                    }
                }
            } catch (e: Exception) {
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
            val info = mapOf(
                "name" to "jugg-mcp",
                "protocol" to "json-rpc-2.0",
                "path" to CONTEXT_PATH,
            )
            logger.debug("[MCP][IN ] GET")
            sendJsonResponse(exchange, 200, McpJsonRpcResponse(result = info))
        }

        private fun handlePostRequest(exchange: HttpExchange) {
            val requestBody = exchange.requestBody.bufferedReader().use { it.readText() }
            logger.debug("[MCP][IN ] $requestBody")
            if (requestBody.isEmpty()) {
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
                val request = gson.fromJson(requestBody, McpJsonRpcRequest::class.java)
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

        private fun sendJsonResponse(exchange: HttpExchange, statusCode: Int, response: McpJsonRpcResponse) {
            val responseJson = gson.toJson(response)
            logger.debug("[MCP][OUT][${statusCode}] $responseJson")
            val responseBytes = responseJson.toByteArray(Charsets.UTF_8)

            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(statusCode, responseBytes.size.toLong())
            exchange.responseBody.use { os ->
                os.write(responseBytes)
            }
        }
    }
}
