package com.sickworm.intellij.jugg.deploy

import com.android.ddmlib.*
import com.android.tools.idea.run.ApkInfo

/**
 * Manage device list，application state
 */
interface IDeployTargetManager {

    fun runFullBuildAndLaunch()

    fun getApks(): List<ApkInfo>

    fun getDevice(): IDevice

    fun restartApp()
}