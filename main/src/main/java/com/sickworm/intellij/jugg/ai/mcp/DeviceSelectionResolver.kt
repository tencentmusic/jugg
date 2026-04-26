package com.sickworm.intellij.jugg.ai.mcp

import com.android.ddmlib.IDevice
import com.sickworm.intellij.jugg.deploy.IDeployTargetManager
import com.sickworm.intellij.jugg.platform.PlatformApi

/**
 * DeviceSelectionResolver resolves device selection decisions.
 */
class DeviceSelectionResolver {

    fun resolve(deployTargetManager: IDeployTargetManager): DeviceSelectionResult {
        val selectedDevices = deployTargetManager.getSelectedDevices().filter { isDeviceOnline(it) }
        val connectedDevices = deployTargetManager.getConnectedDevices().filter { isDeviceOnline(it) }

        if (connectedDevices.isEmpty()) {
            return DeviceSelectionResult.NoDevice("No connected device is available.")
        }

        val selectedDevice = selectedDevices.firstOrNull()
            ?: connectedDevices.firstOrNull()
            ?: return DeviceSelectionResult.NoDevice("No connected device is available.")

        return DeviceSelectionResult.Selected(device = selectedDevice)
    }

    private fun isDeviceOnline(device: IDevice): Boolean {
        val adb = PlatformApi.toDeviceAdb(device)
        return adb?.isOnline ?: false
    }
}

/**
 * DeviceSelectionResult carries device, reason, and messageDetail.
 */
sealed class DeviceSelectionResult {
    /**
     * Selected carries device, reason, and messageDetail.
     */
    data class Selected(
        val device: IDevice,
    ) : DeviceSelectionResult()

    /**
     * NoDevice carries messageDetail.
     */
    data class NoDevice(
        val messageDetail: String,
    ) : DeviceSelectionResult()
}
