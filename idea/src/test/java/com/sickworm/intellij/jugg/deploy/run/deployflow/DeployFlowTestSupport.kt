package com.sickworm.intellij.jugg.deploy.run.deployflow

import com.android.ddmlib.IDevice
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Computable
import com.sickworm.intellij.jugg.apk.ApkInfo
import com.sickworm.intellij.jugg.compiler.CompileOutput
import com.sickworm.intellij.jugg.compiler.ICompileContext
import com.sickworm.intellij.jugg.deploy.DeployFileManager
import com.sickworm.intellij.jugg.deploy.IDeviceAdb
import com.sickworm.intellij.jugg.deploy.DeployStateManager
import com.sickworm.intellij.jugg.deploy.IDeployHistoryManager
import com.sickworm.intellij.jugg.deploy.IDeployTargetManager
import com.sickworm.intellij.jugg.deploy.IIdeDeployStateHelper
import com.sickworm.intellij.jugg.deploy.JuggRunningTaskStatusManager
import com.sickworm.intellij.jugg.deploy.data.ParsedDex
import com.sickworm.intellij.jugg.deploy.run.DeployItem
import com.sickworm.intellij.jugg.deploy.run.DeployOptions
import com.sickworm.intellij.jugg.deploy.run.JuggDeployData
import com.sickworm.intellij.jugg.deploy.run.IAsDeployerCompat
import com.sickworm.intellij.jugg.deploy.run.JuggDeployerHelper
import com.sickworm.intellij.jugg.deploy.run.JuggDeploymentService
import com.sickworm.intellij.jugg.mock.TestGlobal
import com.sickworm.intellij.jugg.mock.context
import com.sickworm.intellij.jugg.project.CompileContextManager
import com.sickworm.intellij.jugg.project.TaskRunnerManager
import com.sickworm.intellij.jugg.project.dependency.IDependencyChangeManager
import com.sickworm.intellij.jugg.server.JuggServer
import kotlinx.coroutines.CompletableDeferred
import org.mockito.Mockito
import org.mockito.kotlin.any
import org.mockito.kotlin.whenever
import java.io.File

internal object DeployFlowTestSupport {

    /** Deploy payload that does not force [com.sickworm.intellij.jugg.deploy.run.JuggDeployData.isNeedRestartApp]. */
    fun incrementalDeployDataWithoutAppRestart(apkInfos: List<ApkInfo> = context.apkInfos): JuggDeployData {
        return incrementalDeployData(apkInfos).copy(isPushOverlayOnly = false)
    }

    fun incrementalDeployData(apkInfos: List<ApkInfo> = context.apkInfos): JuggDeployData {
        val apkPath = apkInfos.first().files.first().apkFile.path
        return JuggDeployData(
            apks = apkInfos,
            newClasses = emptyList(),
            hotFixModifiedClasses = emptyList(),
            hotReloadModifiedClasses = emptyList(),
            effectedClassNodes = emptyList(),
            overlays = listOf(
                DeployItem(
                    name = "res/layout/deploy_flow_main.xml",
                    type = CompileOutput.Type.Res,
                    checksum = 1L,
                    content = byteArrayOf(1, 2, 3),
                    apkPath = apkPath,
                ),
            ),
            parsedDex = ParsedDex.EMPTY,
            isFullRes = false,
            isWarmUp = false,
            isPushOverlayOnly = true,
        )
    }

    fun fullResourceDeployData(
        apkInfos: List<ApkInfo> = context.apkInfos,
        overlayCount: Int,
    ): JuggDeployData {
        val apkPath = apkInfos.first().files.first().apkFile.path
        return JuggDeployData(
            apks = apkInfos,
            newClasses = emptyList(),
            hotFixModifiedClasses = emptyList(),
            hotReloadModifiedClasses = emptyList(),
            effectedClassNodes = emptyList(),
            overlays = (0 until overlayCount).map { index ->
                DeployItem(
                    name = "res/layout/full_resource_$index.xml",
                    type = CompileOutput.Type.Res,
                    checksum = index.toLong(),
                    content = byteArrayOf(index.toByte()),
                    apkPath = apkPath,
                )
            },
            parsedDex = ParsedDex.EMPTY,
            isFullRes = true,
            isWarmUp = false,
            isPushOverlayOnly = false,
        )
    }

    fun emptyDeployData(apkInfos: List<ApkInfo> = context.apkInfos): JuggDeployData {
        return JuggDeployData(
            apks = apkInfos,
            newClasses = emptyList(),
            hotFixModifiedClasses = emptyList(),
            hotReloadModifiedClasses = emptyList(),
            effectedClassNodes = emptyList(),
            overlays = emptyList(),
            parsedDex = ParsedDex.EMPTY,
            isFullRes = false,
            isWarmUp = false,
            isPushOverlayOnly = false,
        )
    }

    fun createHelper(
        project: Project,
        virtualDevice: VirtualDeployDevice,
        deployTargetManager: IDeployTargetManager,
        deployFileManager: DeployFileManager,
        deployStateManager: DeployStateManager,
        deployHistoryManager: IDeployHistoryManager,
        ideDeployStateHelper: DeployFlowIdeDeployStateHelper,
        installPathProvider: Computable<String>,
        asDeployerCompat: IAsDeployerCompat,
        recoverRunHost: DeployFlowRecoverRunHost = DeployFlowRecoverRunHost(),
        afterRecoverSuccess: Runnable? = null,
    ): JuggDeployerHelper {

        val compileContext = Mockito.mock(ICompileContext::class.java)
        Mockito.`when`(compileContext.isDebuggable).thenReturn(true)
        val compileContextManager = Mockito.mock(CompileContextManager::class.java)
        Mockito.`when`(compileContextManager.compileContext).thenReturn(compileContext)

        val taskRunnerManager = Mockito.mock(TaskRunnerManager::class.java)
        whenever(taskRunnerManager.runAsyncSafe<Boolean>(any(), any())).thenReturn(CompletableDeferred(false))

        val ideaLogger = TestGlobal.getLogger()
        val deviceAdbFactory: (IDevice, Logger) -> IDeviceAdb = { _, _ -> virtualDevice.asIDeviceAdb() }
        val deployStateRecover = DeployFlowRecoverFixtureHooks(
            project = project,
            deployTargetManager = deployTargetManager,
            deployFileManager = deployFileManager,
            deployHistoryManager = deployHistoryManager,
            deployStateManager = deployStateManager,
            deployRunHost = recoverRunHost,
            deploymentService = JuggDeploymentService,
            deviceAdbFactory = deviceAdbFactory,
            logger = ideaLogger,
            ideDeployStateHelper = ideDeployStateHelper,
            afterRecoverSuccess = afterRecoverSuccess,
        )
        val helper = JuggDeployerHelper(
            project = project,
            deployTargetManager = deployTargetManager,
            deployFileManager = deployFileManager,
            deployHistoryManager = deployHistoryManager,
            deployStateManager = deployStateManager,
            dependencyChangeManager = defaultDependencyChangeManager(),
            juggRunningTaskStatusManager = JuggRunningTaskStatusManager(),
            compileContextManager = compileContextManager,
            juggServer = Mockito.mock(JuggServer::class.java),
            taskRunnerManager = taskRunnerManager,
            logger = ideaLogger,
            deploymentService = JuggDeploymentService,
            deviceAdbFactory = deviceAdbFactory,
            installPathProvider = installPathProvider,
            asDeployerCompat = asDeployerCompat,
            stateRecover = deployStateRecover,
        )
        recoverRunHost.bind(helper)
        return helper
    }

    fun createDeployStateManager(
        project: Project,
        deployTargetManager: IDeployTargetManager,
        deployHistoryManager: IDeployHistoryManager,
        ideDeployStateHelper: IIdeDeployStateHelper,
    ): DeployStateManager {
        return DeployStateManager(
            project = project,
            deployTargetManager = deployTargetManager,
            deployHistoryManager = deployHistoryManager,
            ideDeployStateHelper = ideDeployStateHelper,
        )
    }

    fun defaultDeployTargetManager(virtualDevice: VirtualDeployDevice): IDeployTargetManager {
        val device = virtualDevice.asDdmlibDevice()
        val deployTargetManager = Mockito.mock(IDeployTargetManager::class.java)
        Mockito.`when`(deployTargetManager.hasDevice).thenReturn(true)
        Mockito.`when`(deployTargetManager.isAppInstalled(device)).thenReturn(true)
        val packageName = DeployFlowOverlaySeed.packageName()
        Mockito.`when`(deployTargetManager.getPackageName()).thenReturn(packageName)
        Mockito.`when`(deployTargetManager.getPackageNameOrNull()).thenReturn(packageName)
        Mockito.`when`(deployTargetManager.getApks()).thenReturn(context.apkInfos)
        Mockito.`when`(deployTargetManager.restartApp(device)).thenReturn(true)
        Mockito.`when`(deployTargetManager.restartAppForDebug(device)).thenReturn(true)
        return deployTargetManager
    }

    fun defaultDeployFileManager(deployData: JuggDeployData): DeployFileManager {
        val deployFileManager = Mockito.mock(DeployFileManager::class.java)
        Mockito.`when`(deployFileManager.getDeployData(Mockito.anyBoolean(), Mockito.anyBoolean())).thenReturn(deployData)
        Mockito.`when`(deployFileManager.getStagingFiles()).thenReturn(emptyList())
        return deployFileManager
    }

    fun defaultDependencyChangeManager(): IDependencyChangeManager {
        val dependencyChangeManager = Mockito.mock(IDependencyChangeManager::class.java)
        Mockito.`when`(dependencyChangeManager.getRemovedLibraryFiles()).thenReturn(emptyList())
        Mockito.`when`(dependencyChangeManager.getNewLibraryFiles()).thenReturn(emptyList())
        return dependencyChangeManager
    }

    fun defaultDeployOptions(device: IDevice): DeployOptions {
        return DeployOptions(device = device, isLastDevice = true, isInstall = false)
    }
}
