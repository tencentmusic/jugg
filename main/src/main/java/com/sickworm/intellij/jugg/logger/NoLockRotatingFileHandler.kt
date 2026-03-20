package com.sickworm.intellij.jugg.logger

import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.logging.ErrorManager
import java.util.logging.Handler
import java.util.logging.LogRecord

/**
 * Rotating file handler that batches writes but does not hold file handles between flushes.
 */
class NoLockRotatingFileHandler(
    pattern: String,
    private val limitBytes: Int,
    private val fileCount: Int,
    private val onActiveFileChanged: (File) -> Unit = {},
) : Handler() {

    private val files: Array<File> = Array(fileCount) { generation ->
        File(pattern.replace("%g", generation.toString()))
    }
    private val stateLock = Any()
    private val pendingRecords = ArrayDeque<ByteArray>()
    private val executor = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "JuggFileLogger").apply { isDaemon = true }
    }

    private var scheduledFlush: ScheduledFuture<*>? = null
    private var isClosed = false
    private var pendingBytesSize = 0

    init {
        ensureActiveFileExists()
        onActiveFileChanged(files[0])
    }

    override fun publish(record: LogRecord?) {
        if (record == null || !isLoggable(record)) {
            return
        }

        val content = try {
            (formatter ?: return).format(record).toByteArray(StandardCharsets.UTF_8)
        } catch (e: Exception) {
            reportError(record.message, e, ErrorManager.FORMAT_FAILURE)
            return
        }

        synchronized(stateLock) {
            if (isClosed) {
                return
            }

            pendingRecords.addLast(content)
            pendingBytesSize += content.size
            if (shouldFlushImmediately(record)) {
                cancelScheduledFlushLocked()
                flushPendingLocked()
            } else if (scheduledFlush == null) {
                scheduledFlush = executor.schedule(::flushSafely, FLUSH_DELAY_MS, TimeUnit.MILLISECONDS)
            }
        }
    }

    override fun flush() {
        synchronized(stateLock) {
            cancelScheduledFlushLocked()
            flushPendingLocked()
        }
    }

    override fun close() {
        synchronized(stateLock) {
            if (isClosed) {
                return
            }

            cancelScheduledFlushLocked()
            flushPendingLocked()
            isClosed = true
        }
        executor.shutdown()
    }

    private fun shouldFlushImmediately(record: LogRecord): Boolean {
        return pendingBytesSize >= FLUSH_THRESHOLD_BYTES || record.level.intValue() >= IMMEDIATE_FLUSH_LEVEL
    }

    private fun flushSafely() {
        synchronized(stateLock) {
            scheduledFlush = null
            flushPendingLocked()
        }
    }

    private fun flushPendingLocked() {
        if (pendingRecords.isEmpty()) {
            return
        }
        val records = ArrayList(pendingRecords)
        pendingRecords.clear()
        pendingBytesSize = 0
        try {
            records.forEach(::appendRecordWithRotation)
        } catch (e: Exception) {
            reportError("Write log failed", e, ErrorManager.WRITE_FAILURE)
        }
    }

    private fun appendRecordWithRotation(bytes: ByteArray) {
        ensureActiveFileExists()

        if (isRotationNeeded() || shouldRotateBeforeWrite(bytes.size)) {
            rotateFiles()
        }

        Files.newOutputStream(files[0].toPath(), StandardOpenOption.CREATE, StandardOpenOption.APPEND).use {
            it.write(bytes)
        }
    }

    private fun shouldRotateBeforeWrite(recordSize: Int): Boolean {
        if (limitBytes <= 0) {
            return false
        }
        val currentLength = files[0].length().coerceAtMost(limitBytes.toLong()).toInt()
        return currentLength > 0 && currentLength + recordSize > limitBytes
    }

    private fun isRotationNeeded(): Boolean {
        return limitBytes > 0 && files[0].length() >= limitBytes
    }

    private fun rotateFiles() {
        for (i in fileCount - 2 downTo 0) {
            if (!files[i].exists()) {
                continue
            }
            Files.move(files[i].toPath(), files[i + 1].toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
        ensureActiveFileExists()
        onActiveFileChanged(files[0])
    }

    private fun ensureActiveFileExists() {
        files[0].parentFile?.mkdirs()
        if (!files[0].exists()) {
            Files.write(files[0].toPath(), byteArrayOf(), StandardOpenOption.CREATE)
        }
    }

    private fun cancelScheduledFlushLocked() {
        scheduledFlush?.cancel(false)
        scheduledFlush = null
    }

    companion object {
        private const val FLUSH_DELAY_MS = 25L
        private const val FLUSH_THRESHOLD_BYTES = 16 * 1024
        private const val IMMEDIATE_FLUSH_LEVEL = 900
    }
}
