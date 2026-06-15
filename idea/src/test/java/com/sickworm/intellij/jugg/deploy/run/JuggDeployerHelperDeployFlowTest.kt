package com.sickworm.intellij.jugg.deploy.run

import com.sickworm.intellij.jugg.deploy.direct.DirectOverlayStateCheckResult
import com.sickworm.intellij.jugg.deploy.direct.DirectOverlayStateChecker
import com.sickworm.intellij.jugg.deploy.run.JuggDeploymentService
import com.sickworm.intellij.jugg.compiler.CompileUiHandler
import com.sickworm.intellij.jugg.deploy.run.deployflow.DeployFlowCaseId
import com.sickworm.intellij.jugg.deploy.run.deployflow.DeployFlowFixture
import com.sickworm.intellij.jugg.deploy.run.deployflow.DeployFlowMockBackend
import com.sickworm.intellij.jugg.deploy.run.deployflow.DeployFlowOverlaySeed
import com.sickworm.intellij.jugg.deploy.run.deployflow.VirtualDeployDevice
import com.sickworm.intellij.jugg.ide.bean.JuggSettings
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
        Mockito.verify(fixture.deployTargetManager, Mockito.times(1)).restartApp(fixture.device)
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

    @Test
    fun `DF-L2-009 empty Apply Changes does not create debugger redefiners`() {
        val fixture = DeployFlowMockBackend.buildFixture(DeployFlowCaseId.DF_L2_009)
        val result = fixture.helper.deploy(fixture.deployOptions)

        assertTrue("deploy failed: ${result.failedReason}", result.isSuccess)
        assertEquals(1, fixture.compatBoundary.optimisticSwapInvokeCount)
        assertEquals(0, fixture.compatBoundary.makeDebuggerRedefinersInvokeCount)
    }

    @Test
    fun `debug restart flag restarts app after hot reload deploy`() {
        val fixture = DeployFlowMockBackend.buildFixture(DeployFlowCaseId.DF_L2_003)
        val compileUiHandler = object : CompileUiHandler by CompileUiHandler.DEFAULT {
            override val isAlwaysRestartApp: Boolean = true
        }

        val result = fixture.helper.deploy(fixture.deployOptions.copy(compileUiHandler = compileUiHandler))

        assertTrue("deploy failed: ${result.failedReason}", result.isSuccess)
        Mockito.verify(fixture.deployTargetManager, Mockito.times(1)).restartApp(fixture.device)
    }

    @Test
    fun `always restart flag does not restart app after empty deploy when app is foreground`() {
        val fixture = DeployFlowMockBackend.buildFixture(DeployFlowCaseId.DF_L2_009)
        val compileUiHandler = object : CompileUiHandler by CompileUiHandler.DEFAULT {
            override val isAlwaysRestartApp: Boolean = true
        }
        Mockito.`when`(fixture.deployTargetManager.isAppForeground(fixture.device)).thenReturn(true)

        val result = fixture.helper.deploy(fixture.deployOptions.copy(compileUiHandler = compileUiHandler))

        assertTrue("deploy failed: ${result.failedReason}", result.isSuccess)
        assertFalse(result.hasDeployChanges)
        Mockito.verify(fixture.deployTargetManager, Mockito.never()).restartApp(fixture.device)
        Mockito.verify(fixture.deployTargetManager, Mockito.never()).startApp(fixture.device)
    }

    @Test
    fun `debug run starts app with debugger wait after empty deploy`() {
        val fixture = DeployFlowMockBackend.buildFixture(DeployFlowCaseId.DF_L2_009)
        val compileUiHandler = object : CompileUiHandler by CompileUiHandler.DEFAULT {
            override val isAlwaysRestartApp: Boolean = true
            override val isDebugRun: Boolean = true
        }

        val result = fixture.helper.deploy(fixture.deployOptions.copy(compileUiHandler = compileUiHandler))

        assertTrue("deploy failed: ${result.failedReason}", result.isSuccess)
        assertFalse(result.hasDeployChanges)
        Mockito.verify(fixture.deployTargetManager, Mockito.times(1)).restartAppForDebug(fixture.device)
        Mockito.verify(fixture.deployTargetManager, Mockito.never()).restartApp(fixture.device)
    }

    @Test
    fun `split full resource deploy restarts activity only on final slice`() {
        withSingleOverlayPerSlice {
            val fixture = DeployFlowMockBackend.buildFixture(DeployFlowCaseId.DF_L2_010)

            val result = fixture.helper.deploy(fixture.deployOptions)

            assertTrue("deploy failed: ${result.failedReason}", result.isSuccess)
            assertEquals(3, fixture.compatBoundary.optimisticSwapInvokeCount)
            assertEquals(listOf(false, false, true), fixture.compatBoundary.optimisticSwapRestartArgs)
        }
    }

    @Test
    fun `split full resource deploy clears device overlay after partial slice failure`() {
        withSingleOverlayPerSlice {
            val fixture = DeployFlowMockBackend.buildFixture(DeployFlowCaseId.DF_L2_011)

            val result = fixture.helper.deploy(
                fixture.deployOptions.copy(retryReason = JuggDeployerHelper.DO_NOT_RETRY),
            )

            assertFalse("deploy should fail on second slice", result.isSuccess)
            assertEquals(2, fixture.compatBoundary.optimisticSwapInvokeCount)
            assertFalse("partial overlay directory should be removed", fixture.virtualDevice.hasOverlayDir())
            assertTrue(
                fixture.virtualDevice.shellCommands.contains(
                    "run-as ${DeployFlowOverlaySeed.packageName()} rm -rf code_cache/.overlay",
                ),
            )
        }
    }

    @Test
    fun `direct overlay full resource deploy bypasses slicing`() {
        withSingleOverlayPerSlice {
            val fixture = DeployFlowMockBackend.buildFixture(DeployFlowCaseId.DF_L2_012)

            val result = fixture.helper.deploy(fixture.deployOptions)

            assertTrue("deploy failed: ${result.failedReason}", result.isSuccess)
            assertEquals(
                1,
                fixture.virtualDevice.shellScripts.count { it.contains("__JUGG_DIRECT_OVERLAY__") },
            )
            assertEquals(0, fixture.compatBoundary.optimisticSwapInvokeCount)
        }
    }

    private fun withSingleOverlayPerSlice(block: () -> Unit) {
        val oldRecordJson = JuggSettings.sliceDeployRecordJson
        JuggSettings.sliceDeployRecordJson = """[{"displayName":"virtual","firstSliceSize":1,"sliceSize":1}]"""
        try {
            block()
        } finally {
            JuggSettings.sliceDeployRecordJson = oldRecordJson
        }
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
