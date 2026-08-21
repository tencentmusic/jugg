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

    /**
     * Resolves the devices for one request without mutating the Host's persisted selection.
     */
    fun getTargetDevices(serial: String?): List<IDevice> {
        val targetSerial = serial?.trim()?.takeIf { it.isNotEmpty() } ?: return getSelectedDevices()
        return getConnectedDevices().filter { it.serialNumber == targetSerial && it.isOnline }
    }

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

    /** Returns display names for the request-scoped target devices. */
    fun getTargetDeviceNameList(serial: String?): String? {
        return try {
            getTargetDevices(serial).takeIf { it.isNotEmpty() }?.joinToString(", ") { it.name }
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

/** Returns stable identities for request-scoped target devices. */
fun IDeployTargetManager.getTargetDeviceSerialList(serial: String?): String? {
    return try {
        getTargetDevices(serial).takeIf { it.isNotEmpty() }?.joinToString(",") { it.serialNumber }
    } catch (e: Exception) {
        null
    }
}
