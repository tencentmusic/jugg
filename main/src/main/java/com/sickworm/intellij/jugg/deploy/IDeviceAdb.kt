package com.sickworm.intellij.jugg.deploy

import java.io.File

/**
 * IDeviceAdb abstracts device-level adb operations required by deploy workflows.
 */
interface IDeviceAdb {

    val displayName: String?

    val api: Int

    val serial: String
        get() = displayName.orEmpty()

    val isOnline: Boolean
        get() = true

    fun execAdbShellCmd(cmd: String): String

    fun execAdbShellScript(cmd: String): String {
        return execAdbShellCmd(cmd)
    }

    fun isAdbTransportReady(): Boolean {
        if (!isOnline) {
            return false
        }
        return try {
            execAdbShellCmd("true")
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Runs a long-lived shell command and delivers output line-by-line to [lineConsumer].
     */
    fun execAdbShellCmdStreaming(
        cmd: String,
        lineConsumer: (String) -> Unit,
        cancelSignal: () -> Boolean,
    ): Int = throw UnsupportedOperationException("execAdbShellCmdStreaming not implemented")

    fun push(from: File, to: String): Boolean

    fun pull(from: String, to: File): Boolean {
        return false
    }

    fun getDefaultLaunchActivity(apkFile: File): String?

    /**
     * @return ARCH_UNKNOWN / ARCH_32_BIT / ARCH_64_BIT
     * @see [com.android.tools.deploy.proto.Deploy.Arch]
     */
    fun getArch(packageName: String): String

    /**
     * equals: adb shell "getprop | grep $name"
     */
    fun getProperty(name: String): String?
}
