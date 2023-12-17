package com.sickworm.intellij.jugg.deploy

import com.sickworm.intellij.jugg.manager.MockJugg
import com.sickworm.intellij.jugg.mock.AdbDeviceHelper
import com.sickworm.intellij.jugg.mock.RequiresDevice
import com.sickworm.intellij.jugg.mock.logger
import org.junit.Test

@RequiresDevice
class DeployTargetManagerTest {

    @Test
    fun test() {
        val jugg = MockJugg()
        val isForeground = jugg.deployTargetManager.isAppForeground(jugg.deployTargetManager.getDevices().first())
        logger.debug("isForeground: $isForeground")
    }
}