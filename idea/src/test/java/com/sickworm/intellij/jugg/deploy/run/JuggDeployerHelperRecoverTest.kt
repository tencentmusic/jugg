package com.sickworm.intellij.jugg.deploy.run

import com.android.ddmlib.IDevice
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.sickworm.intellij.jugg.compiler.CompileUiHandler
import com.sickworm.intellij.jugg.deploy.CachedOverlayId
import com.sickworm.intellij.jugg.deploy.DeployFileManager
import com.sickworm.intellij.jugg.deploy.DeployStateManager
import com.sickworm.intellij.jugg.deploy.IDeployHistoryManager
import com.sickworm.intellij.jugg.deploy.IDeployTargetManager
import com.sickworm.intellij.jugg.deploy.IDeviceAdb
import com.sickworm.intellij.jugg.deploy.IJuggDeploymentService
import com.sickworm.intellij.jugg.deploy.run.flow.DeployStateRecover
import com.sickworm.intellij.jugg.deploy.run.flow.DryDeployResult
import com.sickworm.intellij.jugg.deploy.run.flow.IJuggDeployHelperRunHost
import com.sickworm.intellij.jugg.ide.bean.JuggSettings
import com.sickworm.intellij.jugg.mock.TestGlobal
import org.junit.Assert.assertEquals
import org.junit.BeforeClass
import org.junit.Test
import org.mockito.Mockito

class JuggDeployerHelperRecoverTest {

    @Test
    fun `mergeOverlayIds should keep existing package ids when library test apk is backfilled`() {
        val currentIds = mapOf(
            "com.example.myapplication" to "base-overlay",
            "com.example.myapplication.test" to "app-test-overlay",
        )
        val backfilledIds = mapOf(
            "com.example.library1.test" to "library-test-overlay",
        )

        val result = JuggDeployerHelper.mergeOverlayIds(currentIds, backfilledIds)

        assertEquals(
            mapOf(
                "com.example.myapplication" to "base-overlay",
                "com.example.myapplication.test" to "app-test-overlay",
                "com.example.library1.test" to "library-test-overlay",
            ),
            result,
        )
    }

    @Test
    fun `tryDryDeploy should skip app launch when app is not installed`() {
        val device = Mockito.mock(IDevice::class.java)
        val deployTargetManager = Mockito.mock(IDeployTargetManager::class.java)
        Mockito.`when`(deployTargetManager.isAppInstalled(device)).thenReturn(false)

        val recover = createDeployStateRecover(
            deployTargetManager = deployTargetManager,
        )

        val result = recover.tryDryDeploy(device, false, CompileUiHandler.DEFAULT)

        assertEquals(DryDeployResult.APP_NOT_INSTALLED, result)
        Mockito.verify(deployTargetManager, Mockito.never()).restartApp(device)
    }

    @Test
    fun `tryDryDeploy should skip app launch when direct overlay state matches`() {
        val device = Mockito.mock(IDevice::class.java)
        Mockito.`when`(device.serialNumber).thenReturn("device-1")
        val deployTargetManager = Mockito.mock(IDeployTargetManager::class.java)
        Mockito.`when`(deployTargetManager.isAppInstalled(device)).thenReturn(true)
        Mockito.`when`(deployTargetManager.getPackageName()).thenReturn("com.example.app")

        val deployHistoryManager = Mockito.mock(IDeployHistoryManager::class.java)
        Mockito.`when`(deployHistoryManager.lastDeployOverlayIds)
            .thenReturn(mapOf("com.example.app" to "overlay-id"))

        val testLogger = Mockito.mock(Logger::class.java)
        val deploymentService = Mockito.mock(IJuggDeploymentService::class.java)
        Mockito.`when`(deploymentService.loadCachedOverlayId("device-1", "com.example.app", testLogger))
            .thenReturn(CachedOverlayId(sha = "overlay-id", isBaseInstall = false))

        val adb = Mockito.mock(IDeviceAdb::class.java)
        Mockito.`when`(adb.execAdbShellScript(Mockito.anyString()))
            .thenReturn("__JUGG_OVERLAY_STATE__ ID overlay-id")

        val recover = createDeployStateRecover(
            deployTargetManager = deployTargetManager,
            deployHistoryManager = deployHistoryManager,
            deploymentService = deploymentService,
            deviceAdbFactory = { _, _ -> adb },
            logger = testLogger,
        )

        val result = recover.tryDryDeploy(device, false, CompileUiHandler.DEFAULT)

        assertEquals(DryDeployResult.SUCCESS, result)
        Mockito.verify(deployTargetManager, Mockito.never()).restartApp(device)
    }

    @Test
    fun `tryDryDeploy should fail without app launch when direct overlay state mismatches`() {
        val device = Mockito.mock(IDevice::class.java)
        Mockito.`when`(device.serialNumber).thenReturn("device-1")
        val deployTargetManager = Mockito.mock(IDeployTargetManager::class.java)
        Mockito.`when`(deployTargetManager.isAppInstalled(device)).thenReturn(true)
        Mockito.`when`(deployTargetManager.getPackageName()).thenReturn("com.example.app")

        val deployHistoryManager = Mockito.mock(IDeployHistoryManager::class.java)
        Mockito.`when`(deployHistoryManager.lastDeployOverlayIds)
            .thenReturn(mapOf("com.example.app" to "overlay-id"))

        val testLogger = Mockito.mock(Logger::class.java)
        val deploymentService = Mockito.mock(IJuggDeploymentService::class.java)
        Mockito.`when`(deploymentService.loadCachedOverlayId("device-1", "com.example.app", testLogger))
            .thenReturn(null)

        val recover = createDeployStateRecover(
            deployTargetManager = deployTargetManager,
            deployHistoryManager = deployHistoryManager,
            deploymentService = deploymentService,
            logger = testLogger,
        )

        val result = recover.tryDryDeploy(device, false, CompileUiHandler.DEFAULT)

        assertEquals(DryDeployResult.FAILED, result)
        Mockito.verify(deployTargetManager, Mockito.never()).restartApp(device)
        Mockito.verify(deploymentService).loadCachedOverlayId("device-1", "com.example.app", testLogger)
    }

    private fun createDeployStateRecover(
        deployTargetManager: IDeployTargetManager = Mockito.mock(IDeployTargetManager::class.java),
        deployFileManager: DeployFileManager = Mockito.mock(DeployFileManager::class.java),
        deployHistoryManager: IDeployHistoryManager = Mockito.mock(IDeployHistoryManager::class.java),
        deployStateManager: DeployStateManager = Mockito.mock(DeployStateManager::class.java),
        deploymentService: IJuggDeploymentService = Mockito.mock(IJuggDeploymentService::class.java),
        deviceAdbFactory: (IDevice, Logger) -> IDeviceAdb = { _, _ -> Mockito.mock(IDeviceAdb::class.java) },
        logger: Logger = Mockito.mock(Logger::class.java),
    ): DeployStateRecover {
        return DeployStateRecover(
            project = Mockito.mock(Project::class.java),
            deployTargetManager = deployTargetManager,
            deployFileManager = deployFileManager,
            deployHistoryManager = deployHistoryManager,
            deployStateManager = deployStateManager,
            deployRunHost = Mockito.mock(IJuggDeployHelperRunHost::class.java),
            deploymentService = deploymentService,
            deviceAdbFactory = deviceAdbFactory,
            logger = logger,
        )
    }

    companion object {
        @BeforeClass
        @JvmStatic
        fun initTestEnv() {
            TestGlobal.init()
            JuggSettings.isEnableDirectOverlayDeploy = true
        }
    }
}
