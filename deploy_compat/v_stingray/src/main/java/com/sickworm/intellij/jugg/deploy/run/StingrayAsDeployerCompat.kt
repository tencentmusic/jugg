package com.sickworm.intellij.jugg.deploy.run

import com.android.adblib.ConnectedDevice
import com.android.adblib.ConnectedDevicesTracker
import com.android.adblib.connectedDevicesTracker
import com.android.adblib.device
import com.android.ddmlib.IDevice
import com.android.tools.deployer.DexClass
import com.android.tools.deployer.DexComparator
import com.android.tools.deployer.common.AdbClient
import com.android.tools.deployer.common.DeviceHolder
import com.android.tools.idea.adblib.AdbLibApplicationService
import com.android.utils.ILogger
import com.intellij.openapi.progress.ProgressManager
import java.util.concurrent.TimeUnit

/**
 * Android Studio Stingray compatibility layer.
 */
class StingrayAsDeployerCompat : RabbitAsDeployerCompat() {

    override fun createAdbClient(device: IDevice, logger: ILogger): AdbClient {
        val session = AdbLibApplicationService.instance.session
        val tracker = session.connectedDevicesTracker
        val serial = device.serialNumber
        return AdbClient(DeviceHolder(waitForConnectedDevice(tracker, serial)), logger, session)
    }

    override fun toStudioChangedClasses(
        changes: com.sickworm.intellij.jugg.deploy.api.DexComparator.ChangedClasses,
    ): DexComparator.ChangedClasses {
        return DexComparator.ChangedClasses(
            toDexClasses(changes.newClasses),
            toDexClasses(changes.modifiedClasses),
        )
    }

    private fun toDexClasses(classes: List<com.sickworm.intellij.jugg.deploy.api.DexClass>): List<DexClass> {
        return classes.map { DexClass(it.name, it.checksum, it.code, null) }
    }

    private fun waitForConnectedDevice(tracker: ConnectedDevicesTracker, serial: String): ConnectedDevice {
        // The adblib tracker can start after ddmlib, and its coroutines belong to the IDE class loader.
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        do {
            ProgressManager.checkCanceled()
            tracker.device(serial)?.let { return it }
            try {
                Thread.sleep(50)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw IllegalStateException("Interrupted while waiting for ADB device $serial", e)
            }
        } while (System.nanoTime() - deadline < 0)
        throw IllegalStateException("ADB did not report the selected device $serial")
    }
}
