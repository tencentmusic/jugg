package com.sickworm.intellij.jugg.deploy.run.deployflow

import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Computable
import com.sickworm.intellij.jugg.deploy.run.JuggDeploymentService
import com.sickworm.intellij.jugg.ide.bean.JuggSettings
import com.sickworm.intellij.jugg.logger.JuggLogger
import com.sickworm.intellij.jugg.mock.AssembleAndroidProjectOnce
import com.sickworm.intellij.jugg.mock.JuggMockProject
import com.sickworm.intellij.jugg.mock.TestGlobal
import java.io.File

/**
 * Assembles Virtual Device deploy-flow fixtures for DF-L2-001/002.
 */
object DeployFlowMockBackend : DeployFlowDeviceBackend {

    private var installPath: String? = null

    override fun buildFixture(caseId: DeployFlowCaseId): DeployFlowFixture {
        return when (caseId) {
            DeployFlowCaseId.DF_L2_001 -> buildDfL2001()
            DeployFlowCaseId.DF_L2_002 -> buildDfL2002()
        }
    }

    fun initSettings() {
        TestGlobal.init()
        AssembleAndroidProjectOnce.ensure()
        JuggSettings.isEmbeddedToApk = false
        JuggSettings.isEnableDirectOverlayDeploy = true
        installPath = System.getProperty("java.io.tmpdir")
        JuggDeploymentService.deploymentCacheDbFile.parentFile?.mkdirs()
        JuggDeploymentService.preInit(com.sickworm.intellij.jugg.mock.logger)
    }

    private fun buildDfL2001(): DeployFlowFixture {
        val virtualDevice = VirtualDeployDevice(DeployFlowOverlaySeed.packageName())
        val deployData = DeployFlowTestSupport.incrementalDeployData()
        val deployHistoryManager = DeployFlowTestHistoryManager()
        val seededOverlayId = DeployFlowOverlaySeed.seedMatchedTriple(
            virtualDevice = virtualDevice,
            deploymentService = JuggDeploymentService,
            deployHistoryManager = deployHistoryManager,
        )
        val ideDeployStateHelper = DeployFlowIdeDeployStateHelper().apply { forIncrementalNotDeployable() }
        val deployTargetManager = DeployFlowTestSupport.defaultDeployTargetManager(virtualDevice)
        val deployFileManager = DeployFlowTestSupport.defaultDeployFileManager(deployData)
        val project = registerDeployFlowProject()
        val deployStateManager = DeployFlowTestSupport.createDeployStateManager(
            project = project,
            deployTargetManager = deployTargetManager,
            deployHistoryManager = deployHistoryManager,
            ideDeployStateHelper = ideDeployStateHelper,
        )
        val asDeployerCompat = DeployFlowStaticBoundaryMocks.createCompat(virtualDevice)
        val device = virtualDevice.asDdmlibDevice()
        val helper = DeployFlowTestSupport.createHelper(
            project = project,
            virtualDevice = virtualDevice,
            deployTargetManager = deployTargetManager,
            deployFileManager = deployFileManager,
            deployStateManager = deployStateManager,
            deployHistoryManager = deployHistoryManager,
            ideDeployStateHelper = ideDeployStateHelper,
            installPathProvider = installPathProvider(),
            asDeployerCompat = asDeployerCompat,
        )
        return DeployFlowFixture(
            caseId = DeployFlowCaseId.DF_L2_001,
            virtualDevice = virtualDevice,
            device = device,
            deployOptions = DeployFlowTestSupport.defaultDeployOptions(device),
            helper = helper,
            deployFileManager = deployFileManager,
            deployHistoryManager = deployHistoryManager,
            ideDeployStateHelper = ideDeployStateHelper,
            seededOverlayId = seededOverlayId,
        )
    }

    private fun buildDfL2002(): DeployFlowFixture {
        val virtualDevice = VirtualDeployDevice(DeployFlowOverlaySeed.packageName())
        val deployData = DeployFlowTestSupport.incrementalDeployData()
        val deployHistoryManager = DeployFlowTestHistoryManager()
        val historyOverlayId = DeployFlowOverlaySeed.seedHistoryAndCacheOnly(
            deploymentService = JuggDeploymentService,
            deployHistoryManager = deployHistoryManager,
            virtualDevice = virtualDevice,
        )
        DeployFlowOverlaySeed.writeMismatchedDeviceOverlay(virtualDevice, "mismatched-device-overlay")
        val ideDeployStateHelper = DeployFlowIdeDeployStateHelper().apply { forIncrementalNotDeployable() }
        val deployTargetManager = DeployFlowTestSupport.defaultDeployTargetManager(virtualDevice)
        val deployFileManager = DeployFlowTestSupport.defaultDeployFileManager(deployData)
        val project = registerDeployFlowProject()
        val deployStateManager = DeployFlowTestSupport.createDeployStateManager(
            project = project,
            deployTargetManager = deployTargetManager,
            deployHistoryManager = deployHistoryManager,
            ideDeployStateHelper = ideDeployStateHelper,
        )
        val device = virtualDevice.asDdmlibDevice()
        val asDeployerCompat = DeployFlowStaticBoundaryMocks.createCompat(
            virtualDevice = virtualDevice,
            onInstall = Runnable {
                virtualDevice.onInstallCompleted()
                DeployFlowOverlaySeed.realignDeviceAfterInstall(virtualDevice, historyOverlayId)
            },
        )
        val recoverRunHost = DeployFlowRecoverRunHost(
            onAfterInstallRecoverTask = Runnable {
                DeployFlowOverlaySeed.restoreDeploymentCacheAfterMockInstall(
                    virtualDevice = virtualDevice,
                    deploymentService = JuggDeploymentService,
                    deployHistoryManager = deployHistoryManager,
                )
                ideDeployStateHelper.signalInstallCompletedForRecoverWait()
            },
        )
        val helper = DeployFlowTestSupport.createHelper(
            project = project,
            virtualDevice = virtualDevice,
            deployTargetManager = deployTargetManager,
            deployFileManager = deployFileManager,
            deployStateManager = deployStateManager,
            deployHistoryManager = deployHistoryManager,
            ideDeployStateHelper = ideDeployStateHelper,
            installPathProvider = installPathProvider(),
            asDeployerCompat = asDeployerCompat,
            recoverRunHost = recoverRunHost,
        )
        return DeployFlowFixture(
            caseId = DeployFlowCaseId.DF_L2_002,
            virtualDevice = virtualDevice,
            device = device,
            deployOptions = DeployFlowTestSupport.defaultDeployOptions(device),
            helper = helper,
            deployFileManager = deployFileManager,
            deployHistoryManager = deployHistoryManager,
            ideDeployStateHelper = ideDeployStateHelper,
            seededOverlayId = historyOverlayId,
        )
    }

    private fun installPathProvider(): Computable<String> {
        val path = installPath ?: File(System.getProperty("java.io.tmpdir")).absolutePath
        return Computable { path }
    }

    private fun registerDeployFlowProject(): Project {
        val project = JuggMockProject(TestGlobal.projectInfo.projectRoot)
        JuggLogger.register(project, File(System.getProperty("java.io.tmpdir"), "jugg-deploy-flow-log"))
        return project
    }
}
