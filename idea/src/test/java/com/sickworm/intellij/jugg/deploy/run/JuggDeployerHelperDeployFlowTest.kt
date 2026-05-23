package com.sickworm.intellij.jugg.deploy.run

import com.sickworm.intellij.jugg.deploy.direct.DirectOverlayStateCheckResult
import com.sickworm.intellij.jugg.deploy.direct.DirectOverlayStateChecker
import com.sickworm.intellij.jugg.deploy.run.JuggDeploymentService
import com.sickworm.intellij.jugg.deploy.run.deployflow.DeployFlowCaseId
import com.sickworm.intellij.jugg.deploy.run.deployflow.DeployFlowFixture
import com.sickworm.intellij.jugg.deploy.run.deployflow.DeployFlowMockBackend
import com.sickworm.intellij.jugg.deploy.run.deployflow.DeployFlowOverlaySeed
import com.sickworm.intellij.jugg.deploy.run.deployflow.VirtualDeployDevice
import com.sickworm.intellij.jugg.mock.logger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.mockito.Mockito

/**
 * L2 deploy-flow via [com.sickworm.intellij.jugg.deploy.run.deployflow.VirtualDeployDevice].
 * Spec: docs/task/jugg_deploy_flow_virtual_device.md, jugg_deployer_helper_deploy_flow_test_plan.md §5.1
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
        assertEquals(0, fixture.compatBoundary.optimisticSwapInvokeCount)
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
        assertEquals(0, fixture.compatBoundary.optimisticSwapInvokeCount)
    }

    @Test
    fun `DF-L2-003 recover dry skips reinstall when overlay triple matched`() {
        val fixture = DeployFlowMockBackend.buildFixture(DeployFlowCaseId.DF_L2_003)
        assertOverlayRecoverMatched(fixture)
        val recoverHost = requireNotNull(fixture.recoverRunHost)
        val result = fixture.helper.deploy(fixture.deployOptions)
        assertTrue("deploy failed: ${result.failedReason}", result.isSuccess)
        assertTrue(
            fixture.virtualDevice.hadRecoverMatchedOverlayCheckBeforeInstall(fixture.seededOverlayId),
        )
        assertEquals(0, recoverHost.installRecoverTaskCount)
        assertEquals(0, fixture.virtualDevice.installInvokeCount)
        Mockito.verify(fixture.deployTargetManager, Mockito.never()).restartApp(fixture.device)
        assertTrue(fixture.virtualDevice.hasDirectOverlayApply())
        assertEquals(0, fixture.compatBoundary.optimisticSwapInvokeCount)
    }

    @Test
    fun `DF-L2-004 direct write skipped falls back to Apply Changes`() {
        val fixture = DeployFlowMockBackend.buildFixture(DeployFlowCaseId.DF_L2_004)
        val result = fixture.helper.deploy(fixture.deployOptions)
        assertTrue("deploy failed: ${result.failedReason}", result.isSuccess)
        assertTrue(fixture.virtualDevice.failDirectOverlayPush)
        assertFalse(fixture.virtualDevice.hasDirectOverlayApply())
        assertTrue(
            "expected Apply Changes fallback after direct overlay push failure",
            fixture.compatBoundary.optimisticSwapInvokeCount >= 1,
        )
    }

    @Test
    fun `DF-L2-005 direct write dirty failure does not fall back to Apply Changes`() {
        val fixture = DeployFlowMockBackend.buildFixture(DeployFlowCaseId.DF_L2_005)
        assertEquals(VirtualDeployDevice.DirectOverlayWriteResult.APPLYING, fixture.virtualDevice.directOverlayWriteResult)
        val result = fixture.helper.deploy(fixture.deployOptions)
        assertFalse("deploy should fail on dirty direct overlay write", result.isSuccess)
        assertTrue(
            "failed reason should mention direct overlay",
            result.failedReason.orEmpty().contains("Direct overlay", ignoreCase = true),
        )
        assertEquals(0, fixture.compatBoundary.optimisticSwapInvokeCount)
        assertTrue(fixture.virtualDevice.shellScripts.any { it.contains("__JUGG_DIRECT_OVERLAY__") })
    }

    @Test
    fun `DF-L2-006 skips direct write when app is deployable and uses Apply Changes`() {
        val fixture = DeployFlowMockBackend.buildFixture(DeployFlowCaseId.DF_L2_006)
        val result = fixture.helper.deploy(fixture.deployOptions)
        assertTrue("deploy failed: ${result.failedReason}", result.isSuccess)
        assertFalse(fixture.virtualDevice.hasDirectOverlayApply())
        assertTrue(
            "expected Apply Changes when isDeviceReadyDeploy is true",
            fixture.compatBoundary.optimisticSwapInvokeCount >= 1,
        )
    }

    @Test
    fun `DF-L2-007 swap phase device mismatch falls back to Apply Changes without reinstall`() {
        val swapMismatchOverlayId = "swap-phase-mismatch-overlay"
        val fixture = DeployFlowMockBackend.buildFixture(DeployFlowCaseId.DF_L2_007)
        val recoverHost = requireNotNull(fixture.recoverRunHost)
        val result = fixture.helper.deploy(fixture.deployOptions)
        assertTrue("deploy failed: ${result.failedReason}", result.isSuccess)
        assertTrue(
            fixture.virtualDevice.hadRecoverMatchedOverlayCheckBeforeInstall(fixture.seededOverlayId),
        )
        assertTrue(fixture.virtualDevice.hadOverlayStateCheckWithDeviceId(swapMismatchOverlayId))
        assertEquals(0, recoverHost.installRecoverTaskCount)
        assertEquals(0, fixture.virtualDevice.installInvokeCount)
        assertFalse(fixture.virtualDevice.hasDirectOverlayApply())
        assertTrue(
            "expected Apply Changes after swap-phase checkDevice mismatch",
            fixture.compatBoundary.optimisticSwapInvokeCount >= 1,
        )
    }

    @Test
    fun `DF-L2-008 recover reinstall base cache then direct write with as startup agent push`() {
        val mismatchedDeviceOverlayId = "mismatched-device-overlay"
        val fixture = DeployFlowMockBackend.buildFixture(DeployFlowCaseId.DF_L2_008)
        val result = fixture.helper.deploy(fixture.deployOptions)
        assertTrue("deploy failed: ${result.failedReason}", result.isSuccess)
        assertTrue(
            fixture.virtualDevice.hadRecoverMismatchOverlayCheckBeforeInstallAndDirectWrite(mismatchedDeviceOverlayId),
        )
        assertTrue(fixture.virtualDevice.installInvokeCount >= 1)
        Mockito.verify(fixture.deployFileManager).resetAfterReinstall()
        assertTrue(fixture.virtualDevice.hasAsStartupAgentPush())
        assertTrue(fixture.virtualDevice.listStartupAgents().contains("dced2491-agent.so"))
        assertTrue(fixture.virtualDevice.hasDirectOverlayApply())
        assertNotEquals("", fixture.virtualDevice.readOverlayId().orEmpty())
        assertNotEquals(mismatchedDeviceOverlayId, fixture.virtualDevice.readOverlayId().orEmpty())
        assertEquals(0, fixture.compatBoundary.optimisticSwapInvokeCount)
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
