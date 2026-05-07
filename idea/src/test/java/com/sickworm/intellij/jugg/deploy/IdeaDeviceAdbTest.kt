package com.sickworm.intellij.jugg.deploy

import org.junit.Test
import java.io.IOException
import java.io.InterruptedIOException
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IdeaDeviceAdbTest {

    @Test
    fun `operation interrupted is expected streaming stop`() {
        val exception = IOException("Operation interrupted", InterruptedException())

        assertTrue(isExpectedStreamingStop(exception) { false })
    }

    @Test
    fun `interrupted io is expected streaming stop`() {
        val exception = InterruptedIOException("interrupted")

        assertTrue(isExpectedStreamingStop(exception) { false })
    }

    @Test
    fun `canceled streaming exception is expected stop`() {
        val exception = IOException("receiver closed")

        assertTrue(isExpectedStreamingStop(exception) { true })
    }

    @Test
    fun `regular streaming exception remains unexpected`() {
        val exception = IOException("device offline")

        assertFalse(isExpectedStreamingStop(exception) { false })
    }
}
