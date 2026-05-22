package com.sickworm.intellij.jugg.deploy.run.utils

import com.android.tools.deployer.AdbClient
import java.io.IOException
import java.util.concurrent.TimeUnit

internal object AdbTransientOffline {

    const val DEFAULT_WAIT_MILLIS = 5_000L
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
        serial: String,
        phase: String,
        adb: AdbClient,
        isDeviceOnline: () -> Boolean = { true },
        logWait: (String) -> Unit,
    ): Boolean {
        logWait("Device $serial went offline during $phase, wait up to ${DEFAULT_WAIT_MILLIS}ms.")
        return waitUntilReady {
            isDeviceOnline() && isAdbShellReady(adb)
        }
    }

    private fun isAdbShellReady(adb: AdbClient): Boolean {
        return try {
            adb.shell(arrayOf("true"), null, 5L, TimeUnit.SECONDS)
            true
        } catch (e: Exception) {
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
