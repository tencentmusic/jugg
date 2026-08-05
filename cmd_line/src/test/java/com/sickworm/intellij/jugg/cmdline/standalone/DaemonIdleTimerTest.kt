package com.sickworm.intellij.jugg.cmdline.standalone

import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DaemonIdleTimerTest {

    @Test
    fun `external request refreshes idle deadline`() {
        val activity = StandaloneDaemonActivity()
        val timer = DaemonIdleTimer(activity, idleTimeoutMillis = 100, recheckMillis = 10) {}

        timer.recordExternalActivity(nowMillis = 50)

        assertFalse(timer.shouldExit(nowMillis = 149))
        assertTrue(timer.shouldExit(nowMillis = 150))
        timer.close()
    }

    @Test
    fun `active work postpones exit until next idle check`() {
        val activity = StandaloneDaemonActivity()
        val timer = DaemonIdleTimer(activity, idleTimeoutMillis = 100, recheckMillis = 10) {}
        timer.recordExternalActivity(nowMillis = 0)
        activity.beginJob()

        assertFalse(timer.shouldExit(nowMillis = 100))

        activity.endJob()
        assertTrue(timer.shouldExit(nowMillis = 110))
        timer.close()
    }

    @Test
    fun `project writes and update downloads also postpone exit`() {
        val activity = StandaloneDaemonActivity()
        val timer = DaemonIdleTimer(activity, idleTimeoutMillis = 100, recheckMillis = 10) {}
        timer.recordExternalActivity(nowMillis = 0)
        activity.beginProjectWrite()
        activity.beginUpdateDownload()

        assertFalse(timer.shouldExit(nowMillis = 100))

        activity.endProjectWrite()
        activity.endUpdateDownload()
        assertTrue(timer.shouldExit(nowMillis = 110))
        timer.close()
    }
}
