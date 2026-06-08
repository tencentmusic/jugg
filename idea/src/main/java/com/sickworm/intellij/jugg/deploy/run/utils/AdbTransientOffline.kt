package com.sickworm.intellij.jugg.deploy.run.utils

import com.sickworm.intellij.jugg.deploy.AdbCliShellExecutor
import com.sickworm.intellij.jugg.deploy.AdbCmdHelper
import com.sickworm.intellij.jugg.deploy.IDeviceAdb
import java.io.IOException

internal object AdbTransientOffline {

    /** Max wait for ADB transport to recover after transient offline (shell, swap, install, deploy retry). */
    const val DEFAULT_WAIT_MILLIS = 3_000L

    const val DEFAULT_POLL_INTERVAL_MILLIS = 500L

    fun isOffline(throwable: Throwable?): Boolean {
        var current = throwable
        while (current != null) {
            if (isOfflineClass(current) || isOfflineMessage(current.message)) {
                return true
            }
            current = current.cause
        }
        return false
    }

    fun isOfflineMessage(message: String?): Boolean {
        if (message.isNullOrBlank()) {
            return false
        }
        val normalized = message.lowercase()
        val isAdbRejected = normalized.contains("adbcommandrejectedexception") && normalized.contains("offline")
        val isInvalidTagZero = normalized.contains("invalidprotocolbufferexception") &&
                normalized.contains("protocol message contained an invalid tag (zero)")
        return normalized.contains("device offline") || isAdbRejected || isInvalidTagZero
    }

    fun toException(phase: String, cause: Throwable): AdbTransientOfflineException {
        val detail = cause.message ?: cause::class.java.simpleName
        return AdbTransientOfflineException("ADB device offline during $phase: $detail", cause)
    }

    fun waitUntilReady(
        maxWaitMillis: Long = DEFAULT_WAIT_MILLIS,
        pollIntervalMillis: Long = DEFAULT_POLL_INTERVAL_MILLIS,
        isReady: () -> Boolean,
    ): Boolean {
        val deadline = System.currentTimeMillis() + maxWaitMillis
        while (System.currentTimeMillis() <= deadline) {
            if (isReady()) {
                return true
            }
            try {
                Thread.sleep(pollIntervalMillis)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
        }
        return false
    }

    fun waitForAdbTransport(
        phase: String,
        adb: IDeviceAdb,
        logWait: (String) -> Unit,
    ): Boolean {
        logWait("Device ${adb.serial} went offline during $phase, wait up to ${DEFAULT_WAIT_MILLIS}ms.")
        return waitUntilReady {
            adb.isAdbTransportReady()
        }
    }

    /**
     * True when adb CLI reports device + shell true, or ddmlib transport is ready.
     * CLI is checked first because [com.android.ddmlib.IDevice.isOnline] can lag after recovery.
     */
    internal fun isTransportRecovered(
        serial: String,
        isDeviceOnline: () -> Boolean,
        isDdmlibShellReady: () -> Boolean,
        isCliTransportReady: () -> Boolean = { isAdbCliTransportReady(serial) },
    ): Boolean {
        return isCliTransportReady() || (isDeviceOnline() && isDdmlibShellReady())
    }

    fun isAdbCliTransportReady(serial: String, adbBin: String = AdbCmdHelper.findAdbExecutablePath()): Boolean {
        return try {
            if (AdbCliShellExecutor.getState(adbBin, serial) != "device") {
                return false
            }
            AdbCliShellExecutor.exec(adbBin, serial, "true", timeoutMillis = 5_000L)
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun isOfflineClass(throwable: Throwable): Boolean {
        return throwable::class.java.simpleName == "AdbCommandRejectedException"
    }
}

internal class AdbTransientOfflineException(
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause)
