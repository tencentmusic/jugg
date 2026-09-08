package com.sickworm.intellij.jugg.ai.mcp

import com.sickworm.intellij.jugg.deploy.api.IDevice
import com.sickworm.intellij.jugg.deploy.IDeployTargetManager

/**
 * DeviceSelectionResolver resolves device selection decisions.
 */
class DeviceSelectionResolver {

    fun resolve(deployTargetManager: IDeployTargetManager, serial: String? = null): DeviceSelectionResult {
        val targetSerial = serial?.trim()?.takeIf { it.isNotEmpty() }
        if (targetSerial != null) {
            // The target manager owns exact online filtering, including hosts without an IDeviceAdb adapter.
            val selectedDevice = deployTargetManager.getTargetDevices(targetSerial)
                .firstOrNull()
                ?: return DeviceSelectionResult.NoDevice("Device $targetSerial is not online.")
            return DeviceSelectionResult.Selected(selectedDevice)
        }

        val selectedDevices = deployTargetManager.getSelectedDevices()
        val connectedDevices = deployTargetManager.getConnectedDevices()

        if (selectedDevices.size > 1) {
            return DeviceSelectionResult.MultipleDevices(multipleDevicesMessage(selectedDevices))
        }
        if (connectedDevices.isEmpty()) {
            return DeviceSelectionResult.NoDevice("No connected device is available.")
        }
        if (selectedDevices.isEmpty() && connectedDevices.size > 1) {
            return DeviceSelectionResult.MultipleDevices(multipleDevicesMessage(connectedDevices))
        }

        val selectedDevice = selectedDevices.firstOrNull()
            ?: connectedDevices.firstOrNull()
            ?: return DeviceSelectionResult.NoDevice("No connected device is available.")

        return DeviceSelectionResult.Selected(device = selectedDevice)
    }

    private fun multipleDevicesMessage(devices: List<IDevice>): String {
        return "Multiple devices are online (${devices.joinToString { it.serialNumber }}). Pass --serial to select one device."
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

    /** MultipleDevices requires the caller to provide an explicit target. */
    data class MultipleDevices(
        val messageDetail: String,
    ) : DeviceSelectionResult()
}
