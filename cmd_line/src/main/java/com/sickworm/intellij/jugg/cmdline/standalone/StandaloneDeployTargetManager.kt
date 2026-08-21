package com.sickworm.intellij.jugg.cmdline.standalone

import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.jugg.apk.ApkInfo
import com.sickworm.intellij.jugg.deploy.AdbCmdHelper
import com.sickworm.intellij.jugg.deploy.IDeployTargetManager
import com.sickworm.intellij.jugg.deploy.api.IDevice
import com.sickworm.intellij.jugg.deploy.run.IDeployHost
import com.sickworm.intellij.jugg.deploy.run.StandaloneDeviceManager

/** Selects one deterministic online adb device and performs app lifecycle operations for standalone runs. */
class StandaloneDeployTargetManager(
    private val deviceManagerProvider: () -> StandaloneDeviceManager,
    private val environmentProvider: () -> IDeployHost,
    private val logger: Logger,
) : IDeployTargetManager {
    private var apks = emptyList<ApkInfo>()

    @Synchronized
    override fun setApks(apks: List<ApkInfo>) {
        this.apks = apks
    }

    @Synchronized
    override fun getApks(): List<ApkInfo> = apks

    override fun getSelectedDevices(): List<IDevice> {
        val connectedDevices = getConnectedDevices()
        val selectedSerial = System.getenv("ANDROID_SERIAL")?.trim().orEmpty()
        if (selectedSerial.isNotEmpty()) {
            val selected = connectedDevices.firstOrNull { it.serialNumber == selectedSerial }
                ?: throw IllegalStateException("Selected device $selectedSerial is not online.")
            return listOf(selected)
        }
        check(connectedDevices.size <= 1) {
            "Multiple devices are online. Pass CLI --serial or set ANDROID_SERIAL to select one device."
        }
        return connectedDevices
    }

    override fun getConnectedDevices(): List<IDevice> {
        return runCatching { deviceManagerProvider().devices().filter(IDevice::isOnline).sortedBy(IDevice::serialNumber) }
            .onFailure { logger.debug("Read standalone devices failed", it) }
            .getOrDefault(emptyList())
    }

    override fun startApp(device: IDevice): Boolean = runLifecycle(device) { helper ->
        helper.startDefaultApp(getPackageName(), getApks(), isRestart = false)
    }

    override fun restartApp(device: IDevice): Boolean = runLifecycle(device) { helper ->
        helper.startDefaultApp(getPackageName(), getApks())
    }

    override fun stopApp(device: IDevice): Boolean = runLifecycle(device) { it.stopApp(getPackageName()) }

    override fun isAppForeground(device: IDevice): Boolean = adb(device).isAppForeground(getPackageName())

    override fun isAppInstalled(device: IDevice): Boolean = adb(device).isAppInstalled(getPackageName())

    override fun getPackageName(): String = getApks().firstOrNull()?.applicationId
        ?: throw IllegalStateException("APK applicationId is unavailable")

    override fun dumpErrorLogs(): String = getSelectedDevices().firstOrNull()?.let { adb(it).dumpErrorLog() }.orEmpty()

    private fun runLifecycle(device: IDevice, action: (AdbCmdHelper) -> Unit): Boolean {
        return runCatching { action(adb(device)); true }
            .onFailure { logger.warn("Standalone app lifecycle operation failed", it) }
            .getOrDefault(false)
    }

    private fun adb(device: IDevice): AdbCmdHelper {
        return AdbCmdHelper(environmentProvider().createDeviceAdb(device, logger), logger)
    }
}
