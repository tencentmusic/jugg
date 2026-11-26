package com.sickworm.intellij.jugg.logger

import java.io.OutputStream
import java.io.PrintStream
import java.nio.charset.StandardCharsets
import java.util.logging.Level
import java.util.concurrent.ConcurrentHashMap

/** Converts process output into bounded Jugg file-log records. */
class LoggerPrintStream(private val logger: java.util.logging.Logger) : PrintStream(object : OutputStream() {
    override fun write(value: Int) = Unit
}) {
    private val buffer = StringBuilder()
    private val lastPublishedAt = ConcurrentHashMap<String, Long>()
    private val suppressed = ConcurrentHashMap<String, Int>()
    private var adbFailureActive = false

    override fun write(byteArray: ByteArray, offset: Int, length: Int) {
        buffer.append(String(byteArray, offset, length, StandardCharsets.UTF_8))
        drain(false)
    }

    override fun write(value: Int) {
        buffer.append(value.toChar())
        drain(false)
    }

    override fun flush() {
        drain(true)
    }

    override fun close() {
        flush()
    }

    private fun drain(flush: Boolean) {
        var newline = buffer.indexOf("\n")
        while (newline >= 0) {
            publish(buffer.substring(0, newline).trimEnd('\r'))
            buffer.delete(0, newline + 1)
            newline = buffer.indexOf("\n")
        }
        if (flush && buffer.isNotEmpty()) {
            publish(buffer.toString())
            buffer.clear()
        }
    }

    private fun publish(message: String) {
        if (message.isEmpty()) return
        val now = System.currentTimeMillis()
        val isAdbTrackingFailure = message.contains("DeviceMonitor") ||
                message.contains("Tracking request failed") || message.contains("connection reset")
        if (!isAdbTrackingFailure) {
            if (adbFailureActive) {
                logger.log(Level.INFO, "ADB connection recovered")
                adbFailureActive = false
            }
            logger.log(Level.INFO, message)
            return
        }
        adbFailureActive = true
        val last = lastPublishedAt[message]
        if (last == null || now - last >= 5_000) {
            val count = suppressed.remove(message) ?: 0
            val suffix = if (count == 0) "" else " (suppressed $count repeats)"
            logger.log(Level.WARNING, message + suffix)
            lastPublishedAt[message] = now
        } else {
            suppressed.merge(message, 1, Int::plus)
        }
    }
}
