package com.sickworm.intellij.jugg.deploy.direct

import com.sickworm.intellij.jugg.deploy.IDeviceAdb

/**
 * Resolves the host installer ABI folder name from device properties.
 * This mirrors AdbInstaller host-side ABI selection and does not require the app process.
 */
object InstallerDeviceAbiResolver {
    private val KNOWN_INSTALLER_ABIS = setOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64")

    fun resolve(adb: IDeviceAdb): String {
        listOf("ro.product.cpu.abi", "ro.product.cpu.abi2").forEach { property ->
            val abi = adb.getProperty(property)?.trim().orEmpty()
            if (abi in KNOWN_INSTALLER_ABIS) {
                return abi
            }
        }
        return "arm64-v8a"
    }
}
