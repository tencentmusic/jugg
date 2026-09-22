package com.sickworm.intellij.jugg.ide.ui

import com.sickworm.intellij.jugg.diagnostics.IssueReportUploadResult
import org.junit.Test
import kotlin.test.assertEquals

class ReportIssueResultDialogTest {
    @Test
    fun `copied result identifies custom server on success and failure`() {
        val success = IssueReportUploadResult(true, "a8df5845", null)
        val failure = IssueReportUploadResult(false, null, "HTTP 500")

        assertEquals("Jugg report: a8df5845", reportIssueClipboardText(success, null))
        assertEquals("Jugg report: a8df5845\nServer Url: https://custom.example.com",
            reportIssueClipboardText(success, "https://custom.example.com"))
        assertEquals("Jugg report: HTTP 500\nServer Url: https://custom.example.com",
            reportIssueClipboardText(failure, "https://custom.example.com"))
    }
}
