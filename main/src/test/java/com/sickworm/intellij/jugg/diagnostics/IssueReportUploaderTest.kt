package com.sickworm.intellij.jugg.diagnostics

import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okio.Buffer
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class IssueReportUploaderTest {
    @get:Rule val temp = TemporaryFolder()

    @Test
    fun `automatic upload sends failure metadata and custom manual upload sends identity only`() {
        val requests = mutableListOf<String>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            assertEquals("POST", request.method)
            assertEquals(
                if (requests.size == 2) IssueReportUploader.JUGG_REPORT_URL else "https://example.com/report_issue",
                request.url.toString(),
            )
            assertTrue(request.body!!.contentType().toString().startsWith("multipart/form-data; boundary="))
            requests += Buffer().also { request.body!!.writeTo(it) }.readUtf8()
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200)
                .message("OK").body(okhttp3.ResponseBody.create(null, "{\"reportId\":\"a8df5845\"}")).build()
        }.build()
        val file = temp.newFile("a8df5845.zip").apply { writeText("zip") }
        val bundle = IssueReportBundle("a8df5845", file, emptyList())
        val uploader = IssueReportUploader(client)

        assertTrue(uploader.upload(bundle, "https://example.com/report_issue", IssueReportAutoUpload(
            failedReason = "Compile failed",
            errorDetail = "Foo.kt:12: error",
            projectName = "MyApplication",
            username = "developer",
            pluginVersion = "2.2.0-rc10",
        )).isSuccess)
        assertTrue(uploader.upload(
            bundle, IssueReportUploader.reportUrl("https://example.com"),
            projectName = "MyApplication", username = "developer",
        ).isSuccess)
        assertTrue(uploader.upload(bundle, IssueReportUploader.JUGG_REPORT_URL).isSuccess)

        assertTrue(requests[0].contains("name=\"file\"; filename=\"a8df5845.zip\""))
        listOf(
            "is_auto_upload" to "true", "failed_reason" to "Compile failed",
            "error_detail" to "Foo.kt:12: error", "project_name" to "MyApplication",
            "username" to "developer", "plugin_version" to "2.2.0-rc10", "report_id" to "a8df5845",
        ).forEach { (name, value) ->
            assertTrue(requests[0].contains("name=\"$name\""), name)
            assertTrue(requests[0].contains("\r\n$value\r\n"), name)
        }
        listOf("project_name" to "MyApplication", "username" to "developer")
            .forEach { (name, value) ->
                assertTrue(requests[1].contains("name=\"$name\""), name)
                assertTrue(requests[1].contains("\r\n$value\r\n"), name)
            }
        listOf("is_auto_upload", "failed_reason", "error_detail", "report_id", "plugin_version").forEach { name ->
            assertTrue(!requests[1].contains("name=\"$name\""), name)
        }
        assertTrue(requests[1].contains("name=\"file\"; filename=\"a8df5845.zip\""))
        assertTrue(requests[2].contains("name=\"file\"; filename=\"a8df5845.zip\""))
        listOf("project_name", "username", "plugin_version", "is_auto_upload").forEach { name ->
            assertTrue(!requests[2].contains("name=\"$name\""), name)
        }
    }

    @Test
    fun `custom report URL appends endpoint once and default stays public`() {
        assertEquals(IssueReportUploader.JUGG_REPORT_URL, IssueReportUploader.reportUrl(null))
        assertEquals("https://custom.example.com/api/report_issue", IssueReportUploader.reportUrl("https://custom.example.com/api/"))
        val file = temp.newFile("report.zip")
        val result = IssueReportUploader().upload(
            IssueReportBundle("report", file, emptyList()), IssueReportUploader.reportUrl("http://custom.example.com"),
        )
        assertTrue(!result.isSuccess)
        assertTrue(result.errorMessage!!.contains("HTTPS"))
        val httpClient = OkHttpClient.Builder().addInterceptor { chain ->
            assertEquals("http://custom.example.com/report_issue", chain.request().url.toString())
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200)
                .message("OK").body(okhttp3.ResponseBody.create(null, "{}")).build()
        }.build()
        val backendResult = IssueReportUploader(httpClient).upload(
            IssueReportBundle("report", file, emptyList()), IssueReportUploader.reportUrl("http://custom.example.com"),
            allowHttpForBackend = true,
        )
        assertTrue(backendResult.isSuccess)
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
