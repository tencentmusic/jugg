package com.sickworm.intellij.jugg.deploy

import com.android.ddmlib.*
import com.android.tools.idea.run.ApkInfo

/**
 * Manage device list，application state
 */
interface IDeployTargetManager {

    fun runFullBuildAndLaunch()

    /**
     * Use apks from recover history instead of reading it from gradle, which requires full build.
     */
    fun setApksFromRecover(apks: List<ApkInfo>)

    fun getApks(): List<ApkInfo>

    fun getDevice(): IDevice

    fun restartApp(): Boolean
}