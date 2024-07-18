package com.sickworm.intellij.jugg.deploy

import com.android.ddmlib.*
import com.android.tools.idea.run.ApkInfo

/**
 * Manage device and operation of application on device.
 */
interface IDeployTargetManager {

    /**
     * Set apks from full build result or from recover history
     */
    fun setApks(apks: List<ApkInfo>)

    fun getApks(): List<ApkInfo>

    fun getDevices(): List<IDevice>

    fun startApp(device: IDevice): Boolean

    fun restartApp(device: IDevice): Boolean

    fun stopApp(device: IDevice): Boolean

    fun isAppForeground(device: IDevice): Boolean

    fun getPackageName(): String

    fun dumpErrorLogs(): String = ""

    val hasDevice: Boolean
        get() = getDevices().isNotEmpty()

    fun getDeviceNameList(): String? {
        return try {
            val device = getDevices()
            if (device.isEmpty()) {
                return null
            }
            getDevices().joinToString(", ") { it.name }
        } catch (e: Exception) {
            null
        }
    }

    fun getPackageNameOrNull(): String? {
        return try {
            getPackageName()
        } catch (e: Exception) {
            null
        }
    }
}