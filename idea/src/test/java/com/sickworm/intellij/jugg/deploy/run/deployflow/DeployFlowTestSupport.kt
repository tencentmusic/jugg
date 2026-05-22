package com.sickworm.intellij.jugg.deploy.run.deployflow

import com.android.ddmlib.IDevice
import com.android.sdklib.AndroidVersion
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.sickworm.intellij.jugg.apk.ApkFileUnit
import com.sickworm.intellij.jugg.apk.ApkInfo
import com.sickworm.intellij.jugg.compiler.CompileOutput
import com.sickworm.intellij.jugg.compiler.ICompileContext
import com.sickworm.intellij.jugg.deploy.DeployFileManager
import com.sickworm.intellij.jugg.deploy.DeployStateManager
import com.sickworm.intellij.jugg.deploy.IDeployHistoryManager
import com.sickworm.intellij.jugg.deploy.IDeployTargetManager
import com.sickworm.intellij.jugg.deploy.JuggDeployState
import com.sickworm.intellij.jugg.deploy.data.ParsedDex
import com.sickworm.intellij.jugg.deploy.run.DeployItem
import com.sickworm.intellij.jugg.deploy.run.DeployOptions
import com.sickworm.intellij.jugg.deploy.run.IdeDeployState
import com.sickworm.intellij.jugg.deploy.run.JuggDeployData
import com.sickworm.intellij.jugg.deploy.run.JuggDeployerHelper
import com.sickworm.intellij.jugg.deploy.run.flow.DeployStateRecover
import com.sickworm.intellij.jugg.project.CompileContextManager
import com.sickworm.intellij.jugg.project.TaskRunnerManager
import com.sickworm.intellij.jugg.project.dependency.IDependencyChangeManager
import com.sickworm.intellij.jugg.server.JuggServer
import org.mockito.Mockito
import org.mockito.kotlin.any
import org.mockito.kotlin.whenever
import java.io.File
import kotlinx.coroutines.CompletableDeferred

internal object DeployFlowTestSupport {

    val appNotDeployableState = JuggDeployState(
        JuggDeployState.State.READY_INCREMENTAL_COMPILE,
        "not ready to deploy",
        IdeDeployState.ok,
    )

    val deployReadyState = JuggDeployState.READY

    fun mockDevice(): IDevice {
        val device = Mockito.mock(IDevice::class.java)
        Mockito.`when`(device.serialNumber).thenReturn("deploy-flow-device")
        Mockito.`when`(device.isOnline).thenReturn(true)
        Mockito.`when`(device.version).thenReturn(AndroidVersion(30, null))
        return device
    }

    fun incrementalDeployData(packageName: String = RecordingDeployRunTaskExecutor.DEFAULT_PACKAGE): JuggDeployData {
        val apkPath = "/tmp/$packageName/base.apk"
        val apkInfo = ApkInfo(
            files = listOf(ApkFileUnit(packageName, "", true, File(apkPath))),
            applicationId = packageName,
        )
        return JuggDeployData(
            apks = listOf(apkInfo),
            newClasses = emptyList(),
            hotFixModifiedClasses = emptyList(),
            hotReloadModifiedClasses = emptyList(),
            effectedClassNodes = emptyList(),
            overlays = listOf(
                DeployItem(
                    name = "res/layout/main.xml",
                    type = CompileOutput.Type.Res,
                    checksum = 1L,
                    content = byteArrayOf(1),
                    apkPath = apkPath,
                ),
            ),
            parsedDex = ParsedDex.EMPTY,
            isFullRes = false,
            isWarmUp = false,
            isPushOverlayOnly = true,
        )
    }

    fun createHelper(
        device: IDevice,
        deployTargetManager: IDeployTargetManager,
        deployFileManager: DeployFileManager,
        deployStateManager: DeployStateManager,
        deployHistoryManager: IDeployHistoryManager = defaultDeployHistoryManager(),
        deployStateRecover: DeployStateRecover? = null,
        executor: RecordingDeployRunTaskExecutor = RecordingDeployRunTaskExecutor(),
    ): JuggDeployerHelper {
        val project = Mockito.mock(Project::class.java)
        Mockito.`when`(project.basePath).thenReturn("/tmp/jugg-deploy-flow-test")

        val compileContext = Mockito.mock(ICompileContext::class.java)
        Mockito.`when`(compileContext.isDebuggable).thenReturn(true)
        val compileContextManager = Mockito.mock(CompileContextManager::class.java)
        Mockito.`when`(compileContextManager.compileContext).thenReturn(compileContext)

        val taskRunnerManager = Mockito.mock(TaskRunnerManager::class.java)
        whenever(taskRunnerManager.runAsyncSafe<Boolean>(any(), any())).thenReturn(CompletableDeferred(false))

        return JuggDeployerHelper(
            project = project,
            deployTargetManager = deployTargetManager,
            deployFileManager = deployFileManager,
            deployHistoryManager = deployHistoryManager,
            deployStateManager = deployStateManager,
            dependencyChangeManager = defaultDependencyChangeManager(),
            compileContextManager = compileContextManager,
            juggServer = Mockito.mock(JuggServer::class.java),
            taskRunnerManager = taskRunnerManager,
            logger = Mockito.mock(Logger::class.java),
            injectedDeployStateRecover = deployStateRecover,
            injectedDeployRunTaskExecutor = executor,
        )
    }

    fun defaultDeployOptions(device: IDevice): DeployOptions {
        return DeployOptions(device = device, isLastDevice = true, isInstall = false)
    }

    fun defaultDeployHistoryManager(): IDeployHistoryManager {
        val deployHistoryManager = Mockito.mock(IDeployHistoryManager::class.java)
        Mockito.`when`(deployHistoryManager.lastDeployOverlayIds).thenReturn(emptyMap())
        return deployHistoryManager
    }

    fun defaultDependencyChangeManager(): IDependencyChangeManager {
        val dependencyChangeManager = Mockito.mock(IDependencyChangeManager::class.java)
        Mockito.`when`(dependencyChangeManager.getRemovedLibraryFiles()).thenReturn(emptyList())
        Mockito.`when`(dependencyChangeManager.getNewLibraryFiles()).thenReturn(emptyList())
        return dependencyChangeManager
    }

    fun defaultDeployFileManager(deployData: JuggDeployData): DeployFileManager {
        val deployFileManager = Mockito.mock(DeployFileManager::class.java)
        Mockito.`when`(deployFileManager.getDeployData(Mockito.anyBoolean(), Mockito.anyBoolean())).thenReturn(deployData)
        Mockito.`when`(deployFileManager.getStagingFiles()).thenReturn(emptyList())
        return deployFileManager
    }
}
