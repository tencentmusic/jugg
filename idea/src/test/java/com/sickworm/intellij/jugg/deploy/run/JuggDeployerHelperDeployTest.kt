package com.sickworm.intellij.jugg.deploy.run

import com.android.ddmlib.IDevice
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
import com.sickworm.intellij.jugg.deploy.JuggDeployState
import com.sickworm.intellij.jugg.deploy.data.ParsedDex
import com.sickworm.intellij.jugg.deploy.run.flow.DeployRetryHandler
import com.sickworm.intellij.jugg.deploy.run.flow.IJuggDeployRunTaskExecutor
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
        deployRetryHandler: DeployRetryHandler? = null,
        deployRunTaskExecutor: IJuggDeployRunTaskExecutor? = null,
    ): JuggDeployerHelper {
        val project = Mockito.mock(Project::class.java)
        Mockito.`when`(project.basePath).thenReturn("/tmp/jugg-deploy-test")

        val compileContext = Mockito.mock(ICompileContext::class.java)
        Mockito.`when`(compileContext.isDebuggable).thenReturn(true)
        val compileContextManager = Mockito.mock(CompileContextManager::class.java)
        Mockito.`when`(compileContextManager.compileContext).thenReturn(compileContext)

        return JuggDeployerHelper(
            project = project,
            deployTargetManager = deployTargetManager,
            deployFileManager = deployFileManager,
            deployHistoryManager = Mockito.mock(IDeployHistoryManager::class.java),
            deployStateManager = deployStateManager,
            dependencyChangeManager = dependencyChangeManager,
            compileContextManager = compileContextManager,
            juggServer = Mockito.mock(JuggServer::class.java),
            taskRunnerManager = Mockito.mock(TaskRunnerManager::class.java),
            logger = TestGlobal.getLogger(),
            injectedDeployRetryHandler = deployRetryHandler,
            injectedDeployRunTaskExecutor = deployRunTaskExecutor,
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
