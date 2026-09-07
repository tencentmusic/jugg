package com.sickworm.intellij.jugg.ide.ui

import com.intellij.openapi.util.Disposer
import com.sickworm.intellij.jugg.mock.TestGlobal
import com.sickworm.intellij.jugg.server.protocols.HotUpdateData
import org.junit.Assert.assertEquals
import org.junit.Test
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JRootPane
import javax.swing.SwingUtilities

class CheckUpdatesProgressDialogTest {

    @Test
    fun `missing backend should not be reported as latest version`() {
        TestGlobal.init()
        lateinit var dialog: CheckUpdatesProgressDialog
        SwingUtilities.invokeAndWait {
            dialog = CheckUpdatesProgressDialog()
            dialog.setHotUpdateData(null) {}
        }
        SwingUtilities.invokeAndWait {
            try {
                assertEquals(
                    "Jugg backend server is unavailable. Configure a Custom Server in Jugg Settings.",
                    dialog.statusText(),
                )
            } finally {
                Disposer.dispose(dialog.disposable)
            }
        }
    }

    @Test
    fun `backend response without update should report latest version`() {
        TestGlobal.init()
        lateinit var dialog: CheckUpdatesProgressDialog
        SwingUtilities.invokeAndWait {
            dialog = CheckUpdatesProgressDialog()
            dialog.setHotUpdateData(
                HotUpdateData(false, "3.4.1", null, emptyList(), false),
            ) {}
        }
        SwingUtilities.invokeAndWait {
            try {
                assertEquals("Jugg is already the latest version.", dialog.statusText())
            } finally {
                Disposer.dispose(dialog.disposable)
            }
        }
    }

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

    private fun CheckUpdatesProgressDialog.statusText(): String {
        val method = CheckUpdatesProgressDialog::class.java.getDeclaredMethod("createCenterPanel")
        method.isAccessible = true
        return (method.invoke(this) as JPanel).components.filterIsInstance<JLabel>().single().text
    }

    private class ShowingRootPane : JRootPane() {
        var isOwnerShowing = true

        override fun isShowing(): Boolean = isOwnerShowing
    }
}
