package com.sickworm.intellij.jugg.ide.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.ui.components.JBTextArea
import com.sickworm.intellij.jugg.mock.TestGlobal
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito
import javax.swing.SwingUtilities

class RemoteCommandDialogTest {

    @Test
    fun `run button should only enable for nonblank command`() {
        TestGlobal.init()
        SwingUtilities.invokeAndWait {
            val dialog = RemoteCommandDialog(
                Mockito.mock(Project::class.java),
                "jugg:app",
                "user@host:22",
                "/remote/project",
                emptyList(),
            )

            try {
                val commandTextArea = dialog.preferredFocusedComponent as JBTextArea

                assertFalse(dialog.isOKActionEnabled)
                commandTextArea.text = "pwd"
                assertTrue(dialog.isOKActionEnabled)
                commandTextArea.text = "   "
                assertFalse(dialog.isOKActionEnabled)
            } finally {
                Disposer.dispose(dialog.disposable)
            }
        }
    }
}
