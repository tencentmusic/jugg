package com.sickworm.intellij.jugg.deploy.run

import com.android.ddmlib.IDevice
import com.android.sdklib.AndroidVersion
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.sickworm.intellij.jugg.compiler.ICompileContext
import com.sickworm.intellij.jugg.apk.ApkFileUnit
import com.sickworm.intellij.jugg.apk.ApkInfo
import com.sickworm.intellij.jugg.compiler.CompileOutput
import com.sickworm.intellij.jugg.deploy.DeployFileManager
import com.sickworm.intellij.jugg.deploy.DeployStateManager
import com.sickworm.intellij.jugg.deploy.IDeployHistoryManager
import com.sickworm.intellij.jugg.deploy.IDeployTargetManager
import com.sickworm.intellij.jugg.deploy.data.ParsedDex
import com.sickworm.intellij.jugg.deploy.IJuggRunningTaskStatusManager
import com.sickworm.intellij.jugg.deploy.JuggDeployState
import com.sickworm.intellij.jugg.deploy.JuggRunningTaskStatusManager
import com.sickworm.intellij.jugg.deploy.instrument.AndroidTestRunSpec
import com.sickworm.intellij.jugg.deploy.run.LaunchResult
import com.sickworm.intellij.jugg.deploy.run.flow.DeployStateRecover
import com.sickworm.intellij.jugg.deploy.run.flow.DeployRetryHandler
import com.sickworm.intellij.jugg.deploy.run.flow.IJuggDeployRunTaskExecutor
import com.sickworm.intellij.jugg.deploy.run.instrument.LibraryTestApkBackfillHelper
import com.sickworm.intellij.jugg.ide.bean.IProcessHandler
import com.sickworm.intellij.jugg.ide.bean.JuggSettings
import com.sickworm.intellij.jugg.mock.TestGlobal
import com.sickworm.intellij.jugg.project.CompileContextManager
import com.sickworm.intellij.jugg.project.JuggException
import com.sickworm.intellij.jugg.project.TaskRunnerManager
import com.sickworm.intellij.jugg.project.dependency.IDependencyChangeManager
import com.sickworm.intellij.jugg.server.JuggServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.mockito.Mockito
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.whenever
import java.io.File

/**
 * Orchestration tests for [JuggDeployerHelper.deploy] early-exit branches.
 */
class JuggDeployerHelperDeployTest {

    @Test
    fun `deploy should fail immediately when process handler is canceled`() {
        val device = Mockito.mock(IDevice::class.java)
        val processHandler = Mockito.mock(IProcessHandler::class.java)
        Mockito.`when`(processHandler.isCanceled).thenReturn(true)

        val deployStateManager = Mockito.mock(DeployStateManager::class.java)
        Mockito.`when`(deployStateManager.updateDeployState()).thenReturn(JuggDeployState.READY)

        val helper = createHelper(deployStateManager = deployStateManager)
        val result = helper.deploy(
            DeployOptions(
                device = device,
                isLastDevice = true,
                processHandler = processHandler,
            ),
        )

        assertFalse(result.isSuccess)
        assertEquals("deploy canceled", result.failedReason)
    }

    @Test
    fun `deploy should fail when incremental deploy has no connected device`() {
        val device = Mockito.mock(IDevice::class.java)
        val deployTargetManager = Mockito.mock(IDeployTargetManager::class.java)
        Mockito.`when`(deployTargetManager.hasDevice).thenReturn(false)

        val deployStateManager = Mockito.mock(DeployStateManager::class.java)
        Mockito.`when`(deployStateManager.updateDeployState()).thenReturn(JuggDeployState.READY)

        val helper = createHelper(
            deployTargetManager = deployTargetManager,
            deployStateManager = deployStateManager,
        )
        val result = helper.deploy(
            DeployOptions(device = device, isLastDevice = true, isInstall = false),
        )

        assertFalse(result.isSuccess)
        assertEquals("no device connected", result.failedReason)
    }

    @Test
    fun `deploy should fail when warm up requested but device is not deploy ready`() {
        val device = Mockito.mock(IDevice::class.java)
        val deployTargetManager = Mockito.mock(IDeployTargetManager::class.java)
        Mockito.`when`(deployTargetManager.hasDevice).thenReturn(true)

        val notReady = JuggDeployState(
            JuggDeployState.State.READY_INCREMENTAL_COMPILE,
            "not ready",
            IdeDeployState.ok,
        )
        val deployStateManager = Mockito.mock(DeployStateManager::class.java)
        Mockito.`when`(deployStateManager.updateDeployState()).thenReturn(notReady)
        Mockito.`when`(deployStateManager.getDeployState(device)).thenReturn(notReady)

        val helper = createHelper(
            deployTargetManager = deployTargetManager,
            deployStateManager = deployStateManager,
        )
        val result = helper.deploy(
            DeployOptions(device = device, isLastDevice = true, isWarmUp = true),
        )

        assertFalse(result.isSuccess)
        assertEquals("device not ready to warm up", result.failedReason)
    }

    @Test
    fun `deploy should recover when device is ready deploy but project switched this run`() {
        val device = Mockito.mock(IDevice::class.java)
        val androidVersion = Mockito.mock(AndroidVersion::class.java)
        Mockito.`when`(androidVersion.apiLevel).thenReturn(30)
        Mockito.`when`(device.version).thenReturn(androidVersion)
        val deployTargetManager = Mockito.mock(IDeployTargetManager::class.java)
        Mockito.`when`(deployTargetManager.hasDevice).thenReturn(true)
        Mockito.`when`(deployTargetManager.getApks()).thenReturn(listOf(apkInfo("/tmp/jugg-deploy-test/app.apk")))

        val deployStateManager = Mockito.mock(DeployStateManager::class.java)
        Mockito.`when`(deployStateManager.updateDeployState()).thenReturn(JuggDeployState.READY)
        Mockito.`when`(deployStateManager.getDeployState(device)).thenReturn(JuggDeployState.READY)

        val deployFileManager = Mockito.mock(DeployFileManager::class.java)
        val emptyDeployData = JuggDeployData(
            apks = listOf(apkInfo("/tmp/jugg-deploy-test/app.apk")),
            newClasses = emptyList(),
            hotFixModifiedClasses = emptyList(),
            hotReloadModifiedClasses = emptyList(),
            effectedClassNodes = emptyList(),
            overlays = emptyList(),
            parsedDex = ParsedDex.EMPTY,
            isFullRes = false,
            isWarmUp = false,
        )
        Mockito.`when`(deployFileManager.getDeployData(Mockito.anyBoolean(), Mockito.anyBoolean())).thenReturn(emptyDeployData)
        Mockito.`when`(deployFileManager.getStagingFiles()).thenReturn(emptyList())

        val dependencyChangeManager = Mockito.mock(IDependencyChangeManager::class.java)
        Mockito.`when`(dependencyChangeManager.getRemovedLibraryFiles()).thenReturn(emptyList())

        val statusManager = JuggRunningTaskStatusManager().apply {
            isProjectSwitchedThisRun = true
        }
        val recoverInvokeCount = intArrayOf(0)
        val deployStateRecover = object : DeployStateRecover(
            project = Mockito.mock(Project::class.java),
            deployTargetManager = deployTargetManager,
            deployFileManager = deployFileManager,
            deployHistoryManager = Mockito.mock(IDeployHistoryManager::class.java),
            deployStateManager = deployStateManager,
            deployRunHost = Mockito.mock(com.sickworm.intellij.jugg.deploy.run.flow.IJuggDeployHelperRunHost::class.java),
            deploymentService = Mockito.mock(com.sickworm.intellij.jugg.deploy.IJuggDeploymentService::class.java),
            deviceAdbFactory = { _, _ -> Mockito.mock(com.sickworm.intellij.jugg.deploy.IDeviceAdb::class.java) },
            logger = TestGlobal.getLogger(),
        ) {
            override fun recoverDeployState(
                device: IDevice,
                indicator: com.intellij.openapi.progress.ProgressIndicator?,
                isNeedDryDeployFirst: Boolean,
                isSkipExceptOverlayCheck: Boolean,
                isInstallUpdateApk: Boolean,
                compileUiHandler: com.sickworm.intellij.jugg.compiler.CompileUiHandler,
                allowDirectOverlayRecover: Boolean,
            ): Pair<Boolean, Boolean> {
                recoverInvokeCount[0]++
                return true to false
            }
        }

        val deployRunTaskExecutor = Mockito.mock(IJuggDeployRunTaskExecutor::class.java)
        Mockito.`when`(deployRunTaskExecutor.execute(org.mockito.kotlin.any())).thenReturn(LaunchResult(true, 0, null, emptyMap()))

        val helper = createHelper(
            deployTargetManager = deployTargetManager,
            deployStateManager = deployStateManager,
            deployFileManager = deployFileManager,
            dependencyChangeManager = dependencyChangeManager,
            juggRunningTaskStatusManager = statusManager,
            deployStateRecover = deployStateRecover,
            deployRunTaskExecutor = deployRunTaskExecutor,
        )
        val result = helper.deploy(
            DeployOptions(device = device, isLastDevice = true, isInstall = false),
        )

        assertTrue("recover not invoked, failedReason=${result.failedReason}", recoverInvokeCount[0] == 1)
        assertTrue(result.isSuccess)
    }

    @Test
    fun `deploy should pass pending hot reload deployData to retry when runTask throws instrumentation failed`() {
        val device = Mockito.mock(IDevice::class.java)
        val apkInfo = apkInfo("/tmp/jugg-deploy-test/app.apk")
        val hotReloadDeployData = hotReloadDeployData(apkInfo, "WeMusicApplicationLike")

        val deployTargetManager = Mockito.mock(IDeployTargetManager::class.java)
        Mockito.`when`(deployTargetManager.hasDevice).thenReturn(true)
        Mockito.`when`(deployTargetManager.getApks()).thenReturn(listOf(apkInfo))

        val deployState = JuggDeployState.READY
        val deployStateManager = Mockito.mock(DeployStateManager::class.java)
        Mockito.`when`(deployStateManager.updateDeployState()).thenReturn(deployState)
        Mockito.`when`(deployStateManager.getDeployState(device)).thenReturn(deployState)

        val deployFileManager = Mockito.mock(DeployFileManager::class.java)
        Mockito.`when`(deployFileManager.getDeployData(Mockito.anyBoolean(), Mockito.anyBoolean()))
            .thenReturn(hotReloadDeployData)

        val deployRetryHandler = Mockito.mock(DeployRetryHandler::class.java)
        var retryDeployData: JuggDeployData? = null
        whenever(
            deployRetryHandler.tryRetry(
                any(),
                any(),
                any(),
                any(),
            ),
        ).thenAnswer { invocation ->
            val reason = invocation.getArgument<String>(3)
            if (reason.contains("INSTRUMENTATION_FAILED")) {
                retryDeployData = invocation.getArgument(2)
                DeployTaskResult(isSuccess = true, costTime = 1L)
            } else {
                null
            }
        }

        val deployRunTaskExecutor = object : IJuggDeployRunTaskExecutor {
            override fun execute(request: JuggDeployRunTaskRequest): LaunchResult {
                throw JuggException.applyChangesFailed(
                    LaunchResult(false, 1, "INSTRUMENTATION_FAILED: test failure", emptyMap()),
                )
            }
        }

        val dependencyChangeManager = Mockito.mock(IDependencyChangeManager::class.java)
        Mockito.`when`(dependencyChangeManager.getRemovedLibraryFiles()).thenReturn(emptyList())

        val helper = createHelper(
            deployTargetManager = deployTargetManager,
            deployStateManager = deployStateManager,
            deployFileManager = deployFileManager,
            dependencyChangeManager = dependencyChangeManager,
            deployRetryHandler = deployRetryHandler,
            deployRunTaskExecutor = deployRunTaskExecutor,
        )
        val result = helper.deploy(
            DeployOptions(
                device = device,
                isLastDevice = true,
                isInstall = false,
                retryDeployData = hotReloadDeployData,
            ),
        )

        assertTrue(result.isSuccess)
        assertNotNull(retryDeployData)
        assertFalse(retryDeployData!!.isInstall)
        assertEquals(listOf("WeMusicApplicationLike"), retryDeployData!!.hotReloadModifiedClasses.map { it.name })
    }

    @Test
    fun `deploy should commit state when androidTest fails after successful deploy`() {
        val device = Mockito.mock(IDevice::class.java)
        val androidVersion = Mockito.mock(AndroidVersion::class.java)
        Mockito.`when`(androidVersion.apiLevel).thenReturn(30)
        Mockito.`when`(device.version).thenReturn(androidVersion)
        val apkInfo = apkInfo("/tmp/jugg-deploy-test/app.apk")
        val deployData = hotReloadDeployData(apkInfo, "PlayerPlayButtonGeneratedTest")
        val overlayIds = mapOf("com.example.app" to "overlay-id")

        val deployTargetManager = Mockito.mock(IDeployTargetManager::class.java)
        Mockito.`when`(deployTargetManager.hasDevice).thenReturn(true)
        Mockito.`when`(deployTargetManager.getApks()).thenReturn(listOf(apkInfo))

        val deployState = JuggDeployState.READY
        val deployStateManager = Mockito.mock(DeployStateManager::class.java)
        Mockito.`when`(deployStateManager.updateDeployState()).thenReturn(deployState)
        Mockito.`when`(deployStateManager.getDeployState(device)).thenReturn(deployState)

        val deployFileManager = Mockito.mock(DeployFileManager::class.java)
        Mockito.`when`(deployFileManager.getDeployData(Mockito.anyBoolean(), Mockito.anyBoolean()))
            .thenReturn(deployData)
        Mockito.`when`(deployFileManager.getStagingFiles()).thenReturn(emptyList())

        val deployHistoryManager = Mockito.mock(IDeployHistoryManager::class.java)
        val dependencyChangeManager = Mockito.mock(IDependencyChangeManager::class.java)
        Mockito.`when`(dependencyChangeManager.getRemovedLibraryFiles()).thenReturn(emptyList())

        val deployRunTaskExecutor = Mockito.mock(IJuggDeployRunTaskExecutor::class.java)
        Mockito.`when`(deployRunTaskExecutor.execute(org.mockito.kotlin.any()))
            .thenReturn(LaunchResult(false, 1, "Instrumentation test run reported failures.", overlayIds))

        val helper = createHelper(
            deployTargetManager = deployTargetManager,
            deployStateManager = deployStateManager,
            deployFileManager = deployFileManager,
            deployHistoryManager = deployHistoryManager,
            dependencyChangeManager = dependencyChangeManager,
            deployRunTaskExecutor = deployRunTaskExecutor,
        )

        val result = helper.deploy(
            DeployOptions(
                device = device,
                isLastDevice = true,
                isInstall = false,
                androidTestRunSpec = AndroidTestRunSpec("com.example.PlayerPlayButtonGeneratedTest", null),
            ),
        )

        assertFalse(result.isSuccess)
        assertEquals("Instrumentation test run reported failures.", result.failedReason)
        Mockito.verify(deployHistoryManager).updateHistoryOnAfterDeployed(emptyList())
        Mockito.verify(deployFileManager).commit(deployData)
        Mockito.verify(deployHistoryManager).lastDeployOverlayIds = overlayIds
    }

    @Test
    fun `runRecoverDeployTask should update overlay ids after install recover succeeds`() {
        val device = Mockito.mock(IDevice::class.java)
        val overlayIds = mapOf("com.example.app" to "install-overlay-id")
        val deployHistoryManager = Mockito.mock(IDeployHistoryManager::class.java)
        val deployRunTaskExecutor = Mockito.mock(IJuggDeployRunTaskExecutor::class.java)
        Mockito.`when`(deployRunTaskExecutor.execute(org.mockito.kotlin.any()))
            .thenReturn(LaunchResult(true, 0, null, overlayIds))

        val helper = createHelper(
            deployHistoryManager = deployHistoryManager,
            deployRunTaskExecutor = deployRunTaskExecutor,
        )

        helper.runRecoverDeployTask(
            device = device,
            data = JuggDeployData.forInstall(listOf(apkInfo("/tmp/jugg-deploy-test/app.apk"))),
            isSkipExceptOverlayCheck = false,
            compileUiHandler = com.sickworm.intellij.jugg.compiler.CompileUiHandler.DEFAULT,
            deferPostDeployLaunch = true,
            isAllowDirectOverlayDeploy = true,
        )

        Mockito.verify(deployHistoryManager).lastDeployOverlayIds = overlayIds
    }

    @Test
    fun `install deploy should run task with library test apk backfilled data`() {
        val device = Mockito.mock(IDevice::class.java)
        val appApk = apkInfo("/tmp/jugg-deploy-test/app.apk")
        val testApk = ApkInfo(
            files = listOf(ApkFileUnit("com.example.app.test", "", true, File("/tmp/jugg-deploy-test/app-test.apk"))),
            applicationId = "com.example.app.test",
            instrumentationTargetPackage = "com.example.app",
        )
        val deployTargetManager = Mockito.mock(IDeployTargetManager::class.java)
        Mockito.`when`(deployTargetManager.getApks()).thenReturn(listOf(appApk))

        val deployStateManager = Mockito.mock(DeployStateManager::class.java)
        Mockito.`when`(deployStateManager.updateDeployState()).thenReturn(JuggDeployState.READY)

        val backfillHelper = Mockito.mock(LibraryTestApkBackfillHelper::class.java)
        Mockito.`when`(
            backfillHelper.backfillIfNeeded(
                spec = anyOrNull(),
                data = any(),
                uiHandler = any(),
                installBackfilledApks = any(),
            )
        ).thenReturn(JuggDeployData.forInstall(listOf(appApk, testApk)))

        val deployRunTaskExecutor = Mockito.mock(IJuggDeployRunTaskExecutor::class.java)
        Mockito.`when`(deployRunTaskExecutor.execute(any()))
            .thenReturn(LaunchResult(true, 0, null, emptyMap()))

        val helper = createHelper(
            deployTargetManager = deployTargetManager,
            deployStateManager = deployStateManager,
            libraryTestApkBackfillHelper = backfillHelper,
            deployRunTaskExecutor = deployRunTaskExecutor,
        )
        val result = helper.deploy(
            DeployOptions(
                device = device,
                isLastDevice = true,
                isInstall = true,
                androidTestRunSpec = AndroidTestRunSpec(null, null, sourcePath = "/tmp/jugg-deploy-test/library1/src/androidTest/FooTest.kt"),
            ),
        )

        val requestCaptor = argumentCaptor<JuggDeployRunTaskRequest>()
        Mockito.verify(deployRunTaskExecutor).execute(requestCaptor.capture())
        assertTrue(result.isSuccess)
        assertEquals(
            listOf("com.example.app", "com.example.app.test"),
            requestCaptor.firstValue.data.apks.map { it.applicationId },
        )
    }

    private fun apkInfo(apkPath: String): ApkInfo {
        return ApkInfo(
            files = listOf(ApkFileUnit("com.example.app", "", true, File(apkPath))),
            applicationId = "com.example.app",
        )
    }

    private fun hotReloadDeployData(apkInfo: ApkInfo, className: String): JuggDeployData {
        val deployItem = DeployItem(
            name = className,
            type = CompileOutput.Type.Dex,
            checksum = 1L,
            content = byteArrayOf(1),
            apkPath = apkInfo.files.first().apkFile.path,
            targetApkPaths = listOf(apkInfo.files.first().apkFile.path),
        )
        return JuggDeployData(
            apks = listOf(apkInfo),
            newClasses = emptyList(),
            hotFixModifiedClasses = emptyList(),
            hotReloadModifiedClasses = listOf(ClassDeployItem(deployItem, emptyList())),
            effectedClassNodes = emptyList(),
            overlays = emptyList(),
            parsedDex = ParsedDex.EMPTY,
            isFullRes = false,
            isWarmUp = false,
        )
    }

    private fun createHelper(
        deployTargetManager: IDeployTargetManager = Mockito.mock(IDeployTargetManager::class.java),
        deployStateManager: DeployStateManager = Mockito.mock(DeployStateManager::class.java),
        deployFileManager: DeployFileManager = Mockito.mock(DeployFileManager::class.java),
        dependencyChangeManager: IDependencyChangeManager = Mockito.mock(IDependencyChangeManager::class.java),
        deployHistoryManager: IDeployHistoryManager = Mockito.mock(IDeployHistoryManager::class.java),
        juggRunningTaskStatusManager: IJuggRunningTaskStatusManager = JuggRunningTaskStatusManager(),
        deployRetryHandler: DeployRetryHandler? = null,
        deployStateRecover: DeployStateRecover? = null,
        deployRunTaskExecutor: IJuggDeployRunTaskExecutor? = null,
        libraryTestApkBackfillHelper: LibraryTestApkBackfillHelper? = null,
    ): JuggDeployerHelper {
        val project = Mockito.mock(Project::class.java)
        Mockito.`when`(project.basePath).thenReturn("/tmp/jugg-deploy-test")

        val compileContext = Mockito.mock(ICompileContext::class.java)
        Mockito.`when`(compileContext.isDebuggable).thenReturn(true)
        val compileContextManager = Mockito.mock(CompileContextManager::class.java)
        Mockito.`when`(compileContextManager.compileContext).thenReturn(compileContext)

        val passthroughBackfillHelper = libraryTestApkBackfillHelper
            ?: Mockito.mock(LibraryTestApkBackfillHelper::class.java).also {
                Mockito.`when`(
                    it.backfillIfNeeded(
                        spec = anyOrNull(),
                        data = any(),
                        uiHandler = any(),
                        installBackfilledApks = any(),
                    )
                ).thenAnswer { invocation -> invocation.getArgument<JuggDeployData>(1) }
            }

        return JuggDeployerHelper(
            project = project,
            deployTargetManager = deployTargetManager,
            deployFileManager = deployFileManager,
            deployHistoryManager = deployHistoryManager,
            deployStateManager = deployStateManager,
            dependencyChangeManager = dependencyChangeManager,
            juggRunningTaskStatusManager = juggRunningTaskStatusManager,
            compileContextManager = compileContextManager,
            juggServer = Mockito.mock(JuggServer::class.java),
            taskRunnerManager = Mockito.mock(TaskRunnerManager::class.java),
            logger = TestGlobal.getLogger(),
            stateRecover = deployStateRecover,
            retryHandler = deployRetryHandler,
            deployRunTaskExecutor = deployRunTaskExecutor,
            libraryTestApkBackfillHelper = passthroughBackfillHelper,
        )
    }

    companion object {
        @BeforeClass
        @JvmStatic
        fun initTestEnv() {
            TestGlobal.init()
            JuggSettings.isEmbeddedToApk = false
        }
    }
}
