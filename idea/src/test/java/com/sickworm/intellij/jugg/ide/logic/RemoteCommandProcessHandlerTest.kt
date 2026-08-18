package com.sickworm.intellij.jugg.ide.logic

import com.sickworm.intellij.jugg.mock.TestGlobal
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteCommandProcessHandlerTest {

    @Test
    fun `stop requests cancellation without reporting success`() {
        TestGlobal.init()
        var canceled = false
        val handler = RemoteCommandProcessHandler { canceled = true }
        handler.startNotify()

        handler.destroyProcess()

        assertTrue(canceled)
        assertFalse(handler.isProcessTerminated)

        handler.complete(1)

        assertTrue(handler.isProcessTerminated)
    }
}
