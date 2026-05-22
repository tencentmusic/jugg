package com.sickworm.intellij.jugg.deploy

import com.sickworm.intellij.jugg.deploy.run.utils.AdbTransientOffline
import org.junit.Test
import java.io.IOException
import java.io.InterruptedIOException
import java.nio.file.Files
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class IdeaDeviceAdbTest {

    @Test
    fun `adb command rejected device offline is transient offline`() {
        val exception = IOException("com.android.ddmlib.AdbCommandRejectedException: device offline")

        assertTrue(AdbTransientOffline.isOffline(exception))
    }

    @Test
    fun `adb cli device offline output is transient offline`() {
        assertTrue(AdbTransientOffline.isOfflineMessage("adb: device offline"))
    }

    @Test
    fun `deployer invalid tag zero is transient offline`() {
        val reason = "com.android.tools.idea.protobuf.InvalidProtocolBufferException: Protocol message contained an invalid tag (zero)."

        assertTrue(AdbTransientOffline.isOfflineMessage(reason))
    }

    @Test
    fun `overlay mismatch is not transient offline`() {
        val reason = "The target app on the device is in a state unknown to Studio"

        assertFalse(AdbTransientOffline.isOfflineMessage(reason))
    }

    @Test
    fun `wait until ready succeeds after probe recovers`() {
        var probeCount = 0

        val result = AdbTransientOffline.waitUntilReady(maxWaitMillis = 10, pollIntervalMillis = 1) {
            probeCount++
            probeCount == 2
        }

        assertTrue(result)
    }

    @Test
    fun `wait until ready fails when probe never recovers`() {
        val result = AdbTransientOffline.waitUntilReady(maxWaitMillis = 0, pollIntervalMillis = 1) {
            false
        }

        assertFalse(result)
    }

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

    @Test
    fun `adb cli fallback times out instead of blocking forever`() {
        val adbScript = Files.createTempFile("jugg-fake-adb", ".sh").toFile()
        adbScript.writeText("#!/bin/sh\nsleep 5\n")
        adbScript.setExecutable(true)

        assertFailsWith<IOException> {
            AdbCliShellExecutor.exec(
                adbBin = adbScript.absolutePath,
                serial = "emulator-5554",
                cmd = "am force-stop com.example.myapplication",
                timeoutMillis = 10,
            )
        }
    }
}
