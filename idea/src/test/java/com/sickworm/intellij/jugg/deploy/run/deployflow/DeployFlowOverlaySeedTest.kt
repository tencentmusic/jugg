package com.sickworm.intellij.jugg.deploy.run.deployflow

import com.sickworm.intellij.jugg.deploy.direct.DirectOverlayStateCheckResult
import com.sickworm.intellij.jugg.deploy.direct.DirectOverlayStateChecker
import com.sickworm.intellij.jugg.deploy.run.JuggDeploymentService
import com.sickworm.intellij.jugg.deploy.run.utils.AdbLogWrapper
import com.sickworm.intellij.jugg.mock.TestGlobal
import com.sickworm.intellij.jugg.mock.logger
import org.junit.Assert.assertEquals
import org.junit.BeforeClass
import org.junit.Test

class DeployFlowOverlaySeedTest {

    @Test
    fun `seedMatchedTriple should satisfy checkRecover MATCHED`() {
        val virtualDevice = VirtualDeployDevice(DeployFlowOverlaySeed.packageName())
        val history = DeployFlowTestHistoryManager()
        val overlayId = DeployFlowOverlaySeed.seedMatchedTriple(
            virtualDevice = virtualDevice,
            deploymentService = JuggDeploymentService,
            deployHistoryManager = history,
        )
        val checker = DirectOverlayStateChecker(
            adb = virtualDevice.asIDeviceAdb(),
            logger = logger,
            deployHistoryManager = history,
            deploymentService = JuggDeploymentService,
        )
        val result = checker.checkRecover(virtualDevice.serial, DeployFlowOverlaySeed.packageName())
        assertEquals(
            "overlayId=$overlayId device=${virtualDevice.readOverlayId()} history=${history.lastDeployOverlayIds}",
            DirectOverlayStateCheckResult.MATCHED,
            result,
        )
    }

    companion object {
        @BeforeClass
        @JvmStatic
        fun init() {
            DeployFlowMockBackend.initSettings()
        }
    }
}
