package com.sickworm.intellij.jugg.deploy

import com.sickworm.intellij.jugg.manager.MockJugg
import com.sickworm.intellij.jugg.mock.RequiresDeviceRule
import com.sickworm.intellij.jugg.mock.logger
import org.junit.ClassRule
import org.junit.Test

class DeployTargetManagerTest {

    companion object {
        @ClassRule @JvmField val deviceRule = RequiresDeviceRule()
    }

    @Test
    fun test() {
        val jugg = MockJugg()
        val isForeground = jugg.deployTargetManager.isAppForeground(jugg.deployTargetManager.getSelectedDevices().first())
        logger.debug("isForeground: $isForeground")
    }
}