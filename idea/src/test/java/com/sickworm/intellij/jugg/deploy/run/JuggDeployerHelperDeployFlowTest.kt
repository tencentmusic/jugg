package com.sickworm.intellij.jugg.deploy.run

import com.sickworm.intellij.jugg.deploy.direct.DirectOverlayStateCheckResult
import com.sickworm.intellij.jugg.deploy.direct.DirectOverlayStateChecker
import com.sickworm.intellij.jugg.deploy.run.JuggDeploymentService
import com.sickworm.intellij.jugg.deploy.run.deployflow.DeployFlowCaseId
import com.sickworm.intellij.jugg.deploy.run.deployflow.DeployFlowFixture
import com.sickworm.intellij.jugg.deploy.run.deployflow.DeployFlowMockBackend
import com.sickworm.intellij.jugg.deploy.run.deployflow.DeployFlowOverlaySeed
import com.sickworm.intellij.jugg.mock.logger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.mockito.Mockito

/**
 * L2 deploy-flow via [com.sickworm.intellij.jugg.deploy.run.deployflow.VirtualDeployDevice].
 * Spec: docs/task/jugg_deploy_flow_virtual_device.md
 */
class JuggDeployerHelperDeployFlowTest {

    @Test
    fun `DF-L2-001 direct write incremental deploy when app not deployable`() {
        val fixture = DeployFlowMockBackend.buildFixture(DeployFlowCaseId.DF_L2_001)
        assertOverlayRecoverMatched(fixture)
        val result = fixture.helper.deploy(fixture.deployOptions)
        assertTrue("deploy failed: ${result.failedReason}", result.isSuccess)
        assertTrue(fixture.virtualDevice.hasDirectOverlayApply())
        assertNotEquals("", fixture.virtualDevice.readOverlayId().orEmpty())
    }

    @Test
    fun `DF-L2-002 recover reinstall then direct write when overlay mismatched`() {
        val mismatchedDeviceOverlayId = "mismatched-device-overlay"
        val fixture = DeployFlowMockBackend.buildFixture(DeployFlowCaseId.DF_L2_002)
        val result = fixture.helper.deploy(fixture.deployOptions)
        assertTrue("deploy failed: ${result.failedReason}", result.isSuccess)
        assertTrue(
            "recover must run DirectOverlayStateChecker with device overlay $mismatchedDeviceOverlayId " +
                "before reinstall/direct write (not APP_NOT_INSTALLED or legacy dry-deploy path)",
            fixture.virtualDevice.hadRecoverMismatchOverlayCheckBeforeInstallAndDirectWrite(mismatchedDeviceOverlayId),
        )
        assertTrue(fixture.virtualDevice.installInvokeCount >= 1)
        Mockito.verify(fixture.deployFileManager).resetAfterReinstall()
        assertTrue(fixture.virtualDevice.hasDirectOverlayApply())
        val deviceOverlayId = fixture.virtualDevice.readOverlayId().orEmpty()
        assertNotEquals("", deviceOverlayId)
        assertNotEquals(mismatchedDeviceOverlayId, deviceOverlayId)
    }

    private fun assertOverlayRecoverMatched(fixture: DeployFlowFixture) {
        val checker = DirectOverlayStateChecker(
            adb = fixture.virtualDevice.asIDeviceAdb(),
            logger = logger,
            deployHistoryManager = fixture.deployHistoryManager,
            deploymentService = JuggDeploymentService,
        )
        assertEquals(
            DirectOverlayStateCheckResult.MATCHED,
            checker.checkRecover(fixture.device.serialNumber, DeployFlowOverlaySeed.packageName()),
        )
    }

    companion object {
        @BeforeClass
        @JvmStatic
        fun initTestEnv() {
            DeployFlowMockBackend.initSettings()
        }
    }
}
