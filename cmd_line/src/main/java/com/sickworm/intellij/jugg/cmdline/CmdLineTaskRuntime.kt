package com.sickworm.intellij.jugg.cmdline

import com.sickworm.intellij.jugg.deploy.api.IDevice
import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.jugg.deploy.*
import com.sickworm.intellij.jugg.project.runtime.IHostTaskExecutor
import com.sickworm.intellij.jugg.project.runtime.JuggPathManager
import com.sickworm.intellij.jugg.project.runtime.TaskRunnerManager
import com.sickworm.intellij.jugg.server.JuggServer
import com.sickworm.intellij.jugg.platform.PlatformApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel

internal class CmdLineTaskRuntime(pathManager: JuggPathManager, coroutineScope: CoroutineScope, logger: Logger) {
    val deployStateManager = object : IDeployStateManager {
        override val deployState: JuggDeployState = JuggDeployState.READY
        override var isBuildFileChanged: Boolean = false
        override var whatBuildFileChanged: String = ""
        override var isInitializingIncrementalCompile: Boolean = false

        override fun updateDeployState(): JuggDeployState {
            return JuggDeployState.READY
        }

        override fun getDeployState(device: IDevice): JuggDeployState = JuggDeployState.READY

        override fun beginFileProcessing() = Unit

        override fun endFileProcessing() = Unit

        override fun hasPendingFileProcessing(): Boolean = false

        override fun waitForPendingFileProcessing(timeoutMs: Long): FileProcessingWaitResult {
            return FileProcessingWaitResult(false, 0, 0L, 0)
        }
    }

    private val runtimeInfo = PlatformApi.getRuntimeInfo()
    val juggServer = JuggServer(pathManager.projectDir.name, pathManager, coroutineScope, runtimeInfo, logger)

    val taskRunnerManager = TaskRunnerManager(
        logger = logger,
        deployStateManager = deployStateManager,
        juggServer = juggServer,
        hostTaskExecutor = object : IHostTaskExecutor {
            override val isOnEdt: Boolean = false

            override fun submit(title: String, cancelText: String, showIndicator: Boolean, action: Runnable) {
                action.run()
            }
        },
        pathManager = pathManager,
        runtimeType = runtimeInfo.runtimeType,
        runtimeVersion = runtimeInfo.runtimeVersion,
        coroutineScope = coroutineScope,
    )

    fun dispose() {
        taskRunnerManager.dispose()
        juggServer.cancel()
    }
}
