package com.sickworm.intellij.jugg.server

import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class PublicUpdateCheckerTest {

    @Test
    fun `when marketplace has new version, prefer marketplace channel`() = runBlocking {
        val marketplaceJson = """
            [
              {
                "id": 1173815,
                "version": "3.5.1-release",
                "file": "34099/1173815/jugg-3.5.1-release.zip",
                "notes": "Feature notes",
                "approve": true,
                "listed": true
              }
            ]
        """.trimIndent()

        val client = createMockClient { request ->
            when (request.url.toString()) {
                "http://mock.test/marketplace" -> {
                    Response.Builder()
                        .request(request)
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(marketplaceJson.toResponseBody("application/json".toMediaType()))
                        .build()
                }
                "http://mock.test/github" -> {
                    Response.Builder()
                        .request(request)
                        .protocol(Protocol.HTTP_1_1)
                        .code(302)
                        .message("Found")
                        .header("Location", "https://github.com/tencentmusic/jugg/releases/tag/v3.5.2")
                        .body("".toResponseBody(null))
                        .build()
                }
                else -> error("Unexpected url: ${request.url}")
            }
        }

        val checker = PublicUpdateChecker(
            marketplaceUrl = "http://mock.test/marketplace",
            githubUrl = "http://mock.test/github",
            timeoutMs = 2000L,
            client = client
        )

        val result = checker.check(currentVersion = "3.4.0")
        assertNotNull(result.updateInfo)
        assertEquals(UpdateChannel.MARKETPLACE, result.updateInfo?.channel)
        assertEquals("3.5.1", result.updateInfo?.targetVersion)
        assertEquals("https://plugins.jetbrains.com/files/34099/1173815/jugg-3.5.1-release.zip", result.updateInfo?.downloadUrl)
        assertFalse(result.isAlreadyLatest)
    }

    @Test
    fun `when marketplace fails and github has new version, fallback to github channel`() = runBlocking {
        val client = createMockClient { request ->
            when (request.url.toString()) {
                "http://mock.test/marketplace" -> {
                    Response.Builder()
                        .request(request)
                        .protocol(Protocol.HTTP_1_1)
                        .code(500)
                        .message("Internal Server Error")
                        .body("error".toResponseBody("text/plain".toMediaType()))
                        .build()
                }
                "http://mock.test/github" -> {
                    Response.Builder()
                        .request(request)
                        .protocol(Protocol.HTTP_1_1)
                        .code(302)
                        .message("Found")
                        .header("Location", "https://github.com/tencentmusic/jugg/releases/tag/v3.5.0")
                        .body("".toResponseBody(null))
                        .build()
                }
                else -> error("Unexpected url: ${request.url}")
            }
        }

        val checker = PublicUpdateChecker(
            marketplaceUrl = "http://mock.test/marketplace",
            githubUrl = "http://mock.test/github",
            timeoutMs = 2000L,
            client = client
        )

        val result = checker.check(currentVersion = "3.4.0")
        assertNotNull(result.updateInfo)
        assertEquals(UpdateChannel.GITHUB, result.updateInfo?.channel)
        assertEquals("3.5.0", result.updateInfo?.targetVersion)
        assertFalse(result.isAlreadyLatest)
    }

    @Test
    fun `when both channels report version less than or equal to current, report already latest`() = runBlocking {
        val marketplaceJson = """
            [
              {
                "id": 1173815,
                "version": "3.4.0",
                "file": "34099/1173815/jugg-3.4.0.zip",
                "approve": true,
                "listed": true
              }
            ]
        """.trimIndent()

        val client = createMockClient { request ->
            when (request.url.toString()) {
                "http://mock.test/marketplace" -> {
                    Response.Builder()
                        .request(request)
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(marketplaceJson.toResponseBody("application/json".toMediaType()))
                        .build()
                }
                "http://mock.test/github" -> {
                    Response.Builder()
                        .request(request)
                        .protocol(Protocol.HTTP_1_1)
                        .code(302)
                        .message("Found")
                        .header("Location", "https://github.com/tencentmusic/jugg/releases/tag/3.3.9")
                        .body("".toResponseBody(null))
                        .build()
                }
                else -> error("Unexpected url: ${request.url}")
            }
        }

        val checker = PublicUpdateChecker(
            marketplaceUrl = "http://mock.test/marketplace",
            githubUrl = "http://mock.test/github",
            timeoutMs = 2000L,
            client = client
        )

        val result = checker.check(currentVersion = "3.4.0")
        assertNull(result.updateInfo)
        assertTrue(result.isAlreadyLatest)
        assertEquals("3.4.0", result.latestCheckedVersion)
    }

    @Test
    fun `when current version has release suffix and matches remote, report already latest`() = runBlocking {
        val marketplaceJson = """
            [
              {
                "id": 1173815,
                "version": "3.5.1-release",
                "file": "34099/1173815/jugg-3.5.1-release.zip",
                "approve": true,
                "listed": true
              }
            ]
        """.trimIndent()

        val client = createMockClient { request ->
            when (request.url.toString()) {
                "http://mock.test/marketplace" -> {
                    Response.Builder()
                        .request(request)
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(marketplaceJson.toResponseBody("application/json".toMediaType()))
                        .build()
                }
                "http://mock.test/github" -> {
                    Response.Builder()
                        .request(request)
                        .protocol(Protocol.HTTP_1_1)
                        .code(302)
                        .message("Found")
                        .header("Location", "https://github.com/tencentmusic/jugg/releases/tag/v3.5.1")
                        .body("".toResponseBody(null))
                        .build()
                }
                else -> error("Unexpected url: ${request.url}")
            }
        }

        val checker = PublicUpdateChecker(
            marketplaceUrl = "http://mock.test/marketplace",
            githubUrl = "http://mock.test/github",
            timeoutMs = 2000L,
            client = client
        )

        val result = checker.check(currentVersion = "3.5.1-release")
        assertNull(result.updateInfo)
        assertTrue(result.isAlreadyLatest)
        assertEquals("3.5.1", result.latestCheckedVersion)
    }

    @Test
    fun `when both channels fail, report failure`() = runBlocking {
        val client = createMockClient {
            throw IOException("Network is down")
        }

        val checker = PublicUpdateChecker(
            marketplaceUrl = "http://mock.test/marketplace",
            githubUrl = "http://mock.test/github",
            timeoutMs = 1000L,
            client = client
        )

        val result = checker.check(currentVersion = "3.4.0")
        assertNull(result.updateInfo)
        assertFalse(result.isAlreadyLatest)
        assertNotNull(result.failedReason)
        assertTrue(result.failedReason!!.contains("Failed to fetch updates from public channels"))
    }

    private fun createMockClient(handler: (Request) -> Response): OkHttpClient {
        return OkHttpClient.Builder()
            .addInterceptor { chain -> handler(chain.request()) }
            .build()
    }
}
