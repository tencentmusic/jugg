package com.sickworm.intellij.jugg.rpc

import com.google.gson.Gson
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit
import java.io.BufferedReader
import java.io.InputStreamReader

class RpcLocalServerTest {
    
    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()
    
    private val baseUrl = "http://localhost:${RpcLocalServer.getPort()}"
    
    @Before
    fun setUp() {
        // Ensure server is not running
        if (RpcLocalServer.isRunning()) {
            RpcLocalServer.stop()
        }
    }
    
    @After
    fun tearDown() {
        // Cleanup: stop server
        if (RpcLocalServer.isRunning()) {
            RpcLocalServer.stop()
        }
    }
    
    @Test
    fun testServerStartAndStop() {
        // Test server startup
        assertFalse("Server should not be running initially", RpcLocalServer.isRunning())
        
        RpcLocalServer.start()
        assertTrue("Server should be running after start", RpcLocalServer.isRunning())
        
        // Test server shutdown
        RpcLocalServer.stop()
        assertFalse("Server should not be running after stop", RpcLocalServer.isRunning())
    }
    
    @Test
    fun testDoubleStart() {
        RpcLocalServer.start()
        
        // Attempting to start again should throw an exception
        try {
            RpcLocalServer.start()
            fail("Should throw IllegalStateException when starting already running server")
        } catch (e: IllegalStateException) {
            assertEquals("Server is already running", e.message)
        }
    }
    
    @Test
    fun testGetRequest() {
        RpcLocalServer.start()
        
        val request = Request.Builder()
            .url(baseUrl)
            .get()
            .build()
        
        client.newCall(request).execute().use { response ->
            assertTrue("GET request should be successful", response.isSuccessful)
            assertEquals("Content type should be JSON", "application/json", response.header("Content-Type"))
            
            val responseBody = response.body?.string()
            assertNotNull("Response body should not be null", responseBody)
            
            val rpcResponse = Gson().fromJson(responseBody!!, RpcResponse::class.java)
            assertEquals("OK", rpcResponse.status.name)
            val detailJson = Gson().fromJson(rpcResponse.result, Map::class.java)
            assertEquals("RPC Local Server is running", detailJson["message"])
            assertEquals(12304.0, detailJson["port"])
        }
    }
    
    @Test
    fun testPostJsonEcho() {
        RpcLocalServer.start()
        
        val rpcRequest = """{"cmd": "ECHO"}"""
        val requestBody = rpcRequest.toRequestBody("application/json".toMediaType())
        
        val request = Request.Builder()
            .url(baseUrl)
            .post(requestBody)
            .build()
        
        client.newCall(request).execute().use { response ->
            assertTrue("POST request should be successful", response.isSuccessful)
            assertEquals("Content type should be JSON", "application/json", response.header("Content-Type"))
            
            val responseBody = response.body?.string()
            val rpcResponse = Gson().fromJson(responseBody!!, RpcResponse::class.java)
            assertEquals("OK", rpcResponse.status.name)
            assertEquals(rpcRequest, rpcResponse.result)
        }
    }
    
    @Test
    fun testPostInvalidJson() {
        RpcLocalServer.start()
        
        val invalidJson = "{invalid json}"
        val requestBody = invalidJson.toRequestBody("application/json".toMediaType())
        
        val request = Request.Builder()
            .url(baseUrl)
            .post(requestBody)
            .build()
        
        client.newCall(request).execute().use { response ->
            assertEquals("Invalid JSON should return 400", 400, response.code)
            
            val responseBody = response.body?.string()
            val rpcResponse = Gson().fromJson(responseBody!!, RpcResponse::class.java)
            assertEquals("ErrorInvalidJsonFormat", rpcResponse.status.name)
            assertTrue("Detail should contain error message", rpcResponse.result.contains("Invalid JSON format"))
        }
    }
    
    @Test
    fun testPostEmptyBody() {
        RpcLocalServer.start()
        
        val requestBody = "".toRequestBody("application/json".toMediaType())
        
        val request = Request.Builder()
            .url(baseUrl)
            .post(requestBody)
            .build()
        
        client.newCall(request).execute().use { response ->
            assertEquals("Empty body should return 400", 400, response.code)
            
            val responseBody = response.body?.string()
            val rpcResponse = Gson().fromJson(responseBody!!, RpcResponse::class.java)
            assertEquals("ErrorEmptyRequestBody", rpcResponse.status.name)
            assertEquals("Empty request body", rpcResponse.result)
        }
    }
    
    @Test
    fun testUnsupportedMethod() {
        RpcLocalServer.start()
        
        val request = Request.Builder()
            .url(baseUrl)
            .put("".toRequestBody())
            .build()
        
        client.newCall(request).execute().use { response ->
            assertEquals("Unsupported method should return 405", 405, response.code)
            
            val responseBody = response.body?.string()
            val rpcResponse = Gson().fromJson(responseBody!!, RpcResponse::class.java)
            assertEquals("ErrorMethodNotAllowed", rpcResponse.status.name)
            assertEquals("Method Not Allowed", rpcResponse.result)
        }
    }
    
    @Test
    fun testPortConfiguration() {
        assertEquals("Port should be 12304", 12304, RpcLocalServer.getPort())
    }
    
    @Test
    fun testCurlGetRequest() {
        RpcLocalServer.start()
        
        val process = ProcessBuilder(
            "curl", 
            "-s", 
            "-w", "%{http_code}", 
            "http://localhost:${RpcLocalServer.getPort()}/"
        ).start()
        
        val reader = BufferedReader(InputStreamReader(process.inputStream))
        val output = reader.readText()
        val exitCode = process.waitFor()
        
        assertEquals("Curl command should execute successfully", 0, exitCode)
        assertTrue("Response should contain status code 200. Actual output: $output", output.contains("200"))
        assertTrue("Response should contain server message. Actual output: $output", output.contains("RPC Local Server is running"))
    }
    
    @Test
    fun testCurlPostJsonEcho() {
        RpcLocalServer.start()
        
        val rpcRequest = """{"cmd": "ECHO"}"""
        val process = ProcessBuilder(
            "curl", 
            "-s", 
            "-X", "POST", 
            "-H", "Content-Type: application/json", 
            "-d", rpcRequest,
            "-w", "%{http_code}", 
            "http://localhost:${RpcLocalServer.getPort()}/"
        ).start()
        
        val reader = BufferedReader(InputStreamReader(process.inputStream))
        val output = reader.readText()
        val exitCode = process.waitFor()
        
        assertEquals("Curl command should execute successfully", 0, exitCode)
        assertTrue("Response should contain status code 200. Actual output: $output", output.contains("200"))
        assertTrue("Response should contain OK status. Actual output: $output", output.contains("\"status\":\"OK\""))

        val rpcResponse = Gson().fromJson(output.replace("200", ""), RpcResponse::class.java)
        assertTrue("Response should echo the request. Actual output: $output", rpcResponse.result == rpcRequest)
    }
    
    @Test
    fun testCurlPostInvalidJson() {
        RpcLocalServer.start()
        
        val invalidJson = "{invalid json}"
        val process = ProcessBuilder(
            "curl", 
            "-s", 
            "-X", "POST", 
            "-H", "Content-Type: application/json", 
            "-d", invalidJson,
            "-w", "%{http_code}", 
            "http://localhost:${RpcLocalServer.getPort()}/"
        ).start()
        
        val reader = BufferedReader(InputStreamReader(process.inputStream))
        val output = reader.readText()
        val exitCode = process.waitFor()
        
        assertEquals("Curl command should execute successfully", 0, exitCode)
        assertTrue("Response should contain status code 400. Actual output: $output", output.contains("400"))
        assertTrue("Response should contain error status. Actual output: $output", output.contains("ErrorInvalidJsonFormat"))
        assertTrue("Response should contain error message. Actual output: $output", output.contains("Invalid JSON format"))
    }
    
    @Test
    fun testCurlUnsupportedMethod() {
        RpcLocalServer.start()
        
        val process = ProcessBuilder(
            "curl", 
            "-s", 
            "-X", "PUT", 
            "-w", "%{http_code}", 
            "http://localhost:${RpcLocalServer.getPort()}/"
        ).start()
        
        val reader = BufferedReader(InputStreamReader(process.inputStream))
        val output = reader.readText()
        val exitCode = process.waitFor()
        
        assertEquals("Curl command should execute successfully", 0, exitCode)
        assertTrue("Response should contain status code 405. Actual output: $output", output.contains("405"))
        assertTrue("Response should contain error status. Actual output: $output", output.contains("ErrorMethodNotAllowed"))
        assertTrue("Response should contain method not allowed message. Actual output: $output", output.contains("Method Not Allowed"))
    }
}