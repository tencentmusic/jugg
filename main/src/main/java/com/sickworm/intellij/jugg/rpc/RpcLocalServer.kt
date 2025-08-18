package com.sickworm.intellij.jugg.rpc

import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import com.sickworm.intellij.jugg.logger.JuggLogger
import com.sickworm.intellij.jugg.platform.PlatformApi
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpHandler
import com.sun.net.httpserver.HttpServer
import java.io.IOException
import java.net.InetSocketAddress
import java.util.concurrent.Executors

/**
 * RPC Local Server singleton that provides HTTP interface for processing RpcRequest and returning RpcResponse
 */
object RpcLocalServer {
    private const val PORT = 12310
    private const val CONTEXT_PATH = "/"

    private var server: HttpServer? = null
    private val gson = Gson()

    private val logger = JuggLogger.getGlobalLogger("RPCLocalServer")

    /**
     * Start the HTTP server
     */
    fun start() {
        if (server != null) {
            logger.debug("start: Server is already running")
            return
        }

        try {
            server = HttpServer.create(InetSocketAddress(PORT), 0)
            server?.let { httpServer ->
                httpServer.createContext(CONTEXT_PATH, RpcRequestHandler())
                httpServer.executor = Executors.newFixedThreadPool(4)
                httpServer.start()
                logger.debug("start: RPC Local Server started on port $PORT")
            }
        } catch (e: IOException) {
            server = null
            logger.debug("start: Failed to start RPC Local Server: ${e.message}")
            return
        }
    }

    /**
     * Stop the HTTP server
     */
    fun stop() {
        server?.let { httpServer ->
            httpServer.stop(0)
            server = null
            logger.debug("RPC Local Server stopped")
        }
    }

    /**
     * Check if the server is running
     */
    fun isRunning(): Boolean {
        return server != null
    }

    /**
     * Get the server port
     */
    fun getPort(): Int {
        return PORT
    }

    /**
     * HTTP request handler that processes RpcRequest and returns RpcResponse
     */
    private class RpcRequestHandler : HttpHandler {
        override fun handle(exchange: HttpExchange) {
            try {
                when (exchange.requestMethod) {
                    "POST" -> handlePostRequest(exchange)
                    else -> {
                        val errorResponse = RpcResponse(
                            status = RpcResult.ErrorMethodNotAllowed,
                            result = "Method Not Allowed"
                        )
                        sendJsonResponse(exchange, 405, errorResponse)
                    }
                }
            } catch (e: Exception) {
                try {
                    val errorResponse = RpcResponse(
                        status = RpcResult.ErrorInternalServerError,
                        result = "Internal Server Error: ${e.message}"
                    )
                    sendJsonResponse(exchange, 500, errorResponse)
                } catch (responseException: Exception) {
                    // Ignore response sending failure exceptions
                }
            } finally {
                exchange.close()
            }
        }

        private fun handlePostRequest(exchange: HttpExchange) {
            val requestBody = exchange.requestBody.bufferedReader().use { it.readText() }

            if (requestBody.isEmpty()) {
                val errorResponse = RpcResponse(
                    status = RpcResult.ErrorEmptyRequestBody,
                    result = "Empty request body"
                )
                sendJsonResponse(exchange, 400, errorResponse)
                return
            }

            try {
                // Parse RpcRequest
                val rpcRequest = gson.fromJson(requestBody, RpcRequest::class.java)
                val rpcResponse: RpcResponse =
                    if (rpcRequest.cmd == RpcCommand.ECHO) {
                        RpcResponse(
                            status = RpcResult.OK,
                            result = requestBody
                        )
                    } else {
                        // Process the request based on command
                        PlatformApi.call(rpcRequest)
                    }

                sendJsonResponse(exchange, 200, rpcResponse)
            } catch (e: JsonSyntaxException) {
                val errorResponse = RpcResponse(
                    status = RpcResult.ErrorInvalidJsonFormat,
                    result = "Invalid JSON format: ${e.message}"
                )
                sendJsonResponse(exchange, 400, errorResponse)
            } catch (e: Exception) {
                logger.warn("handlePostRequest error ", e)
                val errorResponse = RpcResponse(
                    status = RpcResult.ErrorInternalServerError,
                    result = "Error processing request: $e"
                )
                sendJsonResponse(exchange, 500, errorResponse)
            }
        }

        private fun sendJsonResponse(exchange: HttpExchange, statusCode: Int, response: RpcResponse) {
            val responseJson = gson.toJson(response)
            exchange.responseHeaders.set("Content-Type", "application/json")
            val responseBytes = responseJson.toByteArray()
            exchange.sendResponseHeaders(statusCode, responseBytes.size.toLong())
            exchange.responseBody.use { outputStream ->
                outputStream.write(responseBytes)
            }
        }
    }
}