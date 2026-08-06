package com.sickworm.intellij.jugg.deploy.run.deployflow

import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Computable
import com.sickworm.intellij.jugg.createTestTaskRunnerManager
import com.sickworm.intellij.jugg.deploy.cache.JuggDeploymentCacheStore
import com.sickworm.intellij.jugg.deploy.direct.MatryoshkaFixtureWriter
import com.sickworm.intellij.jugg.deploy.run.AsDeployerCompat
import com.sickworm.intellij.jugg.deploy.run.JuggDeployerHelper
import com.sickworm.intellij.jugg.deploy.run.JuggDeploymentService
import com.sickworm.intellij.jugg.ide.bean.JuggSettings
import com.sickworm.intellij.jugg.logger.JuggLogger
import com.sickworm.intellij.jugg.mock.AssembleAndroidProjectOnce
import com.sickworm.intellij.jugg.mock.JuggMockProject
import com.sickworm.intellij.jugg.mock.TestGlobal
import com.sickworm.intellij.jugg.project.runtime.JuggPathManager
import java.io.File

/**
 * Assembles Virtual Device deploy-flow fixtures for DF-L2-001 through DF-L2-009.
 */
object DeployFlowMockBackend : DeployFlowDeviceBackend {

    private var installPath: String? = null
    private const val AS_INSTALLER_VERSION = "dced2491"
    private val servicePathManager = JuggPathManager(
        File(System.getProperty("java.io.tmpdir"), "jugg-deploy-flow-project"),
    )
    val deploymentService by lazy {
        JuggDeploymentService(
            servicePathManager,
            JuggDeploymentCacheStore(servicePathManager.deploymentCacheDbFile, createTestTaskRunnerManager(servicePathManager)),
            AsDeployerCompat,
        )
    }

    override fun buildFixture(caseId: DeployFlowCaseId): DeployFlowFixture {
        return when (caseId) {
            DeployFlowCaseId.DF_L2_001 -> buildDfL2001()
            DeployFlowCaseId.DF_L2_002 -> buildDfL2002()
            DeployFlowCaseId.DF_L2_003 -> buildDfL2003()
            DeployFlowCaseId.DF_L2_004 -> buildDfL2004()
            DeployFlowCaseId.DF_L2_005 -> buildDfL2005()
            DeployFlowCaseId.DF_L2_006 -> buildDfL2006()
            DeployFlowCaseId.DF_L2_007 -> buildDfL2007()
            DeployFlowCaseId.DF_L2_008 -> buildDfL2008()
            DeployFlowCaseId.DF_L2_009 -> buildDfL2009()
            DeployFlowCaseId.DF_L2_010 -> buildDfL2010()
            DeployFlowCaseId.DF_L2_011 -> buildDfL2011()
            DeployFlowCaseId.DF_L2_012 -> buildDfL2012()
        }
    }

    fun initSettings() {
        TestGlobal.init()
        AssembleAndroidProjectOnce.ensure()
        JuggSettings.isEmbeddedToApk = false
        JuggSettings.isEnableDirectOverlayDeploy = true
        installPath = ensureMatryoshkaInstallersRoot()
        deploymentService.deploymentCacheDbFile.parentFile?.mkdirs()
        deploymentService.preInit(com.sickworm.intellij.jugg.mock.logger)
    }

    private fun ensureMatryoshkaInstallersRoot(): String {
        val root = File(System.getProperty("java.io.tmpdir"), "jugg-deploy-flow-installers")
        val abiDir = File(root, "arm64-v8a").also { it.mkdirs() }
        val installer = File(abiDir, "installer")
        if (!installer.isFile) {
            installer.writeBytes(byteArrayOf(0x7f))
            MatryoshkaFixtureWriter.appendMatryoshka(
                installer,
                linkedMapOf(
                    "agent.so" to byteArrayOf(0x7f, 0x45, 0x4c, 0x46, 0x02),
                    "version" to AS_INSTALLER_VERSION.toByteArray(),
                ),
            )
        }
        return root.absolutePath
    }

    private fun buildDfL2001(): DeployFlowFixture {
        return buildMatchedNotDeployableFixture(
            caseId = DeployFlowCaseId.DF_L2_001,
            recoverRunHost = null,
            afterRecoverSuccess = null,
            optimisticSwapPolicy = DeployFlowAsDeployerCompatBoundary.OptimisticSwapPolicy.FORBIDDEN,
            onInstall = null,
        )
    }

    private fun buildDfL2002(): DeployFlowFixture {
        val virtualDevice = VirtualDeployDevice(DeployFlowOverlaySeed.packageName())
        val deployData = DeployFlowTestSupport.incrementalDeployData()
        val deployHistoryManager = DeployFlowTestHistoryManager()
        val historyOverlayId = DeployFlowOverlaySeed.seedHistoryAndCacheOnly(
            deploymentService = deploymentService,
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
        val compatBoundary = DeployFlowStaticBoundaryMocks.createCompat(
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
                    deploymentService = deploymentService,
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
            asDeployerCompat = compatBoundary,
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
            deployTargetManager = deployTargetManager,
            compatBoundary = compatBoundary,
            ideDeployStateHelper = ideDeployStateHelper,
            recoverRunHost = recoverRunHost,
            seededOverlayId = historyOverlayId,
        )
    }

    private fun buildDfL2003(): DeployFlowFixture {
        val recoverRunHost = DeployFlowRecoverRunHost()
        return buildMatchedNotDeployableFixture(
            caseId = DeployFlowCaseId.DF_L2_003,
            recoverRunHost = recoverRunHost,
            afterRecoverSuccess = null,
            optimisticSwapPolicy = DeployFlowAsDeployerCompatBoundary.OptimisticSwapPolicy.FORBIDDEN,
            onInstall = null,
            deployData = DeployFlowTestSupport.incrementalDeployDataWithoutAppRestart(),
        )
    }

    private fun buildDfL2004(): DeployFlowFixture {
        val fixture = buildMatchedNotDeployableFixture(
            caseId = DeployFlowCaseId.DF_L2_004,
            recoverRunHost = null,
            afterRecoverSuccess = null,
            optimisticSwapPolicy = DeployFlowAsDeployerCompatBoundary.OptimisticSwapPolicy.RECORD_SUCCESS,
            onInstall = null,
        )
        fixture.virtualDevice.failDirectOverlayPush = true
        return fixture
    }

    private fun buildDfL2005(): DeployFlowFixture {
        val fixture = buildMatchedNotDeployableFixture(
            caseId = DeployFlowCaseId.DF_L2_005,
            recoverRunHost = null,
            afterRecoverSuccess = null,
            optimisticSwapPolicy = DeployFlowAsDeployerCompatBoundary.OptimisticSwapPolicy.FORBIDDEN,
            onInstall = null,
        )
        fixture.virtualDevice.directOverlayWriteResult = VirtualDeployDevice.DirectOverlayWriteResult.APPLYING
        return fixture.copy(
            deployOptions = fixture.deployOptions.copy(retryReason = JuggDeployerHelper.DO_NOT_RETRY),
        )
    }

    private fun buildDfL2006(): DeployFlowFixture {
        val virtualDevice = VirtualDeployDevice(DeployFlowOverlaySeed.packageName())
        val deployData = DeployFlowTestSupport.incrementalDeployData()
        val deployHistoryManager = DeployFlowTestHistoryManager()
        val seededOverlayId = DeployFlowOverlaySeed.seedMatchedTriple(
            virtualDevice = virtualDevice,
            deploymentService = deploymentService,
            deployHistoryManager = deployHistoryManager,
        )
        val ideDeployStateHelper = DeployFlowIdeDeployStateHelper().apply { forIncrementalDeployable() }
        val deployTargetManager = DeployFlowTestSupport.defaultDeployTargetManager(virtualDevice)
        val deployFileManager = DeployFlowTestSupport.defaultDeployFileManager(deployData)
        val project = registerDeployFlowProject()
        val deployStateManager = DeployFlowTestSupport.createDeployStateManager(
            project = project,
            deployTargetManager = deployTargetManager,
            deployHistoryManager = deployHistoryManager,
            ideDeployStateHelper = ideDeployStateHelper,
        )
        val compatBoundary = DeployFlowStaticBoundaryMocks.createCompat(
            virtualDevice = virtualDevice,
            optimisticSwapPolicy = DeployFlowAsDeployerCompatBoundary.OptimisticSwapPolicy.RECORD_SUCCESS,
        )
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
            asDeployerCompat = compatBoundary,
        )
        return DeployFlowFixture(
            caseId = DeployFlowCaseId.DF_L2_006,
            virtualDevice = virtualDevice,
            device = device,
            deployOptions = DeployFlowTestSupport.defaultDeployOptions(device),
            helper = helper,
            deployFileManager = deployFileManager,
            deployHistoryManager = deployHistoryManager,
            deployTargetManager = deployTargetManager,
            compatBoundary = compatBoundary,
            ideDeployStateHelper = ideDeployStateHelper,
            seededOverlayId = seededOverlayId,
        )
    }

    private fun buildDfL2007(): DeployFlowFixture {
        return buildMatchedNotDeployableFixture(
            caseId = DeployFlowCaseId.DF_L2_007,
            recoverRunHost = DeployFlowRecoverRunHost(),
            afterRecoverSuccess = null,
            optimisticSwapPolicy = DeployFlowAsDeployerCompatBoundary.OptimisticSwapPolicy.RECORD_SUCCESS,
            onInstall = null,
            afterRecoverSuccessOverlayId = "swap-phase-mismatch-overlay",
        )
    }

    private fun buildDfL2008(): DeployFlowFixture {
        val virtualDevice = VirtualDeployDevice(DeployFlowOverlaySeed.packageName())
        val deployData = DeployFlowTestSupport.incrementalDeployData()
        val deployHistoryManager = DeployFlowTestHistoryManager()
        val historyOverlayId = DeployFlowOverlaySeed.seedHistoryAndCacheOnly(
            deploymentService = deploymentService,
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
        val compatBoundary = DeployFlowStaticBoundaryMocks.createCompat(
            virtualDevice = virtualDevice,
            onInstall = Runnable { virtualDevice.onInstallCompleted() },
            installerVersion = AS_INSTALLER_VERSION,
        )
        val recoverRunHost = DeployFlowRecoverRunHost(
            onAfterInstallRecoverTask = Runnable {
                DeployFlowOverlaySeed.restoreBaseInstallCacheAfterMockInstall(
                    virtualDevice = virtualDevice,
                    deploymentService = deploymentService,
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
            asDeployerCompat = compatBoundary,
            recoverRunHost = recoverRunHost,
        )
        return DeployFlowFixture(
            caseId = DeployFlowCaseId.DF_L2_008,
            virtualDevice = virtualDevice,
            device = device,
            deployOptions = DeployFlowTestSupport.defaultDeployOptions(device),
            helper = helper,
            deployFileManager = deployFileManager,
            deployHistoryManager = deployHistoryManager,
            deployTargetManager = deployTargetManager,
            compatBoundary = compatBoundary,
            ideDeployStateHelper = ideDeployStateHelper,
            recoverRunHost = recoverRunHost,
            seededOverlayId = historyOverlayId,
        )
    }

    private fun buildDfL2009(): DeployFlowFixture {
        return buildDeployableApplyChangesFixture(
            caseId = DeployFlowCaseId.DF_L2_009,
            deployData = DeployFlowTestSupport.emptyDeployData(),
        )
    }

    private fun buildDfL2010(): DeployFlowFixture {
        return buildDeployableApplyChangesFixture(
            caseId = DeployFlowCaseId.DF_L2_010,
            deployData = DeployFlowTestSupport.fullResourceDeployData(overlayCount = 3),
        )
    }

    private fun buildDfL2011(): DeployFlowFixture {
        return buildDeployableApplyChangesFixture(
            caseId = DeployFlowCaseId.DF_L2_011,
            deployData = DeployFlowTestSupport.fullResourceDeployData(overlayCount = 3),
            optimisticSwapPolicy = DeployFlowAsDeployerCompatBoundary.OptimisticSwapPolicy.FAIL_SECOND_AFTER_RECORD,
        )
    }

    private fun buildDfL2012(): DeployFlowFixture {
        return buildMatchedNotDeployableFixture(
            caseId = DeployFlowCaseId.DF_L2_012,
            recoverRunHost = null,
            afterRecoverSuccess = null,
            optimisticSwapPolicy = DeployFlowAsDeployerCompatBoundary.OptimisticSwapPolicy.FORBIDDEN,
            onInstall = null,
            deployData = DeployFlowTestSupport.fullResourceDeployData(overlayCount = 3),
        )
    }

    private fun buildDeployableApplyChangesFixture(
        caseId: DeployFlowCaseId,
        deployData: com.sickworm.intellij.jugg.deploy.run.JuggDeployData,
        optimisticSwapPolicy: DeployFlowAsDeployerCompatBoundary.OptimisticSwapPolicy =
            DeployFlowAsDeployerCompatBoundary.OptimisticSwapPolicy.RECORD_SUCCESS,
    ): DeployFlowFixture {
        val virtualDevice = VirtualDeployDevice(DeployFlowOverlaySeed.packageName())
        val deployHistoryManager = DeployFlowTestHistoryManager()
        val seededOverlayId = DeployFlowOverlaySeed.seedMatchedTriple(
            virtualDevice = virtualDevice,
            deploymentService = deploymentService,
            deployHistoryManager = deployHistoryManager,
        )
        val ideDeployStateHelper = DeployFlowIdeDeployStateHelper().apply { forIncrementalDeployable() }
        val deployTargetManager = DeployFlowTestSupport.defaultDeployTargetManager(virtualDevice)
        val deployFileManager = DeployFlowTestSupport.defaultDeployFileManager(deployData)
        val project = registerDeployFlowProject()
        val deployStateManager = DeployFlowTestSupport.createDeployStateManager(
            project = project,
            deployTargetManager = deployTargetManager,
            deployHistoryManager = deployHistoryManager,
            ideDeployStateHelper = ideDeployStateHelper,
        )
        val compatBoundary = DeployFlowStaticBoundaryMocks.createCompat(
            virtualDevice = virtualDevice,
            optimisticSwapPolicy = optimisticSwapPolicy,
        )
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
            asDeployerCompat = compatBoundary,
        )
        return DeployFlowFixture(
            caseId = caseId,
            virtualDevice = virtualDevice,
            device = device,
            deployOptions = DeployFlowTestSupport.defaultDeployOptions(device),
            helper = helper,
            deployFileManager = deployFileManager,
            deployHistoryManager = deployHistoryManager,
            deployTargetManager = deployTargetManager,
            compatBoundary = compatBoundary,
            ideDeployStateHelper = ideDeployStateHelper,
            seededOverlayId = seededOverlayId,
        )
    }

    private fun buildMatchedNotDeployableFixture(
        caseId: DeployFlowCaseId,
        recoverRunHost: DeployFlowRecoverRunHost?,
        afterRecoverSuccess: Runnable?,
        optimisticSwapPolicy: DeployFlowAsDeployerCompatBoundary.OptimisticSwapPolicy,
        onInstall: Runnable?,
        afterRecoverSuccessOverlayId: String? = null,
        deployData: com.sickworm.intellij.jugg.deploy.run.JuggDeployData =
            DeployFlowTestSupport.incrementalDeployData(),
    ): DeployFlowFixture {
        val virtualDevice = VirtualDeployDevice(DeployFlowOverlaySeed.packageName())
        val deployHistoryManager = DeployFlowTestHistoryManager()
        val seededOverlayId = DeployFlowOverlaySeed.seedMatchedTriple(
            virtualDevice = virtualDevice,
            deploymentService = deploymentService,
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
        val host = recoverRunHost ?: DeployFlowRecoverRunHost()
        val recoverHook = when {
            afterRecoverSuccessOverlayId != null -> Runnable {
                DeployFlowOverlaySeed.writeMismatchedDeviceOverlay(virtualDevice, afterRecoverSuccessOverlayId)
                afterRecoverSuccess?.run()
            }
            else -> afterRecoverSuccess
        }
        val compatBoundary = DeployFlowStaticBoundaryMocks.createCompat(
            virtualDevice = virtualDevice,
            optimisticSwapPolicy = optimisticSwapPolicy,
            onInstall = onInstall ?: Runnable { virtualDevice.onInstallCompleted() },
        )
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
            asDeployerCompat = compatBoundary,
            recoverRunHost = host,
            afterRecoverSuccess = recoverHook,
        )
        return DeployFlowFixture(
            caseId = caseId,
            virtualDevice = virtualDevice,
            device = device,
            deployOptions = DeployFlowTestSupport.defaultDeployOptions(device),
            helper = helper,
            deployFileManager = deployFileManager,
            deployHistoryManager = deployHistoryManager,
            deployTargetManager = deployTargetManager,
            compatBoundary = compatBoundary,
            ideDeployStateHelper = ideDeployStateHelper,
            recoverRunHost = recoverRunHost,
            seededOverlayId = seededOverlayId,
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
