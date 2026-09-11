package com.sickworm.intellij.jugg.deploy

import com.android.tools.deploy.proto.Deploy
import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.jugg.logger.getInstance

/**
 * Resolves the target app ARM bitness from runtime, installed package, APK, and device evidence.
 */
class AppAbiResolver(
    private val adb: IDeviceAdb,
    loggerArg: Logger,
) {

    private val logger = loggerArg.getInstance("AppAbiResolver")

    fun resolve(
        packageName: String,
        processArch: Deploy.Arch,
        apkArch: String,
        use32BitAbi: Boolean,
        deviceAbi: String,
    ): Deploy.Arch {
        if (processArch != Deploy.Arch.ARCH_UNKNOWN) {
            return resolved(processArch, "running_process")
        }
        resolveInstalledPackage(packageName)?.let {
            return resolved(it, "installed_package")
        }
        if (use32BitAbi) {
            return resolved(Deploy.Arch.ARCH_32_BIT, "manifest_use32bitAbi")
        }
        parseArch(apkArch)?.let {
            return resolved(it, "apk_native_libraries")
        }
        parseAbi(deviceAbi)?.let {
            return resolved(it, "device_primary_abi")
        }
        return resolved(Deploy.Arch.ARCH_64_BIT, "default_64_bit")
    }

    private fun resolveInstalledPackage(packageName: String): Deploy.Arch? {
        val output = try {
            adb.execAdbShellCmd("dumpsys package $packageName")
        } catch (e: Exception) {
            logger.debug("Read installed package ABI failed for $packageName", e)
            return null
        }
        val abi = PRIMARY_CPU_ABI.find(output)?.groupValues?.get(1) ?: return null
        return parseAbi(abi)
    }

    private fun parseArch(arch: String): Deploy.Arch? {
        return when (arch) {
            Deploy.Arch.ARCH_32_BIT.name -> Deploy.Arch.ARCH_32_BIT
            Deploy.Arch.ARCH_64_BIT.name -> Deploy.Arch.ARCH_64_BIT
            else -> null
        }
    }

    private fun parseAbi(abi: String): Deploy.Arch? {
        return when (abi.trim()) {
            "armeabi", "armeabi-v7a" -> Deploy.Arch.ARCH_32_BIT
            "arm64-v8a" -> Deploy.Arch.ARCH_64_BIT
            else -> null
        }
    }

    private fun resolved(arch: Deploy.Arch, source: String): Deploy.Arch {
        logger.debug("Resolved app ABI: arch=$arch, source=$source")
        return arch
    }

    companion object {
        private val PRIMARY_CPU_ABI = Regex("primaryCpuAbi=([^\\s]+)")
    }
}
