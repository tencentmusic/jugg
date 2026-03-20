package com.sickworm.intellij.jugg.logger

import java.io.File
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.concurrent.BlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.logging.ErrorManager
import java.util.logging.Handler
import java.util.logging.LogRecord

/**
 * Rotating file handler that writes through a dedicated writer thread.
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
    private val queue: BlockingQueue<QueueItem> = LinkedBlockingQueue()
    private val writerThread = Thread(::runWriterLoop, "JuggFileLogger").apply {
        isDaemon = true
    }
    private var currentFileSize = 0
    private var isClosed = false

    init {
        ensureActiveFileExists()
        currentFileSize = activeFileLength()
        onActiveFileChanged(files[0])
        writerThread.start()
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
            queue.put(LogEntry(content, shouldFlushImmediately(record)))
        }
    }

    override fun flush() {
        awaitQueueDrain(isClose = false)
    }

    override fun close() {
        awaitQueueDrain(isClose = true)
    }

    private fun shouldFlushImmediately(record: LogRecord): Boolean {
        return record.level.intValue() >= IMMEDIATE_FLUSH_LEVEL
    }

    private fun awaitQueueDrain(isClose: Boolean) {
        val completion = CountDownLatch(1)
        synchronized(stateLock) {
            if (isClosed && !isClose) {
                return
            }
            if (isClose) {
                if (isClosed) {
                    return
                }
                isClosed = true
                queue.put(CloseRequest(completion))
            } else {
                queue.put(FlushRequest(completion))
            }
        }
        waitForCompletion(completion)
        if (isClose) {
            joinWriterThread()
        }
    }

    private fun waitForCompletion(completion: CountDownLatch) {
        try {
            completion.await()
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            reportError("Wait for log writer failed", e, ErrorManager.CLOSE_FAILURE)
        }
    }

    private fun joinWriterThread() {
        try {
            writerThread.join()
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            reportError("Join log writer failed", e, ErrorManager.CLOSE_FAILURE)
        }
    }

    private fun runWriterLoop() {
        val batch = ArrayList<LogEntry>()
        var batchBytes = 0
        var keepRunning = true

        while (keepRunning) {
            val item = takeNextItem(batch.isNotEmpty())
            if (item == null) {
                batchBytes = flushBatch(batch)
                continue
            }

            when (item) {
                is LogEntry -> {
                    batch.add(item)
                    batchBytes += item.bytes.size
                    if (item.forceFlush || batchBytes >= FLUSH_THRESHOLD_BYTES) {
                        batchBytes = flushBatch(batch)
                    }
                }
                is FlushRequest -> {
                    batchBytes = flushBatch(batch)
                    item.completion.countDown()
                }
                is CloseRequest -> {
                    flushBatch(batch)
                    item.completion.countDown()
                    keepRunning = false
                }
            }
        }
    }

    private fun takeNextItem(hasPendingBatch: Boolean): QueueItem? {
        return try {
            if (hasPendingBatch) {
                queue.poll(FLUSH_DELAY_MS, TimeUnit.MILLISECONDS)
            } else {
                queue.take()
            }
        } catch (_: InterruptedException) {
            null
        }
    }

    private fun flushBatch(batch: MutableList<LogEntry>): Int {
        if (batch.isEmpty()) {
            return 0
        }
        return try {
            writeBatch(batch)
            0
        } catch (e: Exception) {
            reportError("Write log failed", e, ErrorManager.WRITE_FAILURE)
            0
        } finally {
            batch.clear()
        }
    }

    private fun writeBatch(batch: List<LogEntry>) {
        var outputStream: OutputStream? = null
        try {
            batch.forEach { entry ->
                outputStream = appendRecord(entry.bytes, outputStream)
            }
        } finally {
            outputStream?.close()
        }
    }

    private fun appendRecord(bytes: ByteArray, outputStream: OutputStream?): OutputStream {
        var activeStream = outputStream
        ensureActiveFileExists()

        if (isRotationNeeded() || shouldRotateBeforeWrite(bytes.size)) {
            activeStream?.close()
            activeStream = null
            rotateFiles()
        }

        if (activeStream == null) {
            activeStream = openActiveOutputStream()
        }
        activeStream.write(bytes)
        currentFileSize += bytes.size
        return activeStream
    }

    private fun openActiveOutputStream(): OutputStream {
        return Files.newOutputStream(files[0].toPath(), StandardOpenOption.CREATE, StandardOpenOption.APPEND)
    }

    private fun shouldRotateBeforeWrite(recordSize: Int): Boolean {
        if (limitBytes <= 0) {
            return false
        }
        return currentFileSize > 0 && currentFileSize + recordSize > limitBytes
    }

    private fun isRotationNeeded(): Boolean {
        return limitBytes > 0 && currentFileSize >= limitBytes
    }

    private fun rotateFiles() {
        for (i in fileCount - 2 downTo 0) {
            if (!files[i].exists()) {
                continue
            }
            Files.move(files[i].toPath(), files[i + 1].toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
        ensureActiveFileExists()
        currentFileSize = activeFileLength()
        onActiveFileChanged(files[0])
    }

    private fun ensureActiveFileExists() {
        files[0].parentFile?.mkdirs()
        if (!files[0].exists()) {
            Files.write(files[0].toPath(), byteArrayOf(), StandardOpenOption.CREATE)
            currentFileSize = 0
        }
    }

    private fun activeFileLength(): Int {
        return files[0].length().coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    companion object {
        private const val FLUSH_DELAY_MS = 100L
        private const val FLUSH_THRESHOLD_BYTES = 64 * 1024
        private const val IMMEDIATE_FLUSH_LEVEL = 900
    }

    private sealed interface QueueItem

    private data class LogEntry(
        val bytes: ByteArray,
        val forceFlush: Boolean,
    ) : QueueItem

    private data class FlushRequest(
        val completion: CountDownLatch,
    ) : QueueItem

    private data class CloseRequest(
        val completion: CountDownLatch,
    ) : QueueItem
}
