package com.sickworm.intellij.jugg.deploy.run.flow

import com.android.ddmlib.IDevice
import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.jugg.deploy.IdeaDeviceAdb
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
        return AdbTransientOffline.waitForAdbTransport(
            phase = phase,
            adb = IdeaDeviceAdb(device, logger),
            logWait = logWait,
        )
    }
}
