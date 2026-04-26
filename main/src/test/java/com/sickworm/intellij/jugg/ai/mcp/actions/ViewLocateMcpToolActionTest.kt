package com.sickworm.intellij.jugg.ai.mcp.actions

import org.junit.Assert
import org.junit.Test

/**
 * ViewLocateMcpToolActionTest verifies that UiFindMcpToolAction exposes the new
 * tool name "view-locate" and contains the expected description keywords after
 * the Plan-A rename.
 */
class ViewLocateMcpToolActionTest {

    private val action = UiFindMcpToolAction()

    @Test
    fun toolNameIsViewLocate() {
        Assert.assertEquals("view-locate", action.toolName)
    }

    @Test
    fun definitionNameMatchesToolName() {
        Assert.assertEquals("view-locate", action.definition.name)
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
            "description should reference view-inspect as alternative",
            action.definition.description.contains("view-inspect")
        )
    }
}
