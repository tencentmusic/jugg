package com.sickworm.intellij.jugg.deploy

import java.io.IOException

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

    private fun isOfflineClass(throwable: Throwable): Boolean {
        return throwable::class.java.simpleName == "AdbCommandRejectedException"
    }
}

internal class AdbTransientOfflineException(
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause)
