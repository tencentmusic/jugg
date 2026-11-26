package com.sickworm.intellij.jugg.deploy

import com.android.ddmlib.IDevice as StudioDevice
import com.sickworm.intellij.jugg.deploy.api.IDevice
import com.sickworm.intellij.jugg.deploy.api.IRuntimeDevice

/** Unwraps one Android Studio runtime device only inside the IDEA ADB boundary. */
internal fun IDevice.toStudioDevice(): StudioDevice {
    return ((this as? IRuntimeDevice)?.runtimeDevice as? StudioDevice)
        ?: throw IllegalArgumentException("Device does not belong to the current Android Studio runtime")
}
