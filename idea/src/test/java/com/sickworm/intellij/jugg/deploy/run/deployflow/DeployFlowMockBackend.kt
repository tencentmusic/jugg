package com.sickworm.intellij.jugg.deploy.run.deployflow

import com.sickworm.intellij.jugg.compiler.CompileUiHandler
import com.sickworm.intellij.jugg.deploy.DeployFileManager
import com.sickworm.intellij.jugg.deploy.DeployStateManager
import com.sickworm.intellij.jugg.deploy.IDeployHistoryManager
import com.sickworm.intellij.jugg.deploy.IDeployTargetManager
import com.sickworm.intellij.jugg.deploy.run.JuggDeployData
import com.sickworm.intellij.jugg.deploy.run.JuggDeployerHelper
import com.sickworm.intellij.jugg.deploy.run.flow.DeployStateRecover
import com.sickworm.intellij.jugg.ide.bean.JuggSettings
import com.sickworm.intellij.jugg.mock.TestGlobal
import org.mockito.Mockito
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.whenever

/**
 * Builds L2 fixtures with mocked device and collaborators.
 */
object DeployFlowMockBackend : DeployFlowDeviceBackend {

    override val mode: DeployFlowDeviceMode = DeployFlowDeviceMode.MOCK

    override fun buildFixture(caseId: DeployFlowCaseId): DeployFlowFixture {
        return when (caseId) {
            DeployFlowCaseId.DF_L2_001 -> dfL2001DirectWriteSuccess()
            DeployFlowCaseId.DF_L2_002 -> dfL2002RecoverThenDirectWrite()
        }
    }

    fun dfL2001DirectWriteSuccess(): DeployFlowFixture {
        val device = DeployFlowTestSupport.mockDevice()
        val deployData = DeployFlowTestSupport.incrementalDeployData()
        val deployTargetManager = baseDeployTargetManager(device)
        val deployFileManager = DeployFlowTestSupport.defaultDeployFileManager(deployData)

        val deployStateManager = Mockito.mock(DeployStateManager::class.java)
        Mockito.`when`(deployStateManager.updateDeployState()).thenReturn(DeployFlowTestSupport.deployReadyState)
        Mockito.`when`(deployStateManager.getDeployState(device)).thenReturn(DeployFlowTestSupport.appNotDeployableState)

        val deployHistoryManager = Mockito.mock(IDeployHistoryManager::class.java)
        Mockito.`when`(deployHistoryManager.lastDeployOverlayIds)
            .thenReturn(mapOf(RecordingDeployRunTaskExecutor.DEFAULT_PACKAGE to "matched-overlay-id"))

        val recover = Mockito.mock(DeployStateRecover::class.java)
        whenever(
            recover.recoverDeployState(
                eq(device),
                eq(null),
                eq(true),
                any(),
                eq(false),
                any(),
            ),
        ).thenReturn(true to false)

        val executor = RecordingDeployRunTaskExecutor(mapOf(RecordingDeployRunTaskExecutor.DEFAULT_PACKAGE to "new-overlay-id"))
        val helper = DeployFlowTestSupport.createHelper(
            device = device,
            deployTargetManager = deployTargetManager,
            deployFileManager = deployFileManager,
            deployStateManager = deployStateManager,
            deployHistoryManager = deployHistoryManager,
            deployStateRecover = recover,
            executor = executor,
        )

        return DeployFlowFixture(
            caseId = DeployFlowCaseId.DF_L2_001,
            device = device,
            deployOptions = DeployFlowTestSupport.defaultDeployOptions(device),
            helper = helper,
            executor = executor,
            deployFileManager = deployFileManager,
            deployStateRecover = recover,
        )
    }

    fun dfL2002RecoverThenDirectWrite(): DeployFlowFixture {
        val device = DeployFlowTestSupport.mockDevice()
        val deployData = DeployFlowTestSupport.incrementalDeployData()
        val deployTargetManager = baseDeployTargetManager(device)
        Mockito.`when`(deployTargetManager.getApks()).thenReturn(deployData.apks)

        val deployFileManager = DeployFlowTestSupport.defaultDeployFileManager(deployData)

        val deployStateManager = Mockito.mock(DeployStateManager::class.java)
        Mockito.`when`(deployStateManager.updateDeployState()).thenReturn(DeployFlowTestSupport.deployReadyState)
        Mockito.`when`(deployStateManager.getDeployState(device)).thenReturn(DeployFlowTestSupport.appNotDeployableState)

        val executor = RecordingDeployRunTaskExecutor(mapOf(RecordingDeployRunTaskExecutor.DEFAULT_PACKAGE to "overlay-after-recover"))
        val recover = Mockito.mock(DeployStateRecover::class.java)
        lateinit var helper: JuggDeployerHelper
        whenever(
            recover.recoverDeployState(
                eq(device),
                eq(null),
                eq(true),
                any(),
                eq(false),
                any(),
            ),
        ).thenAnswer {
            helper.runRecoverDeployTask(
                device = device,
                data = JuggDeployData.forInstall(deployData.apks),
                isSkipExceptOverlayCheck = false,
                compileUiHandler = CompileUiHandler.DEFAULT,
            )
            deployFileManager.resetAfterReinstall()
            true to true
        }
        helper = DeployFlowTestSupport.createHelper(
            device = device,
            deployTargetManager = deployTargetManager,
            deployFileManager = deployFileManager,
            deployStateManager = deployStateManager,
            deployStateRecover = recover,
            executor = executor,
        )

        return DeployFlowFixture(
            caseId = DeployFlowCaseId.DF_L2_002,
            device = device,
            deployOptions = DeployFlowTestSupport.defaultDeployOptions(device),
            helper = helper,
            executor = executor,
            deployFileManager = deployFileManager,
            deployStateRecover = recover,
        )
    }

    private fun baseDeployTargetManager(device: com.android.ddmlib.IDevice): IDeployTargetManager {
        val deployTargetManager = Mockito.mock(IDeployTargetManager::class.java)
        Mockito.`when`(deployTargetManager.hasDevice).thenReturn(true)
        Mockito.`when`(deployTargetManager.isAppInstalled(device)).thenReturn(true)
        Mockito.`when`(deployTargetManager.getPackageName()).thenReturn(RecordingDeployRunTaskExecutor.DEFAULT_PACKAGE)
        return deployTargetManager
    }

    fun initSettings() {
        TestGlobal.init()
        JuggSettings.isEmbeddedToApk = false
        JuggSettings.isEnableDirectOverlayDeploy = true
    }
}
