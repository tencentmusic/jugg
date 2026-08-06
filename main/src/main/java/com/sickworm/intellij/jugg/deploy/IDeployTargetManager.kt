package com.sickworm.intellij.jugg.deploy

import com.sickworm.intellij.jugg.apk.ApkInfo
import com.sickworm.intellij.jugg.deploy.api.IDevice

/**
 * Manage device and operation of application on device.
 */
interface IDeployTargetManager {

    /**
     * Set apks from full build result or from recover history
     */
    fun setApks(apks: List<ApkInfo>)

    fun getApks(): List<ApkInfo>

    /**
     * @return devices selected in the IDE that are already running. This method must not boot virtual devices.
     */
    fun getSelectedDevices(): List<IDevice>

    /**
     * @return devices that is online in adb.
     */
    fun getConnectedDevices(): List<IDevice>

    fun startApp(device: IDevice): Boolean

    fun restartApp(device: IDevice): Boolean

    fun restartAppForDebug(device: IDevice): Boolean = restartApp(device)

    fun stopApp(device: IDevice): Boolean

    fun isAppForeground(device: IDevice): Boolean

    /**
     * Returns null when install state cannot be determined cheaply.
     */
    fun isAppInstalled(device: IDevice): Boolean? = null

    fun getPackageName(): String

    fun dumpErrorLogs(): String = ""

    val hasDevice: Boolean
        get() = getSelectedDevices().isNotEmpty()

    fun getDeviceNameList(): String? {
        return try {
            val devices = getSelectedDevices()
            if (devices.isEmpty()) {
                return null
            }
            devices.joinToString(", ") { it.name }
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
