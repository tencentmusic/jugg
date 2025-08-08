package com.sickworm.intellij.jugg.platform

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.io.BufferedReader
import java.io.InputStreamReader

class RPCLocalServerTest {
    
    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()
    
    private val baseUrl = "http://localhost:${RPCLocalServer.getPort()}"
    
    @Before
    fun setUp() {
        // Ensure server is not running
        if (RPCLocalServer.isRunning()) {
            RPCLocalServer.stop()
        }
    }
    
    @After
    fun tearDown() {
        // Cleanup: stop server
        if (RPCLocalServer.isRunning()) {
            RPCLocalServer.stop()
        }
    }
    
    @Test
    fun testServerStartAndStop() {
        // Test server startup
        assertFalse("Server should not be running initially", RPCLocalServer.isRunning())
        
        RPCLocalServer.start()
        assertTrue("Server should be running after start", RPCLocalServer.isRunning())
        
        // Test server shutdown
        RPCLocalServer.stop()
        assertFalse("Server should not be running after stop", RPCLocalServer.isRunning())
    }
    
    @Test
    fun testDoubleStart() {
        RPCLocalServer.start()
        
        // Attempting to start again should throw an exception
        try {
            RPCLocalServer.start()
            fail("Should throw IllegalStateException when starting already running server")
        } catch (e: IllegalStateException) {
            assertEquals("Server is already running", e.message)
        }
    }
    
    @Test
    fun testGetRequest() {
        RPCLocalServer.start()
        
        val request = Request.Builder()
            .url(baseUrl)
            .get()
            .build()
        
        client.newCall(request).execute().use { response ->
            assertTrue("GET request should be successful", response.isSuccessful)
            assertEquals("Content type should be JSON", "application/json", response.header("Content-Type"))
            
            val responseBody = response.body?.string()
            assertNotNull("Response body should not be null", responseBody)
            
            val jsonResponse = JSONObject(responseBody!!)
            assertEquals("RPC Local Server is running", jsonResponse.getString("message"))
            assertEquals(12304, jsonResponse.getInt("port"))
        }
    }
    
    @Test
    fun testPostJsonEcho() {
        RPCLocalServer.start()
        
        val testJson = """{"name": "test", "value": 123, "nested": {"key": "value"}}"""
        val requestBody = testJson.toRequestBody("application/json".toMediaType())
        
        val request = Request.Builder()
            .url(baseUrl)
            .post(requestBody)
            .build()
        
        client.newCall(request).execute().use { response ->
            assertTrue("POST request should be successful", response.isSuccessful)
            assertEquals("Content type should be JSON", "application/json", response.header("Content-Type"))
            
            val responseBody = response.body?.string()
            assertEquals("Response should echo the request JSON", testJson, responseBody)
        }
    }
    
    @Test
    fun testPostInvalidJson() {
        RPCLocalServer.start()
        
        val invalidJson = "{invalid json}"
        val requestBody = invalidJson.toRequestBody("application/json".toMediaType())
        
        val request = Request.Builder()
            .url(baseUrl)
            .post(requestBody)
            .build()
        
        client.newCall(request).execute().use { response ->
            assertEquals("Invalid JSON should return 400", 400, response.code)
            
            val responseBody = response.body?.string()
            assertEquals("Invalid JSON format", responseBody)
        }
    }
    
    @Test
    fun testPostEmptyBody() {
        RPCLocalServer.start()
        
        val requestBody = "".toRequestBody("application/json".toMediaType())
        
        val request = Request.Builder()
            .url(baseUrl)
            .post(requestBody)
            .build()
        
        client.newCall(request).execute().use { response ->
            assertEquals("Empty body should return 400", 400, response.code)
            
            val responseBody = response.body?.string()
            assertEquals("Empty request body", responseBody)
        }
    }
    
    @Test
    fun testUnsupportedMethod() {
        RPCLocalServer.start()
        
        val request = Request.Builder()
            .url(baseUrl)
            .put("".toRequestBody())
            .build()
        
        client.newCall(request).execute().use { response ->
            assertEquals("Unsupported method should return 405", 405, response.code)
            
            val responseBody = response.body?.string()
            assertEquals("Method Not Allowed", responseBody)
        }
    }
    
    @Test
    fun testPortConfiguration() {
        assertEquals("Port should be 12304", 12304, RPCLocalServer.getPort())
    }
    
    @Test
    fun testCurlGetRequest() {
        RPCLocalServer.start()
        
        val process = ProcessBuilder(
            "curl", 
            "-s", 
            "-w", "%{http_code}", 
            "http://localhost:${RPCLocalServer.getPort()}/"
        ).start()
        
        val reader = BufferedReader(InputStreamReader(process.inputStream))
        val output = reader.readText()
        val exitCode = process.waitFor()
        
        assertEquals("Curl command should execute successfully", 0, exitCode)
        assertTrue("Response should contain status code 200", output.contains("200"))
        assertTrue("Response should contain server message", output.contains("RPC Local Server is running"))
    }
    
    @Test
    fun testCurlPostJsonEcho() {
        RPCLocalServer.start()
        
        val testJson = """{"message": "hello", "number": 42}"""
        val process = ProcessBuilder(
            "curl", 
            "-s", 
            "-X", "POST", 
            "-H", "Content-Type: application/json", 
            "-d", testJson,
            "-w", "%{http_code}", 
            "http://localhost:${RPCLocalServer.getPort()}/"
        ).start()
        
        val reader = BufferedReader(InputStreamReader(process.inputStream))
        val output = reader.readText()
        val exitCode = process.waitFor()
        
        assertEquals("Curl command should execute successfully", 0, exitCode)
        assertTrue("Response should contain status code 200", output.contains("200"))
        assertTrue("Response should echo the JSON", output.contains("\"message\": \"hello\""))
        assertTrue("Response should echo the number", output.contains("\"number\": 42"))
    }
    
    @Test
    fun testCurlPostInvalidJson() {
        RPCLocalServer.start()
        
        val invalidJson = "{invalid json}"
        val process = ProcessBuilder(
            "curl", 
            "-s", 
            "-X", "POST", 
            "-H", "Content-Type: application/json", 
            "-d", invalidJson,
            "-w", "%{http_code}", 
            "http://localhost:${RPCLocalServer.getPort()}/"
        ).start()
        
        val reader = BufferedReader(InputStreamReader(process.inputStream))
        val output = reader.readText()
        val exitCode = process.waitFor()
        
        assertEquals("Curl command should execute successfully", 0, exitCode)
        assertTrue("Response should contain status code 400", output.contains("400"))
        assertTrue("Response should contain error message", output.contains("Invalid JSON format"))
    }
    
    @Test
    fun testCurlUnsupportedMethod() {
        RPCLocalServer.start()
        
        val process = ProcessBuilder(
            "curl", 
            "-s", 
            "-X", "PUT", 
            "-w", "%{http_code}", 
            "http://localhost:${RPCLocalServer.getPort()}/"
        ).start()
        
        val reader = BufferedReader(InputStreamReader(process.inputStream))
        val output = reader.readText()
        val exitCode = process.waitFor()
        
        assertEquals("Curl command should execute successfully", 0, exitCode)
        assertTrue("Response should contain status code 405", output.contains("405"))
        assertTrue("Response should contain method not allowed message", output.contains("Method Not Allowed"))
    }
}