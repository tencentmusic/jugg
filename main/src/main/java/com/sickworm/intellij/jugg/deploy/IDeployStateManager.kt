package com.sickworm.intellij.jugg.deploy

import com.sickworm.intellij.jugg.deploy.api.IDevice

/**
 * IDeployStateManager computes and updates current deploy mode/state from runtime conditions.
 */
interface IDeployStateManager {

    val deployState: JuggDeployState

    var isBuildFileChanged: Boolean

    var whatBuildFileChanged: String

    var isInitializingIncrementalCompile: Boolean

    fun updateDeployState(): JuggDeployState

    fun getDeployState(device: IDevice): JuggDeployState

    fun beginFileProcessing()

    fun endFileProcessing()

    fun hasPendingFileProcessing(): Boolean

    fun waitForPendingFileProcessing(timeoutMs: Long = 1_000L): FileProcessingWaitResult
}

data class FileProcessingWaitResult(
    val isTimeout: Boolean,
    val pendingCount: Int,
    val waitedMs: Long,
    val initialPendingCount: Int,
)
