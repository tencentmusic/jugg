package com.sickworm.intellij.jugg.ai.mcp.actions

import com.sickworm.intellij.jugg.ai.mcp.IMcpRuntime
import com.sickworm.intellij.jugg.ai.mcp.McpErrorCode
import com.sickworm.intellij.jugg.ai.mcp.McpToolStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test
import org.mockito.kotlin.mock

class ReportIssueMcpToolActionTest {

    @Test
    fun `default registry exposes both report phases`() {
        val toolNames = McpToolActionRegistry.defaultActions().map { it.toolName }

        assertTrue(McpToolActionRegistry.ToolNames.REPORT_PREPARE in toolNames)
        assertTrue(McpToolActionRegistry.ToolNames.REPORT_UPLOAD in toolNames)
    }

    @Test
    fun `upload requires the prepared report identity and digest`() {
        val schema = UploadIssueReportMcpToolAction().definition.inputSchema

        assertEquals(listOf("projectDir", "reportId", "sha256"), schema.required)
        assertEquals("^[0-9a-f]{8}$", schema.properties.getValue("reportId").pattern)
        assertEquals("^[0-9a-fA-F]{64}$", schema.properties.getValue("sha256").pattern)
    }

    @Test
    fun `upload rejects missing report identity before reading files`() {
        val result = UploadIssueReportMcpToolAction().execute(emptyMap(), mock<IMcpRuntime>())

        assertEquals(McpToolStatus.ERROR, result.status)
        assertEquals(McpErrorCode.INVALID_PARAMS, result.errorCode)
    }

    @Test
    fun `upload success matches IDEA message without temporary file details`() {
        val result = reportUploadSuccessResult("a1b2c3d4")

        assertEquals("Report uploaded. Jugg Report ID: a1b2c3d4", result.message)
        assertEquals(mapOf("reportId" to "a1b2c3d4"), result.data)
        assertTrue(result.artifacts.isEmpty())
        val output = result.toString()
        assertFalse(output.contains("entries"))
        assertFalse(output.contains("filePath"))
        assertFalse(output.contains("path="))
        assertFalse(output.contains("type="))
    }
}
