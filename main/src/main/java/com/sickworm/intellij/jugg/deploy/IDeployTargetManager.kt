package com.sickworm.intellij.jugg.deploy

import com.android.ddmlib.*
import com.android.tools.idea.run.ApkInfo
import com.intellij.execution.ExecutionResult
import com.intellij.openapi.Disposable
import com.sickworm.intellij.jugg.ide.GradleCompileSettings

/**
 * Manage device list，application state
 */
interface IDeployTargetManager : Disposable {

    /**
     * Click the "Run" button for current selected configuration
     */
    fun runFullBuildAndLaunch(settings: GradleCompileSettings?): ExecutionResult

    /**
     * Use apks from recover history instead of reading it from gradle, which requires full build.
     */
    fun setApksFromRecover(apks: List<ApkInfo>)

    fun getApks(): List<ApkInfo>

    fun getDevice(): IDevice

    fun restartApp(): Boolean
}