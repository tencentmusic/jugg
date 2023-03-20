package com.sickworm.intellij.jugg.deploy

import com.android.ddmlib.*
import com.android.tools.idea.run.ApkInfo
import com.intellij.execution.ExecutionResult

/**
 * Manage device list，application state
 */
interface IDeployTargetManager {

    /**
     * Click the "Run" button for current selected configuration
     */
    fun runFullBuildAndLaunch(): ExecutionResult

    /**
     * Set apks from full build result or from recover history
     */
    fun setApks(apks: List<ApkInfo>)

    fun getApks(): List<ApkInfo>

    fun getDevice(): IDevice

    fun restartApp(): Boolean
}