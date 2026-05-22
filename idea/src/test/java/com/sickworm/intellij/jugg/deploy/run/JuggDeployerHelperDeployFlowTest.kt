package com.sickworm.intellij.jugg.deploy.run

import com.sickworm.intellij.jugg.deploy.run.deployflow.DeployFlowCaseId
import com.sickworm.intellij.jugg.deploy.run.deployflow.DeployFlowFixture
import com.sickworm.intellij.jugg.deploy.run.deployflow.DeployFlowMockBackend
import com.sickworm.intellij.jugg.deploy.run.deployflow.resolveDeployFlowDeviceBackend
import com.sickworm.intellij.jugg.ide.bean.JuggSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Ignore
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.verify

/**
 * L2 **orchestration** slice for [JuggDeployerHelper]: recover/retry wiring and [JuggDeployRunTaskRequest] shape.
 *
 * Does **not** execute [com.sickworm.intellij.jugg.deploy.run.applychanges.JuggDeployTask] /
 * [com.sickworm.intellij.jugg.deploy.run.applychanges.JuggDeployer.optimisticSwap]; therefore mock cases here
 * cannot prove [com.sickworm.intellij.jugg.deploy.direct.DirectOverlaySwapTransport] or Apply Changes fallback.
 * See docs/task/jugg_deployer_helper_deploy_flow_test_plan.md §3.4.
 */
class JuggDeployerHelperDeployFlowTest {

    private val backend = resolveDeployFlowDeviceBackend()

    // region DF-L2-001 direct write incremental deploy success

    @Test
    fun `DF-L2-001 orchestration passes not deployable flag into runTask`() {
        val fixture = backend.buildFixture(DeployFlowCaseId.DF_L2_001)
        runDfL2001Orchestration(fixture)
    }

    @Test
    @Ignore("Real device backend: run with -Ddeploy.flow.device=real after DeployFlowRealDeviceBackend is implemented")
    fun `DF-L2-001 direct write incremental deploy when app not deployable real device`() {
        assumeTrue(System.getProperty("deploy.flow.device") == "real")
        val fixture = backend.buildFixture(DeployFlowCaseId.DF_L2_001)
        runDfL2001Real(fixture)
    }

    private fun runDfL2001Orchestration(fixture: DeployFlowFixture) {
        // §5.1 step 1 (L2-mock): deploy() -> recover -> runTask request
        val result = fixture.helper.deploy(fixture.deployOptions)

        assertTrue("deploy failed: ${result.failedReason}", result.isSuccess)
        assertEquals(1, fixture.executor.invocations.size)
        val incrementalRequest = fixture.executor.lastRequest!!
        assertFalse(incrementalRequest.isDeviceReadyDeploy)
        assertFalse(incrementalRequest.data.isInstall)
        assertFalse(incrementalRequest.data.isEmpty)
        assertTrue(JuggSettings.isEnableDirectOverlayDeploy)

        // §5.1 steps 2–6: L1 DirectOverlaySwapTransportTest / L2-integration JuggDeployer / L2-real — not this class
        verify(fixture.deployStateRecover!!).recoverDeployState(
            eq(fixture.device),
            eq(null),
            eq(true),
            any(),
            eq(false),
            any(),
        )
    }

    private fun runDfL2001Real(fixture: DeployFlowFixture) {
        // Step 6 (real): device overlay dir + code_cache/.overlay/id — implemented in DeployFlowRealDeviceBackend
        throw UnsupportedOperationException("DF-L2-001 real device assertions not implemented")
    }

    // endregion

    // region DF-L2-002 recover then direct write

    @Test
    fun `DF-L2-002 orchestration reinstall then incremental runTask`() {
        val fixture = backend.buildFixture(DeployFlowCaseId.DF_L2_002)
        runDfL2002Orchestration(fixture)
    }

    @Test
    @Ignore("Real device backend: run with -Ddeploy.flow.device=real after DeployFlowRealDeviceBackend is implemented")
    fun `DF-L2-002 recover reinstall then direct write when overlay mismatched real device`() {
        assumeTrue(System.getProperty("deploy.flow.device") == "real")
        val fixture = backend.buildFixture(DeployFlowCaseId.DF_L2_002)
        runDfL2002Real(fixture)
    }

    private fun runDfL2002Orchestration(fixture: DeployFlowFixture) {
        // §5.1 step 1/3 (L2-mock): recover triggers install runTask then incremental runTask
        val result = fixture.helper.deploy(fixture.deployOptions)
        assertTrue(result.isSuccess)

        // §5.1 step 2 (MISMATCHED dry): JuggDeployerHelperRecoverTest — not simulated here
        verify(fixture.deployStateRecover!!).recoverDeployState(
            eq(fixture.device),
            eq(null),
            eq(true),
            any(),
            eq(false),
            any(),
        )

        assertEquals(2, fixture.executor.invocations.size)
        assertTrue(fixture.executor.invocations[0].data.isInstall)
        verify(fixture.deployFileManager).resetAfterReinstall()

        val incrementalRequest = fixture.executor.invocations[1]
        assertFalse(incrementalRequest.data.isInstall)
        assertFalse(incrementalRequest.isDeviceReadyDeploy)

        // §5.1 step 4 direct write / step 5 device overlay: L2-integration + L2-real — not this class
    }

    private fun runDfL2002Real(fixture: DeployFlowFixture) {
        throw UnsupportedOperationException("DF-L2-002 real device assertions not implemented")
    }

    // endregion

    companion object {
        @BeforeClass
        @JvmStatic
        fun initTestEnv() {
            DeployFlowMockBackend.initSettings()
        }
    }
}
