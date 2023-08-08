package com.sickworm.intellij.jugg.deploy

import com.sickworm.intellij.jugg.manager.MockJugg
import com.sickworm.intellij.jugg.mock.logger
import org.junit.Test

class DeployTargetManagerTest {

    @Test
    fun test() {
        val jugg = MockJugg()
        val isForeground = jugg.deployTargetManager.isAppForeground()
        logger.debug("isForeground: $isForeground")
    }
}