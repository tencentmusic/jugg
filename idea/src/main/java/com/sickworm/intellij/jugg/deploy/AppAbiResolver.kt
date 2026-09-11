package com.sickworm.intellij.jugg.deploy

import com.android.tools.deploy.proto.Deploy
import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.jugg.logger.getInstance

/**
 * Resolves the target app ARM bitness from runtime, local APK, installed package, and device evidence.
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
    ): Deploy.Arch = resolveDetailed(
        packageName = packageName,
        processArch = processArch,
        apkArch = apkArch,
        use32BitAbi = use32BitAbi,
        deviceAbi = deviceAbi,
    ).arch

    /** Resolves the ABI and marks whether the result is safe to persist after package-query failures. */
    internal fun resolveDetailed(
        packageName: String,
        processArch: Deploy.Arch,
        apkArch: String,
        use32BitAbi: Boolean,
        deviceAbi: String,
    ): Resolution {
        if (processArch != Deploy.Arch.ARCH_UNKNOWN) {
            return resolved(processArch, "running_process")
        }
        if (use32BitAbi) {
            return resolved(Deploy.Arch.ARCH_32_BIT, "manifest_use32bitAbi")
        }
        parseArch(apkArch)?.let {
            return resolved(it, "apk_native_libraries")
        }
        val installedPackage = resolveInstalledPackage(packageName)
        installedPackage.arch?.let {
            return resolved(it, "installed_package")
        }
        parseAbi(deviceAbi)?.let {
            return resolved(it, "device_primary_abi", installedPackage.querySucceeded)
        }
        return resolved(Deploy.Arch.ARCH_64_BIT, "default_64_bit", installedPackage.querySucceeded)
    }

    private fun resolveInstalledPackage(packageName: String): InstalledPackageResolution {
        val startNanos = System.nanoTime()
        val output = try {
            adb.execAdbShellCmd("dumpsys package $packageName")
        } catch (e: Exception) {
            logger.debug("Read installed package ABI failed: packageName=$packageName" +
                    ", cost=${elapsedMillis(startNanos)}ms", e)
            return InstalledPackageResolution(null, false)
        }
        val abi = PRIMARY_CPU_ABI.find(output)?.groupValues?.get(1)
        val arch = abi?.let(::parseAbi)
        logger.debug("Read installed package ABI: packageName=$packageName, abi=$abi" +
                ", arch=$arch, cost=${elapsedMillis(startNanos)}ms")
        return InstalledPackageResolution(arch, true)
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

    private fun resolved(arch: Deploy.Arch, source: String, cacheable: Boolean = true): Resolution {
        logger.debug("Resolved app ABI: arch=$arch, source=$source")
        return Resolution(arch, source, cacheable)
    }

    private fun elapsedMillis(startNanos: Long): Long = (System.nanoTime() - startNanos) / 1_000_000

    internal data class Resolution(
        val arch: Deploy.Arch,
        val source: String,
        val cacheable: Boolean,
    )

    private data class InstalledPackageResolution(
        val arch: Deploy.Arch?,
        val querySucceeded: Boolean,
    )

    companion object {
        private val PRIMARY_CPU_ABI = Regex("primaryCpuAbi=([^\\s]+)")
    }
}
