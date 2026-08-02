package com.sickworm.intellij.jugg.diagnostics

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class IssueReportUploaderTest {

    @Test
    fun `validate accepts only credential-free https endpoint`() {
        assertEquals("https://example.com/report_issue", IssueReportUploader.validateUrl(" https://example.com/report_issue ").toString())
        assertFailsWith<IllegalArgumentException> { IssueReportUploader.validateUrl("http://example.com/report_issue") }
        assertFailsWith<IllegalArgumentException> { IssueReportUploader.validateUrl("https://user:password@example.com/report_issue") }
        assertFailsWith<IllegalArgumentException> { IssueReportUploader.validateUrl("https://example.com/report_issue?token=secret") }
        assertFailsWith<IllegalArgumentException> { IssueReportUploader.validateUrl("https://example.com/report_issue#fragment") }
    }
}
