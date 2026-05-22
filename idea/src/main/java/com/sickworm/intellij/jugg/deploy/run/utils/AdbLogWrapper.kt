package com.sickworm.intellij.jugg.deploy.run.utils

import com.android.tools.idea.log.LogWrapper
import com.intellij.openapi.diagnostic.Logger

/**
 * only print warning and error
 */
class AdbLogWrapper(val logger: Logger) : LogWrapper(logger) {

    var realErrorMessage: String? = null
        private set

    init {
        alwaysLogAsDebug(true)
        allowVerbose(true)
    }

    override fun info(msgFormat: String?, vararg args: Any?) {
        verbose(msgFormat, *args)
    }

    override fun warning(msgFormat: String?, vararg args: Any?) {
        super.warning(msgFormat, *args)
        checkMessage(msgFormat, *args)
    }

    override fun error(t: Throwable?, msgFormat: String?, vararg args: Any?) {
        super.error(t, msgFormat, *args)
        checkMessage(msgFormat, *args)
    }

    override fun verbose(msgFormat: String?, vararg args: Any?) {
        super.verbose(msgFormat, *args)
        checkMessage(msgFormat, *args)
    }

    private fun checkMessage(msgFormat: String?, vararg args: Any?) {
        msgFormat ?: return
        val message = String.format(msgFormat, *args)
        val installFailureReason = parseInstallFailureReason(message)
        if (installFailureReason != null) {
            realErrorMessage = installFailureReason
        } else if (message.contains("MessagePipeWrapper read() timeout")) {
            realErrorMessage = message
        } else if (message.contains("device") && message.contains("not found")) {
            realErrorMessage = message
        } else if (message.contains("overlay has no readable id file")) {
            // .overlay exists but no id file
            // Occurs when base APK is an incremental-embedded APK. Because incremental-embedded APK will create .overlay folder
            realErrorMessage = message
        }
    }

    private fun parseInstallFailureReason(message: String): String? {
        if (!message.contains("Installation Failure:")) {
            return null
        }
        val tail = message.substringAfter("Installation Failure:").trim()
        if (AdbTransientOffline.isOfflineMessage(tail)) {
            return tail.trim('\'', '"')
        }
        val prefixes = listOf(
            "Caused by: java.io.IOException:",
            "android.os.ParcelableException: java.io.IOException:",
            "java.io.IOException:",
        )
        return message.lineSequence()
            .map { it.trim().trim('\'', '"') }
            .firstNotNullOfOrNull { line ->
                prefixes.firstOrNull { line.startsWith(it) }
                    ?.let { line.removePrefix(it).trim() }
                    ?.takeIf { it.isNotBlank() }
            }
    }
}