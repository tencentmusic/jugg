package com.sickworm.intellij.jugg.ide.ui

import com.sickworm.intellij.jugg.mock.TestGlobal
import org.junit.Test

class WindowsRsyncSetupDialogTest {
    @Test
    fun `language copies current preview and clipboard failure preserves it`() {
        TestGlobal.init()
        WindowsRsyncSetupDialogAssertions().languageCopiesCurrentPreviewAndClipboardFailurePreservesIt()
    }
}
