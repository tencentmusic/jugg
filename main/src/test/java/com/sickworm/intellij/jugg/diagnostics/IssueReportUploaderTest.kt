package com.sickworm.intellij.jugg.diagnostics

import com.sun.net.httpserver.HttpServer
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okio.Buffer
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class IssueReportUploaderTest {
    @get:Rule val temp = TemporaryFolder()

    @Test
    fun `automatic upload sends failure metadata and all backend manual uploads send identity`() {
        val requests = mutableListOf<String>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            assertEquals("POST", request.method)
            assertEquals(
                when (requests.size) {
                    1 -> "https://custom.example.com/report_issue"
                    3 -> IssueReportDestination.PUBLIC_REPORT_URL
                    else -> "https://selected.example.com/report_issue"
                },
                request.url.toString(),
            )
            assertTrue(request.body!!.contentType().toString().startsWith("multipart/form-data; boundary="))
            requests += Buffer().also { request.body!!.writeTo(it) }.readUtf8()
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200)
                .message("OK").body(okhttp3.ResponseBody.create(null, "{\"reportId\":\"a8df5845\"}")).build()
        }.build()
        val file = temp.newFile("a8df5845.zip").apply { writeText("zip") }
        val backend = IssueReportDestination.backend("https://selected.example.com")
        val custom = IssueReportDestination.backend("https://custom.example.com")
        val bundle = IssueReportBundle("a8df5845", file, emptyList(), backend)
        val uploader = IssueReportUploader(client)

        assertTrue(uploader.upload(bundle, IssueReportAutoUpload(
            failedReason = "Compile failed",
            errorDetail = "Foo.kt:12: error",
            projectName = "MyApplication",
            username = "developer",
            pluginVersion = "2.2.0-rc10",
        )).isSuccess)
        assertTrue(uploader.upload(
            bundle.copy(destination = custom),
            projectName = "MyApplication", username = "developer",
        ).isSuccess)
        assertTrue(uploader.upload(bundle, projectName = "MyApplication", username = "developer").isSuccess)
        assertTrue(uploader.upload(
            bundle.copy(destination = IssueReportDestination.Public),
            projectName = "MyApplication", username = "developer",
        ).isSuccess)

        assertTrue(requests[0].contains("name=\"file\"; filename=\"a8df5845.zip\""))
        listOf(
            "is_auto_upload" to "true", "failed_reason" to "Compile failed",
            "error_detail" to "Foo.kt:12: error", "project_name" to "MyApplication",
            "username" to "developer", "plugin_version" to "2.2.0-rc10", "report_id" to "a8df5845",
        ).forEach { (name, value) ->
            assertTrue(requests[0].contains("name=\"$name\""), name)
            assertTrue(requests[0].contains("\r\n$value\r\n"), name)
        }
        listOf(1, 2).forEach { index ->
            listOf("project_name" to "MyApplication", "username" to "developer")
                .forEach { (name, value) ->
                    assertTrue(requests[index].contains("name=\"$name\""), name)
                    assertTrue(requests[index].contains("\r\n$value\r\n"), name)
                }
            listOf("is_auto_upload", "failed_reason", "error_detail", "report_id", "plugin_version").forEach { name ->
                assertTrue(!requests[index].contains("name=\"$name\""), name)
            }
            assertTrue(requests[index].contains("name=\"file\"; filename=\"a8df5845.zip\""))
        }
        assertTrue(requests[3].contains("name=\"file\"; filename=\"a8df5845.zip\""))
        listOf("project_name", "username", "plugin_version", "is_auto_upload").forEach { name ->
            assertTrue(!requests[3].contains("name=\"$name\""), name)
        }
    }

    @Test
    fun `custom report URL appends endpoint once and default stays public`() {
        assertEquals(IssueReportDestination.PUBLIC_REPORT_URL, IssueReportDestination.Public.uploadUrl)
        assertEquals("https://custom.example.com/api/report_issue",
            IssueReportDestination.backend("https://custom.example.com/api/").uploadUrl)
        assertTrue(IssueReportDestination.backend("https://jugg.sickworm.com").redactLogs)
        assertTrue(IssueReportDestination.backend("https://JUGG.SICKWORM.COM./").redactLogs)
        val file = temp.newFile("report.zip")
        val httpClient = OkHttpClient.Builder().addInterceptor { chain ->
            assertEquals("http://custom.example.com/report_issue", chain.request().url.toString())
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200)
                .message("OK").body(okhttp3.ResponseBody.create(null, "{}")).build()
        }.build()
        val backendResult = IssueReportUploader(httpClient).upload(
            IssueReportBundle("report", file, emptyList(), IssueReportDestination.backend("http://custom.example.com")),
        )
        assertTrue(backendResult.isSuccess)
    }

    @Test
    fun `public upload refuses a bundle containing original diagnostics`() {
        val file = temp.newFile("raw.zip")
        val bundle = IssueReportBundle("raw", file, listOf(
            IssueReportEntry("diagnostics/logs/compile.log", 1, IssueReportSensitivity.MEDIUM, "none"),
        ), IssueReportDestination.Public)

        val result = IssueReportUploader().upload(bundle)

        assertTrue(!result.isSuccess)
        assertTrue(result.errorMessage!!.contains("Unredacted diagnostics"))
    }

    @Test
    fun `backend upload does not follow redirects to another server`() {
        val forwarded = AtomicInteger()
        val receiver = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/report_issue") { exchange ->
                forwarded.incrementAndGet()
                exchange.sendResponseHeaders(200, -1)
                exchange.close()
            }
            start()
        }
        val backend = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/report_issue") { exchange ->
                exchange.responseHeaders.add("Location", "http://127.0.0.1:${receiver.address.port}/report_issue")
                exchange.sendResponseHeaders(307, -1)
                exchange.close()
            }
            start()
        }
        try {
            val file = temp.newFile("original.zip").apply { writeText("original") }
            val bundle = IssueReportBundle("original", file, emptyList(),
                IssueReportDestination.backend("http://127.0.0.1:${backend.address.port}"))

            val result = IssueReportUploader().upload(bundle)

            assertTrue(!result.isSuccess)
            assertTrue(result.errorMessage!!.contains("[307]"))
            assertEquals(0, forwarded.get())
        } finally {
            backend.stop(0)
            receiver.stop(0)
        }
    }

    @Test
    fun `validate accepts only credential-free https endpoint`() {
        assertEquals("https://example.com/report_issue", IssueReportUploader.validateUrl(" https://example.com/report_issue ").toString())
        assertFailsWith<IllegalArgumentException> { IssueReportUploader.validateUrl("http://example.com/report_issue") }
        assertFailsWith<IllegalArgumentException> { IssueReportUploader.validateUrl("https://user:password@example.com/report_issue") }
        assertFailsWith<IllegalArgumentException> { IssueReportUploader.validateUrl("https://example.com/report_issue?token=secret") }
        assertFailsWith<IllegalArgumentException> { IssueReportUploader.validateUrl("https://example.com/report_issue#fragment") }
    }
}
