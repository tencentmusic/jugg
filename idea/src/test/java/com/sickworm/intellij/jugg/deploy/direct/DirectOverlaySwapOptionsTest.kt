package com.sickworm.intellij.jugg.deploy.direct

import com.sickworm.intellij.jugg.mock.TestGlobal
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DirectOverlaySwapOptionsTest {

    private val logger = TestGlobal.getLogger()

    @Test
    fun `enabled should require settings device offline and caller consent`() {
        val allowed = DirectOverlaySwapOptions.create(
            settingsEnabled = true,
            isDeviceReadyDeploy = false,
            isAllowedByCaller = true,
            logger = logger,
            adb = null,
        )
        assertTrue(allowed.enabled)

        assertFalse(
            DirectOverlaySwapOptions.create(
                settingsEnabled = false,
                isDeviceReadyDeploy = false,
                isAllowedByCaller = true,
                logger = logger,
                adb = null,
            ).enabled,
        )
        assertFalse(
            DirectOverlaySwapOptions.create(
                settingsEnabled = true,
                isDeviceReadyDeploy = true,
                isAllowedByCaller = true,
                logger = logger,
                adb = null,
            ).enabled,
        )
        assertFalse(
            DirectOverlaySwapOptions.create(
                settingsEnabled = true,
                isDeviceReadyDeploy = false,
                isAllowedByCaller = false,
                logger = logger,
                adb = null,
            ).enabled,
        )
    }
}
