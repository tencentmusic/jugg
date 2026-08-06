package com.sickworm.intellij.jugg.deploy

import com.sickworm.intellij.jugg.deploy.api.Deploy
import java.io.File

/**
 * IDeviceAdb abstracts device-level adb operations required by deploy workflows.
 */
interface IDeviceAdb {

    val displayName: String?

    val api: Int

    val serial: String

    val isOnline: Boolean

    fun execAdbShellCmd(cmd: String): String

    /**
     * Returns true when the adb transport can accept a lightweight shell command.
     */
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

    fun execAdbShellScript(cmd: String): String {
        return execAdbShellCmd(cmd)
    }

    /**
     * Runs a shell script once without transport-level retry or adb-cli fallback.
     * Use this for non-idempotent scripts that may mutate device state before returning.
     */
    fun execAdbShellScriptNoFallback(cmd: String): String {
        return execAdbShellScript(cmd)
    }

    /**
     * Runs a long-lived shell command and delivers output line-by-line to [lineConsumer].
     *
     * @param cmd         The shell command to execute (e.g. `am instrument -w -r ...`).
     * @param lineConsumer Called synchronously for each line received from the device.
     *                     Must not block; short processing is acceptable.
     * @param cancelSignal Returns true when the caller wants the command to be interrupted.
     *                     Implementations should stop the shell channel promptly when this returns true.
     * @return The exit code returned by the shell (implementation-defined; 0 typically means success).
     */
    fun execAdbShellCmdStreaming(
        cmd: String,
        lineConsumer: (String) -> Unit,
        cancelSignal: () -> Boolean,
    ): Int = throw UnsupportedOperationException("execAdbShellCmdStreaming not implemented")

    fun push(from: File, to: String): Boolean

    fun pull(from: String, to: File): Boolean

    fun getDefaultLaunchActivity(apkFile: File): String?

    /**
     * @return ARCH_UNKNOWN / ARCH_32_BIT / ARCH_64_BIT
     * @see [com.android.tools.deploy.proto.Deploy.Arch]
     */
    fun getArch(packageName: String): String

    fun getPids(packageName: String): List<Int> {
        return execAdbShellCmd("pidof $packageName")
            .trim()
            .split(Regex("\\s+"))
            .mapNotNull(String::toIntOrNull)
    }

    fun getDeployArch(packageName: String): Deploy.Arch {
        return runCatching { Deploy.Arch.valueOf(getArch(packageName)) }
            .getOrDefault(Deploy.Arch.ARCH_UNKNOWN)
    }

    /**
     * equals: adb shell "getprop | grep $name"
     */
    fun getProperty(name: String): String?
}
