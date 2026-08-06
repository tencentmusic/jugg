package com.sickworm.intellij.jugg.deploy.run

import com.sickworm.intellij.jugg.deploy.api.IDevice
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
import com.sickworm.intellij.jugg.deploy.JuggDeployState
import com.sickworm.intellij.jugg.deploy.run.flow.DeployRetryHandler
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
    fun `tryDryDeploy should use legacy path when direct overlay recover is disallowed`() {
        val device = Mockito.mock(IDevice::class.java)
        Mockito.`when`(device.serialNumber).thenReturn("device-1")
        val deployTargetManager = Mockito.mock(IDeployTargetManager::class.java)
        Mockito.`when`(deployTargetManager.isAppInstalled(device)).thenReturn(true)
        Mockito.`when`(deployTargetManager.getPackageName()).thenReturn("com.example.app")
        Mockito.`when`(deployTargetManager.restartApp(device)).thenReturn(true)
        Mockito.`when`(deployTargetManager.getApks()).thenReturn(emptyList())

        val deployHistoryManager = Mockito.mock(IDeployHistoryManager::class.java)
        Mockito.`when`(deployHistoryManager.lastDeployOverlayIds)
            .thenReturn(mapOf("com.example.app" to "overlay-id"))

        val deploymentService = Mockito.mock(IJuggDeploymentService::class.java)
        Mockito.`when`(deploymentService.loadCachedOverlayId("device-1", "com.example.app", TestGlobal.getLogger()))
            .thenReturn(CachedOverlayId(sha = "overlay-id", isBaseInstall = false))

        val deployStateManager = Mockito.mock(DeployStateManager::class.java)
        Mockito.`when`(deployStateManager.updateDeployState()).thenReturn(JuggDeployState.READY)
        Mockito.`when`(deployStateManager.getDeployState(device)).thenReturn(JuggDeployState.READY)

        val recoverHost = RecordingRecoverHost()
        val recover = createDeployStateRecover(
            deployTargetManager = deployTargetManager,
            deployHistoryManager = deployHistoryManager,
            deploymentService = deploymentService,
            deployStateManager = deployStateManager,
            deployRunHost = recoverHost,
            logger = TestGlobal.getLogger(),
        )

        val result = recover.tryDryDeploy(
            device,
            false,
            CompileUiHandler.DEFAULT,
            allowDirectOverlayRecover = false,
        )

        assertEquals(DryDeployResult.SUCCESS, result)
        Mockito.verify(deployTargetManager).restartApp(device)
        Mockito.verifyNoInteractions(deploymentService)
    }

    @Test
    fun `recoverDeployState should not defer install launch when direct overlay recover is disallowed`() {
        val device = Mockito.mock(IDevice::class.java)
        Mockito.`when`(device.serialNumber).thenReturn("device-1")
        val deployTargetManager = Mockito.mock(IDeployTargetManager::class.java)
        Mockito.`when`(deployTargetManager.isAppInstalled(device)).thenReturn(true)
        Mockito.`when`(deployTargetManager.getPackageName()).thenReturn("com.example.app")
        Mockito.`when`(deployTargetManager.getApks()).thenReturn(emptyList())

        val deployHistoryManager = Mockito.mock(IDeployHistoryManager::class.java)
        Mockito.`when`(deployHistoryManager.isCleanAndReinstall).thenReturn(false)
        Mockito.`when`(deployHistoryManager.lastDeployOverlayIds)
            .thenReturn(mapOf("com.example.app" to "overlay-id"))

        val deployStateManager = Mockito.mock(DeployStateManager::class.java)
        Mockito.`when`(deployStateManager.updateDeployState()).thenReturn(JuggDeployState.READY)
        Mockito.`when`(deployStateManager.getDeployState(device)).thenReturn(JuggDeployState.READY)

        val deployFileManager = Mockito.mock(DeployFileManager::class.java)
        val deploymentService = Mockito.mock(IJuggDeploymentService::class.java)
        Mockito.`when`(deploymentService.loadCachedOverlayId("device-1", "com.example.app", TestGlobal.getLogger()))
            .thenReturn(null)

        val recoverHost = RecordingRecoverHost()
        val recover = createDeployStateRecover(
            deployTargetManager = deployTargetManager,
            deployFileManager = deployFileManager,
            deployHistoryManager = deployHistoryManager,
            deployStateManager = deployStateManager,
            deploymentService = deploymentService,
            deployRunHost = recoverHost,
            logger = TestGlobal.getLogger(),
        )

        recover.recoverDeployState(
            device = device,
            indicator = null,
            isNeedDryDeployFirst = true,
            isSkipExceptOverlayCheck = false,
            compileUiHandler = CompileUiHandler.DEFAULT,
            allowDirectOverlayRecover = false,
        )

        assertEquals(false, recoverHost.lastDeferPostDeployLaunch)
    }

    @Test
    fun `tryDryDeploy should match after reinstall when deploy history is empty but cache is base install`() {
        val device = Mockito.mock(IDevice::class.java)
        Mockito.`when`(device.serialNumber).thenReturn("device-1")
        val deployTargetManager = Mockito.mock(IDeployTargetManager::class.java)
        Mockito.`when`(deployTargetManager.isAppInstalled(device)).thenReturn(true)
        Mockito.`when`(deployTargetManager.getPackageName()).thenReturn("com.example.app")

        val deployHistoryManager = Mockito.mock(IDeployHistoryManager::class.java)
        Mockito.`when`(deployHistoryManager.lastDeployOverlayIds).thenReturn(emptyMap())

        val deploymentService = Mockito.mock(IJuggDeploymentService::class.java)
        Mockito.`when`(deploymentService.loadCachedOverlayId("device-1", "com.example.app", TestGlobal.getLogger()))
            .thenReturn(CachedOverlayId(sha = "base-overlay", isBaseInstall = true))

        val adb = Mockito.mock(IDeviceAdb::class.java)
        Mockito.`when`(adb.execAdbShellScript(Mockito.anyString()))
            .thenReturn("__JUGG_OVERLAY_STATE__ NO_DIR")

        val recover = createDeployStateRecover(
            deployTargetManager = deployTargetManager,
            deployHistoryManager = deployHistoryManager,
            deploymentService = deploymentService,
            deviceAdbFactory = { _, _ -> adb },
            logger = TestGlobal.getLogger(),
        )

        val result = recover.tryDryDeploy(device, isSkipExceptOverlayCheck = true, compileUiHandler = CompileUiHandler.DEFAULT)

        assertEquals(DryDeployResult.SUCCESS, result)
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

        val deploymentService = Mockito.mock(IJuggDeploymentService::class.java)
        Mockito.`when`(deploymentService.loadCachedOverlayId("device-1", "com.example.app", TestGlobal.getLogger()))
            .thenReturn(CachedOverlayId(sha = "overlay-id", isBaseInstall = false))

        val adb = Mockito.mock(IDeviceAdb::class.java)
        Mockito.`when`(adb.execAdbShellScript(Mockito.anyString()))
            .thenReturn("__JUGG_OVERLAY_STATE__ ID overlay-id")

        val recover = createDeployStateRecover(
            deployTargetManager = deployTargetManager,
            deployHistoryManager = deployHistoryManager,
            deploymentService = deploymentService,
            deviceAdbFactory = { _, _ -> adb },
            logger = TestGlobal.getLogger(),
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

        val deploymentService = Mockito.mock(IJuggDeploymentService::class.java)
        Mockito.`when`(deploymentService.loadCachedOverlayId("device-1", "com.example.app", TestGlobal.getLogger()))
            .thenReturn(null)

        val recover = createDeployStateRecover(
            deployTargetManager = deployTargetManager,
            deployHistoryManager = deployHistoryManager,
            deploymentService = deploymentService,
            logger = TestGlobal.getLogger(),
        )

        val result = recover.tryDryDeploy(device, false, CompileUiHandler.DEFAULT)

        assertEquals(DryDeployResult.FAILED, result)
        Mockito.verify(deployTargetManager, Mockito.never()).restartApp(device)
        Mockito.verify(deploymentService).loadCachedOverlayId("device-1", "com.example.app", TestGlobal.getLogger())
    }

    @Test
    fun `recoverDeployState should skip reinstall when dry deploy succeeds`() {
        val device = Mockito.mock(IDevice::class.java)
        Mockito.`when`(device.serialNumber).thenReturn("device-1")
        val deployTargetManager = Mockito.mock(IDeployTargetManager::class.java)
        Mockito.`when`(deployTargetManager.isAppInstalled(device)).thenReturn(true)
        Mockito.`when`(deployTargetManager.getPackageName()).thenReturn("com.example.app")

        val deployHistoryManager = Mockito.mock(IDeployHistoryManager::class.java)
        Mockito.`when`(deployHistoryManager.isCleanAndReinstall).thenReturn(false)
        Mockito.`when`(deployHistoryManager.lastDeployOverlayIds)
            .thenReturn(mapOf("com.example.app" to "overlay-id"))

        val deploymentService = Mockito.mock(IJuggDeploymentService::class.java)
        Mockito.`when`(deploymentService.loadCachedOverlayId("device-1", "com.example.app", TestGlobal.getLogger()))
            .thenReturn(CachedOverlayId(sha = "overlay-id", isBaseInstall = false))

        val adb = Mockito.mock(IDeviceAdb::class.java)
        Mockito.`when`(adb.execAdbShellScript(Mockito.anyString()))
            .thenReturn("__JUGG_OVERLAY_STATE__ ID overlay-id")

        val recoverHost = RecordingRecoverHost()
        val recover = createDeployStateRecover(
            deployTargetManager = deployTargetManager,
            deployHistoryManager = deployHistoryManager,
            deploymentService = deploymentService,
            deviceAdbFactory = { _, _ -> adb },
            logger = TestGlobal.getLogger(),
            deployRunHost = recoverHost,
        )

        val result = recover.recoverDeployState(
            device = device,
            indicator = null,
            isNeedDryDeployFirst = true,
            isSkipExceptOverlayCheck = false,
            compileUiHandler = CompileUiHandler.DEFAULT,
        )

        assertEquals(true to false, result)
        assertEquals(0, recoverHost.recoverInvokeCount)
    }

    @Test
    fun `recoverDeployState should defer install recover launch when direct overlay enabled`() {
        val device = Mockito.mock(IDevice::class.java)
        Mockito.`when`(device.serialNumber).thenReturn("device-1")
        val deployTargetManager = Mockito.mock(IDeployTargetManager::class.java)
        Mockito.`when`(deployTargetManager.isAppInstalled(device)).thenReturn(true)
        Mockito.`when`(deployTargetManager.getPackageName()).thenReturn("com.example.app")
        Mockito.`when`(deployTargetManager.getApks()).thenReturn(emptyList())

        val deployHistoryManager = Mockito.mock(IDeployHistoryManager::class.java)
        Mockito.`when`(deployHistoryManager.isCleanAndReinstall).thenReturn(false)
        Mockito.`when`(deployHistoryManager.lastDeployOverlayIds)
            .thenReturn(mapOf("com.example.app" to "overlay-id"))

        val deployStateManager = Mockito.mock(DeployStateManager::class.java)
        Mockito.`when`(deployStateManager.updateDeployState()).thenReturn(JuggDeployState.READY)
        Mockito.`when`(deployStateManager.getDeployState(device)).thenReturn(JuggDeployState.READY)

        val deployFileManager = Mockito.mock(DeployFileManager::class.java)
        val deploymentService = Mockito.mock(IJuggDeploymentService::class.java)
        Mockito.`when`(deploymentService.loadCachedOverlayId("device-1", "com.example.app", TestGlobal.getLogger()))
            .thenReturn(null)

        val recoverHost = RecordingRecoverHost()
        val recover = createDeployStateRecover(
            deployTargetManager = deployTargetManager,
            deployFileManager = deployFileManager,
            deployHistoryManager = deployHistoryManager,
            deployStateManager = deployStateManager,
            deploymentService = deploymentService,
            logger = TestGlobal.getLogger(),
            deployRunHost = recoverHost,
        )

        recover.recoverDeployState(
            device = device,
            indicator = null,
            isNeedDryDeployFirst = true,
            isSkipExceptOverlayCheck = false,
            compileUiHandler = CompileUiHandler.DEFAULT,
        )

        assertEquals(true, recoverHost.lastDeferPostDeployLaunch)
    }

    @Test
    fun `recoverDeployState should skip waiting when direct overlay defers launch without dry deploy first`() {
        val device = Mockito.mock(IDevice::class.java)
        val deployTargetManager = Mockito.mock(IDeployTargetManager::class.java)
        Mockito.`when`(deployTargetManager.getApks()).thenReturn(emptyList())

        val deployHistoryManager = Mockito.mock(IDeployHistoryManager::class.java)
        Mockito.`when`(deployHistoryManager.isCleanAndReinstall).thenReturn(false)

        val notReady = JuggDeployState(
            JuggDeployState.State.READY_INCREMENTAL_COMPILE,
            "app not running or not debuggable",
            com.sickworm.intellij.jugg.deploy.run.IdeDeployState(
                com.sickworm.intellij.jugg.deploy.run.IdeDeployState.State.NO_DEPLOYABLE_APP,
                "app not running or not debuggable",
            ),
        )
        val deployStateManager = Mockito.mock(DeployStateManager::class.java)
        Mockito.`when`(deployStateManager.updateDeployState()).thenReturn(notReady)
        Mockito.`when`(deployStateManager.getDeployState(device)).thenReturn(notReady)

        val deployFileManager = Mockito.mock(DeployFileManager::class.java)
        val recoverHost = RecordingRecoverHost()
        val recover = createDeployStateRecover(
            deployTargetManager = deployTargetManager,
            deployFileManager = deployFileManager,
            deployHistoryManager = deployHistoryManager,
            deployStateManager = deployStateManager,
            deployRunHost = recoverHost,
        )

        val result = recover.recoverDeployState(
            device = device,
            indicator = null,
            isNeedDryDeployFirst = false,
            isSkipExceptOverlayCheck = false,
            isInstallUpdateApk = true,
            compileUiHandler = CompileUiHandler.DEFAULT,
        )

        assertEquals(true to true, result)
        Mockito.verify(deployStateManager, Mockito.never()).getDeployState(device)
    }

    @Test
    fun `recoverDeployState should reinstall and reset after dry deploy fails`() {
        val device = Mockito.mock(IDevice::class.java)
        Mockito.`when`(device.serialNumber).thenReturn("device-1")
        val deployTargetManager = Mockito.mock(IDeployTargetManager::class.java)
        Mockito.`when`(deployTargetManager.isAppInstalled(device)).thenReturn(true)
        Mockito.`when`(deployTargetManager.getPackageName()).thenReturn("com.example.app")
        Mockito.`when`(deployTargetManager.getApks()).thenReturn(emptyList())

        val deployHistoryManager = Mockito.mock(IDeployHistoryManager::class.java)
        Mockito.`when`(deployHistoryManager.isCleanAndReinstall).thenReturn(false)
        Mockito.`when`(deployHistoryManager.lastDeployOverlayIds)
            .thenReturn(mapOf("com.example.app" to "overlay-id"))

        val deployStateManager = Mockito.mock(DeployStateManager::class.java)
        Mockito.`when`(deployStateManager.updateDeployState()).thenReturn(JuggDeployState.READY)
        Mockito.`when`(deployStateManager.getDeployState(device)).thenReturn(JuggDeployState.READY)

        val deployFileManager = Mockito.mock(DeployFileManager::class.java)
        val deploymentService = Mockito.mock(IJuggDeploymentService::class.java)
        Mockito.`when`(deploymentService.loadCachedOverlayId("device-1", "com.example.app", TestGlobal.getLogger()))
            .thenReturn(null)

        val recoverHost = RecordingRecoverHost()
        val recover = createDeployStateRecover(
            deployTargetManager = deployTargetManager,
            deployFileManager = deployFileManager,
            deployHistoryManager = deployHistoryManager,
            deployStateManager = deployStateManager,
            deploymentService = deploymentService,
            logger = TestGlobal.getLogger(),
            deployRunHost = recoverHost,
        )

        val result = recover.recoverDeployState(
            device = device,
            indicator = null,
            isNeedDryDeployFirst = true,
            isSkipExceptOverlayCheck = false,
            compileUiHandler = CompileUiHandler.DEFAULT,
        )

        assertEquals(true to true, result)
        assertEquals(1, recoverHost.recoverInvokeCount)
        Mockito.verify(deployFileManager).resetAfterReinstall()
    }

    @Test
    fun `recoverDeployState should clear stale overlay files after reinstall`() {
        val device = Mockito.mock(IDevice::class.java)
        Mockito.`when`(device.serialNumber).thenReturn("device-1")
        val deployTargetManager = Mockito.mock(IDeployTargetManager::class.java)
        Mockito.`when`(deployTargetManager.isAppInstalled(device)).thenReturn(true)
        Mockito.`when`(deployTargetManager.getPackageName()).thenReturn("com.example.app")
        Mockito.`when`(deployTargetManager.getApks()).thenReturn(emptyList())

        val deployHistoryManager = Mockito.mock(IDeployHistoryManager::class.java)
        Mockito.`when`(deployHistoryManager.isCleanAndReinstall).thenReturn(false)
        Mockito.`when`(deployHistoryManager.lastDeployOverlayIds)
            .thenReturn(mapOf("com.example.app" to "overlay-id"))

        val deployStateManager = Mockito.mock(DeployStateManager::class.java)
        Mockito.`when`(deployStateManager.updateDeployState()).thenReturn(JuggDeployState.READY)
        Mockito.`when`(deployStateManager.getDeployState(device)).thenReturn(JuggDeployState.READY)

        val deployFileManager = Mockito.mock(DeployFileManager::class.java)
        val deploymentService = Mockito.mock(IJuggDeploymentService::class.java)
        Mockito.`when`(deploymentService.loadCachedOverlayId("device-1", "com.example.app", TestGlobal.getLogger()))
            .thenReturn(null)

        val adb = Mockito.mock(IDeviceAdb::class.java)
        val recover = createDeployStateRecover(
            deployTargetManager = deployTargetManager,
            deployFileManager = deployFileManager,
            deployHistoryManager = deployHistoryManager,
            deployStateManager = deployStateManager,
            deploymentService = deploymentService,
            deviceAdbFactory = { _, _ -> adb },
            logger = TestGlobal.getLogger(),
            deployRunHost = RecordingRecoverHost(),
        )

        recover.recoverDeployState(
            device = device,
            indicator = null,
            isNeedDryDeployFirst = true,
            isSkipExceptOverlayCheck = false,
            compileUiHandler = CompileUiHandler.DEFAULT,
        )

        Mockito.verify(deployFileManager).resetAfterReinstall()
    }

    @Test
    fun `tryDryDeploy should match using cache when except overlay check is skipped after reinstall`() {
        val device = Mockito.mock(IDevice::class.java)
        Mockito.`when`(device.serialNumber).thenReturn("device-1")
        val deployTargetManager = Mockito.mock(IDeployTargetManager::class.java)
        Mockito.`when`(deployTargetManager.isAppInstalled(device)).thenReturn(true)
        Mockito.`when`(deployTargetManager.getPackageName()).thenReturn("com.example.app")

        val deployHistoryManager = Mockito.mock(IDeployHistoryManager::class.java)
        Mockito.`when`(deployHistoryManager.lastDeployOverlayIds)
            .thenReturn(mapOf("com.example.app" to "stale-history-id"))

        val deploymentService = Mockito.mock(IJuggDeploymentService::class.java)
        Mockito.`when`(deploymentService.loadCachedOverlayId("device-1", "com.example.app", TestGlobal.getLogger()))
            .thenReturn(CachedOverlayId(sha = "fresh-cache-id", isBaseInstall = true))

        val adb = Mockito.mock(IDeviceAdb::class.java)
        Mockito.`when`(adb.execAdbShellScript(Mockito.anyString()))
            .thenReturn("__JUGG_OVERLAY_STATE__ NO_DIR")

        val recover = createDeployStateRecover(
            deployTargetManager = deployTargetManager,
            deployHistoryManager = deployHistoryManager,
            deploymentService = deploymentService,
            deviceAdbFactory = { _, _ -> adb },
            logger = TestGlobal.getLogger(),
        )

        val result = recover.tryDryDeploy(
            device,
            isSkipExceptOverlayCheck = true,
            compileUiHandler = CompileUiHandler.DEFAULT,
        )

        assertEquals(DryDeployResult.SUCCESS, result)
        Mockito.verify(deployTargetManager, Mockito.never()).restartApp(device)
    }

    @Test
    fun `recoverDeployState should reinstall without dry deploy when apk update requires install`() {
        val device = Mockito.mock(IDevice::class.java)
        val deployTargetManager = Mockito.mock(IDeployTargetManager::class.java)
        Mockito.`when`(deployTargetManager.getApks()).thenReturn(emptyList())

        val deployHistoryManager = Mockito.mock(IDeployHistoryManager::class.java)
        Mockito.`when`(deployHistoryManager.isCleanAndReinstall).thenReturn(false)

        val deployStateManager = Mockito.mock(DeployStateManager::class.java)
        Mockito.`when`(deployStateManager.updateDeployState()).thenReturn(JuggDeployState.READY)
        Mockito.`when`(deployStateManager.getDeployState(device)).thenReturn(JuggDeployState.READY)

        val deployFileManager = Mockito.mock(DeployFileManager::class.java)
        val recoverHost = RecordingRecoverHost()
        val recover = createDeployStateRecover(
            deployTargetManager = deployTargetManager,
            deployFileManager = deployFileManager,
            deployHistoryManager = deployHistoryManager,
            deployStateManager = deployStateManager,
            deployRunHost = recoverHost,
        )

        val result = recover.recoverDeployState(
            device = device,
            indicator = null,
            isNeedDryDeployFirst = false,
            isSkipExceptOverlayCheck = false,
            isInstallUpdateApk = true,
            compileUiHandler = CompileUiHandler.DEFAULT,
        )

        assertEquals(true to true, result)
        assertEquals(1, recoverHost.recoverInvokeCount)
        Mockito.verify(deployTargetManager, Mockito.never()).isAppInstalled(device)
    }

    @Test
    fun `tryDryDeploy should treat compat redeploy message as success without restarting app`() {
        val previousDirectOverlay = JuggSettings.isEnableDirectOverlayDeploy
        JuggSettings.isEnableDirectOverlayDeploy = false
        try {
            val device = Mockito.mock(IDevice::class.java)
            val deployTargetManager = Mockito.mock(IDeployTargetManager::class.java)
            Mockito.`when`(deployTargetManager.isAppInstalled(device)).thenReturn(true)
            Mockito.`when`(deployTargetManager.restartApp(device)).thenReturn(true)
            Mockito.`when`(deployTargetManager.getApks()).thenReturn(emptyList())

            val deployStateManager = Mockito.mock(DeployStateManager::class.java)
            Mockito.`when`(deployStateManager.updateDeployState()).thenReturn(JuggDeployState.READY)
            Mockito.`when`(deployStateManager.getDeployState(device)).thenReturn(JuggDeployState.READY)

            val recoverHost = RecordingRecoverHost(
                throwOnRecover = RuntimeException(DeployRetryHandler.REDEPLOY_WITH_COMPAT_MESSAGE),
            )
            val recover = createDeployStateRecover(
                deployTargetManager = deployTargetManager,
                deployStateManager = deployStateManager,
                deployRunHost = recoverHost,
            )

            val result = recover.tryDryDeploy(device, false, CompileUiHandler.DEFAULT)

            assertEquals(DryDeployResult.SUCCESS, result)
            assertEquals(1, recoverHost.recoverInvokeCount)
            Mockito.verify(deployTargetManager).restartApp(device)
        } finally {
            JuggSettings.isEnableDirectOverlayDeploy = previousDirectOverlay
        }
    }

    private fun createDeployStateRecover(
        deployTargetManager: IDeployTargetManager = Mockito.mock(IDeployTargetManager::class.java),
        deployFileManager: DeployFileManager = Mockito.mock(DeployFileManager::class.java),
        deployHistoryManager: IDeployHistoryManager = Mockito.mock(IDeployHistoryManager::class.java),
        deployStateManager: DeployStateManager = Mockito.mock(DeployStateManager::class.java),
        deploymentService: IJuggDeploymentService = Mockito.mock(IJuggDeploymentService::class.java),
        deviceAdbFactory: (IDevice, Logger) -> IDeviceAdb = { _, _ -> Mockito.mock(IDeviceAdb::class.java) },
        logger: Logger = TestGlobal.getLogger(),
        deployRunHost: IJuggDeployHelperRunHost = Mockito.mock(IJuggDeployHelperRunHost::class.java),
    ): DeployStateRecover {
        return DeployStateRecover(
            project = Mockito.mock(Project::class.java),
            deployTargetManager = deployTargetManager,
            deployFileManager = deployFileManager,
            deployHistoryManager = deployHistoryManager,
            deployStateManager = deployStateManager,
            deployRunHost = deployRunHost,
            deploymentService = deploymentService,
            deviceAdbFactory = deviceAdbFactory,
            logger = logger,
        )
    }

    private class RecordingRecoverHost(
        private val throwOnRecover: Exception? = null,
    ) : IJuggDeployHelperRunHost {
        var recoverInvokeCount = 0

        var lastDeferPostDeployLaunch: Boolean? = null
        var lastAllowDirectOverlayDeploy: Boolean? = null

        override fun runRecoverDeployTask(
            device: IDevice,
            data: JuggDeployData,
            isSkipExceptOverlayCheck: Boolean,
            compileUiHandler: CompileUiHandler,
            deferPostDeployLaunch: Boolean,
            isAllowDirectOverlayDeploy: Boolean,
        ) {
            recoverInvokeCount++
            if (data.isInstall) {
                lastDeferPostDeployLaunch = deferPostDeployLaunch
                lastAllowDirectOverlayDeploy = isAllowDirectOverlayDeploy
            }
            throwOnRecover?.let { throw it }
        }

        override fun redeploy(deployOptions: DeployOptions): DeployTaskResult =
            DeployTaskResult(isSuccess = true, costTime = 0)

        override fun tryRetryInstall(
            deployOptions: DeployOptions,
            deployData: JuggDeployData,
            reason: String,
        ): DeployTaskResult? = null

        override fun detectJvmtiCompatIssue(device: IDevice, deployData: JuggDeployData): Boolean = false
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
