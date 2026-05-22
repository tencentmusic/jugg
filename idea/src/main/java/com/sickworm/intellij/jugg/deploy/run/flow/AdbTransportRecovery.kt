package com.sickworm.intellij.jugg.deploy.run.flow

import com.android.ddmlib.IDevice
import com.android.tools.deployer.AdbClient
import com.android.tools.idea.log.LogWrapper
import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.jugg.deploy.run.utils.AdbTransientOffline

/**
 * Waits until ADB transport is ready again after transient offline.
 */
interface IAdbTransportRecovery {
    fun waitUntilRecovered(device: IDevice, phase: String, logWait: (String) -> Unit): Boolean
}

class AdbTransportRecovery(
    private val logger: Logger,
) : IAdbTransportRecovery {

    override fun waitUntilRecovered(device: IDevice, phase: String, logWait: (String) -> Unit): Boolean {
        val adbLogger = LogWrapper(logger).apply {
            alwaysLogAsDebug(true)
            allowVerbose(true)
        }
        return AdbTransientOffline.waitForAdbTransport(
            serial = device.serialNumber,
            phase = phase,
            adb = AdbClient(device, adbLogger),
            isDeviceOnline = { device.isOnline },
            logWait = logWait,
        )
    }
}
