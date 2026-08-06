package com.sickworm.intellij.jugg.deploy.run.utils

import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.jugg.deploy.api.ILogger

/** Adapts deployer logs to Jugg logging while retaining actionable transport errors. */
class AdbLogWrapper(val logger: Logger) : ILogger {
    var realErrorMessage: String? = null
        private set

    override fun error(t: Throwable?, msgFormat: String?, vararg args: Any?) {
        val message = format(msgFormat, args)
        logger.debug(message, t)
        checkMessage(message)
    }

    override fun warning(msgFormat: String?, vararg args: Any?) {
        val message = format(msgFormat, args)
        logger.debug(message)
        checkMessage(message)
    }

    override fun info(msgFormat: String?, vararg args: Any?) = verbose(msgFormat, *args)

    override fun verbose(msgFormat: String?, vararg args: Any?) {
        val message = format(msgFormat, args)
        logger.debug(message)
        checkMessage(message)
    }

    private fun format(msgFormat: String?, args: Array<out Any?>): String {
        return msgFormat?.let { runCatching { String.format(it, *args) }.getOrDefault(it) }.orEmpty()
    }

    private fun checkMessage(message: String) {
        val installFailureReason = parseInstallFailureReason(message)
        realErrorMessage = when {
            installFailureReason != null -> installFailureReason
            message.contains("MessagePipeWrapper read() timeout") -> message
            message.contains("device") && message.contains("not found") -> message
            message.contains("overlay has no readable id file") -> message
            else -> realErrorMessage
        }
    }

    private fun parseInstallFailureReason(message: String): String? {
        if (!message.contains("Installation Failure:")) return null
        val tail = message.substringAfter("Installation Failure:").trim()
        if (AdbTransientOffline.isOfflineMessage(tail)) return tail.trim('\'', '"')
        val prefixes = listOf("Caused by: java.io.IOException:",
            "android.os.ParcelableException: java.io.IOException:", "java.io.IOException:")
        return message.lineSequence().map { it.trim().trim('\'', '"') }.firstNotNullOfOrNull { line ->
            prefixes.firstOrNull { line.startsWith(it) }?.let { line.removePrefix(it).trim() }?.takeIf(String::isNotBlank)
        }
    }
}
