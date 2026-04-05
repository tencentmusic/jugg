package com.sickworm.intellij.jugg.mcp.actions

import org.junit.Assert
import org.junit.Test

/**
 * ViewLocateMcpToolActionTest verifies that UiFindMcpToolAction exposes the new
 * tool name "view_locate" and contains the expected description keywords after
 * the Plan-A rename.
 */
class ViewLocateMcpToolActionTest {

    private val action = UiFindMcpToolAction()

    @Test
    fun toolNameIsViewLocate() {
        Assert.assertEquals("view_locate", action.toolName)
    }

    @Test
    fun definitionNameMatchesToolName() {
        Assert.assertEquals("view_locate", action.definition.name)
    }

    @Test
    fun descriptionContainsUseFor() {
        Assert.assertTrue(
            "description should contain 'Use for'",
            action.definition.description.contains("Use for")
        )
    }

    @Test
    fun descriptionContainsDoNotUseFor() {
        Assert.assertTrue(
            "description should contain 'Do NOT use for'",
            action.definition.description.contains("Do NOT use for")
        )
    }

    @Test
    fun descriptionMentionsViewInspect() {
        Assert.assertTrue(
            "description should reference view_inspect as alternative",
            action.definition.description.contains("view_inspect")
        )
    }
}
