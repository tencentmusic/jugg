package com.sickworm.intellij.jugg.deploy.run.utils

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdbTransientOfflineTest {

    @Test
    fun `transport recovered when cli ready even if ddmlib reports offline`() {
        val recovered = AdbTransientOffline.isTransportRecovered(
            serial = "emulator-5554",
            isDeviceOnline = { false },
            isDdmlibShellReady = { false },
            isCliTransportReady = { true },
        )

        assertTrue(recovered)
    }

    @Test
    fun `transport recovered when ddmlib ready even if cli not ready`() {
        val recovered = AdbTransientOffline.isTransportRecovered(
            serial = "emulator-5554",
            isDeviceOnline = { true },
            isDdmlibShellReady = { true },
            isCliTransportReady = { false },
        )

        assertTrue(recovered)
    }

    @Test
    fun `transport not recovered when both cli and ddmlib are unavailable`() {
        val recovered = AdbTransientOffline.isTransportRecovered(
            serial = "emulator-5554",
            isDeviceOnline = { false },
            isDdmlibShellReady = { false },
            isCliTransportReady = { false },
        )

        assertFalse(recovered)
    }
}
