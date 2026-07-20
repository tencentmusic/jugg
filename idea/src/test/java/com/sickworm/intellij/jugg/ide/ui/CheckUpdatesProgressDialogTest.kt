package com.sickworm.intellij.jugg.ide.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import javax.swing.JButton
import javax.swing.JRootPane
import javax.swing.SwingUtilities

class CheckUpdatesProgressDialogTest {

    @Test
    fun `reopen action should run after owner dialog closes`() {
        val events = mutableListOf<String>()
        val ownerRootPane = ShowingRootPane().apply {
            defaultButton = JButton().apply {
                addActionListener {
                    events += "close owner"
                    isOwnerShowing = false
                }
            }
        }

        invokeCloseOwnerAndRun(ownerRootPane) {
            events += "reopen"
        }
        SwingUtilities.invokeAndWait {}

        assertEquals(listOf("close owner", "reopen"), events)
    }

    @Test
    fun `reopen action should wait when owner dialog remains open`() {
        val events = mutableListOf<String>()
        val ownerRootPane = ShowingRootPane().apply {
            defaultButton = JButton().apply {
                addActionListener { events += "close owner" }
            }
        }

        invokeCloseOwnerAndRun(ownerRootPane) {
            events += "reopen"
        }
        SwingUtilities.invokeAndWait {}

        assertEquals(listOf("close owner"), events)
    }

    @Test
    fun `reopen action should run directly without owner dialog`() {
        val events = mutableListOf<String>()

        invokeCloseOwnerAndRun(null) {
            events += "reopen"
        }

        assertEquals(listOf("reopen"), events)
    }

    private fun invokeCloseOwnerAndRun(ownerRootPane: JRootPane?, action: () -> Unit) {
        val method = Class.forName("com.sickworm.intellij.jugg.ide.ui.CheckUpdatesProgressDialogKt").getDeclaredMethod(
            "closeOwnerAndRun",
            JRootPane::class.java,
            Function0::class.java,
        )
        method.isAccessible = true
        method.invoke(null, ownerRootPane, action)
    }

    private class ShowingRootPane : JRootPane() {
        var isOwnerShowing = true

        override fun isShowing(): Boolean = isOwnerShowing
    }
}
