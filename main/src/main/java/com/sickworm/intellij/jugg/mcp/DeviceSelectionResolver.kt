package com.sickworm.intellij.jugg.mcp

import com.android.ddmlib.IDevice
import com.sickworm.intellij.jugg.deploy.IDeployTargetManager
import com.sickworm.intellij.jugg.platform.PlatformApi

class DeviceSelectionResolver {

    fun resolve(serial: String?, deployTargetManager: IDeployTargetManager): DeviceSelectionResult {
        val selectedDevices = deployTargetManager.getSelectedDevices().filter { isDeviceOnline(it) }
        val connectedDevices = deployTargetManager.getConnectedDevices().filter { isDeviceOnline(it) }

        if (connectedDevices.isEmpty()) {
            return DeviceSelectionResult.NoDevice("No connected device is available.")
        }

        if (!serial.isNullOrBlank()) {
            val serialMatchedDevice = connectedDevices.firstOrNull { deviceSerial(it) == serial }
            if (serialMatchedDevice != null) {
                return DeviceSelectionResult.Selected(
                    device = serialMatchedDevice,
                    reason = DeviceSelectionReason.BY_SERIAL,
                    messageDetail = "Device selected by serial: ${deviceIdentity(serialMatchedDevice)}."
                )
            }
        }

        val selectedDevice = selectedDevices.firstOrNull()
            ?: return DeviceSelectionResult.NoDevice("No connected device is available.")

        if (serial.isNullOrBlank()) {
            return DeviceSelectionResult.Selected(
                device = selectedDevice,
                reason = DeviceSelectionReason.FALLBACK_SELECTED_MISSING_SERIAL,
                messageDetail = "Serial not provided; selected device '${deviceIdentity(selectedDevice)}' is used."
            )
        }

        return DeviceSelectionResult.Selected(
            device = selectedDevice,
            reason = DeviceSelectionReason.FALLBACK_SELECTED_INVALID_SERIAL,
            messageDetail = "Serial '$serial' is invalid; fallback to selected device '${deviceIdentity(selectedDevice)}'."
        )
    }

    private fun isDeviceOnline(device: IDevice): Boolean {
        val adb = PlatformApi.toDeviceAdb(device)
        return adb?.isOnline ?: false
    }

    private fun deviceSerial(device: IDevice): String? {
        val adb = PlatformApi.toDeviceAdb(device)
        return adb?.serial
    }

    private fun deviceName(device: IDevice): String? {
        val adb = PlatformApi.toDeviceAdb(device)
        return adb?.displayName
    }

    private fun deviceIdentity(device: IDevice): String {
        return deviceSerial(device) ?: deviceName(device) ?: "unknown-device"
    }
}

sealed class DeviceSelectionResult {
    data class Selected(
        val device: IDevice,
        val reason: String,
        val messageDetail: String,
    ) : DeviceSelectionResult()

    data class NoDevice(
        val messageDetail: String,
    ) : DeviceSelectionResult()
}

object DeviceSelectionReason {
    const val BY_SERIAL = "BY_SERIAL"
    const val FALLBACK_SELECTED_MISSING_SERIAL = "FALLBACK_SELECTED_MISSING_SERIAL"
    const val FALLBACK_SELECTED_INVALID_SERIAL = "FALLBACK_SELECTED_INVALID_SERIAL"
}
