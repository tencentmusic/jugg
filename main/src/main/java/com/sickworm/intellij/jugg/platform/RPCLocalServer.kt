package com.sickworm.intellij.jugg.platform

import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpHandler
import com.sun.net.httpserver.HttpServer
import java.io.IOException
import java.net.InetSocketAddress
import java.util.concurrent.Executors

/**
 * RPC Local Server singleton that provides HTTP interface for receiving and returning JSON data
 */
object RPCLocalServer {
    private const val PORT = 12304
    private const val CONTEXT_PATH = "/"
    
    private var server: HttpServer? = null
    private val gson = Gson()
    
    /**
     * Start the HTTP server
     * @throws IOException if the port is already in use or other network errors
     */
    @Throws(IOException::class)
    fun start() {
        if (server != null) {
            throw IllegalStateException("Server is already running")
        }
        
        try {
            server = HttpServer.create(InetSocketAddress(PORT), 0)
            server?.let { httpServer ->
                httpServer.createContext(CONTEXT_PATH, JsonEchoHandler())
                httpServer.executor = Executors.newFixedThreadPool(4)
                httpServer.start()
                println("RPC Local Server started on port $PORT")
            }
        } catch (e: IOException) {
            server = null
            throw e
        }
    }
    
    /**
     * Stop the HTTP server
     */
    fun stop() {
        server?.let { httpServer ->
            httpServer.stop(0)
            server = null
            println("RPC Local Server stopped")
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
     * HTTP request handler that receives JSON and returns it as-is
     */
    private class JsonEchoHandler : HttpHandler {
        override fun handle(exchange: HttpExchange) {
            try {
                when (exchange.requestMethod) {
                    "POST" -> handlePostRequest(exchange)
                    "GET" -> handleGetRequest(exchange)
                    else -> {
                        sendResponse(exchange, 405, "Method Not Allowed")
                    }
                }
            } catch (e: Exception) {
                try {
                    sendResponse(exchange, 500, "Internal Server Error: ${e.message}")
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
                sendResponse(exchange, 400, "Empty request body")
                return
            }
            
            try {
                // Validate if it's valid JSON
                gson.fromJson(requestBody, Any::class.java)
                
                // Return JSON as-is
                exchange.responseHeaders.set("Content-Type", "application/json")
                sendResponse(exchange, 200, requestBody)
            } catch (e: JsonSyntaxException) {
                sendResponse(exchange, 400, "Invalid JSON format")
            }
        }
        
        private fun handleGetRequest(exchange: HttpExchange) {
            val response = """{"message": "RPC Local Server is running", "port": $PORT}"""
            exchange.responseHeaders.set("Content-Type", "application/json")
            sendResponse(exchange, 200, response)
        }
        
        private fun sendResponse(exchange: HttpExchange, statusCode: Int, response: String) {
            val responseBytes = response.toByteArray()
            exchange.sendResponseHeaders(statusCode, responseBytes.size.toLong())
            exchange.responseBody.use { outputStream ->
                outputStream.write(responseBytes)
            }
        }
    }
}