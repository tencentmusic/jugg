package com.sickworm.intellij.jugg.ai.mcp.actions

import com.sickworm.intellij.jugg.ai.mcp.McpErrorCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ViewHierarchyFailureDiagnoserTest {

    @Test
    fun `incomplete capture is distinct from a missing element`() {
        val result = ViewHierarchyFailureDiagnoser.toolError(
            "tap", "ViewHierarchy server error: UI_HIERARCHY_INCOMPLETE: Compose extraction failed: tooling",
        )
        assertEquals("UI_HIERARCHY_INCOMPLETE", result.errorCode)
        assertTrue(result.message.contains("tooling"))
    }

    @Test
    fun `missing kotlin runtime is reported as unsupported`() {
        val result = ViewHierarchyFailureDiagnoser.toolError(
            toolName = "view-inspect",
            errorMessage = "eval_view failed: Kotlin runtime is unavailable; this feature is not supported",
        )

        assertEquals(McpErrorCode.FEATURE_NOT_SUPPORTED, result.errorCode)
        assertTrue(result.message.contains("Kotlin runtime is unavailable; this feature is not supported"))
    }

    @Test
    fun `other runtime failures remain internal errors`() {
        val result = ViewHierarchyFailureDiagnoser.toolError(
            toolName = "tap",
            errorMessage = "find_and_tap failed: unexpected failure",
        )

        assertEquals(McpErrorCode.INTERNAL_ERROR, result.errorCode)
    }
}
