package com.sickworm.intellij.jugg.logger

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.logging.Formatter
import java.util.logging.Level
import java.util.logging.LogRecord
import java.util.logging.Logger
import java.util.logging.SimpleFormatter

/**
 * Logger logs to jugg/log.
 */
class FileLogger(
    val dir: File,
    private val limitBytes: Int = DEFAULT_LIMIT_BYTES,
    private val fileCount: Int = DEFAULT_FILE_COUNT,
    private var patternName: String = createPatternName(),
    val logger: Logger = createLogger(dir, patternName, limitBytes, fileCount),
) {

    fun recreateIfDeleted() {
        if (!dir.exists()) {
            dir.mkdirs()
            resetLatestCompileLog()
            return
        }
        if (!currentMainLogFile().exists()) {
            resetLatestCompileLog()
        }
    }

    fun resetLatestCompileLog() {
        val previousMainLogFile = currentMainLogFile()
        closeHandlers()
        updateLastLatestLogFile(dir, previousMainLogFile)

        patternName = createPatternName()
        logger.addHandler(createFileHandler(dir, patternName, limitBytes, fileCount))
        removeOldLogFiles(dir)
    }

    fun dispose() {
        closeHandlers()
    }

    private fun currentMainLogFile(): File {
        return File(dir, patternName.replace("%g", "0"))
    }

    private fun closeHandlers() {
        logger.handlers.clone().forEach {
            logger.removeHandler(it)
            it.close()
        }
    }

    companion object {

        private const val LATEST_LOG_NAME = "compile_latest.log"
        private const val LAST_LATEST_LOG_NAME = "compile_latest-1.log"
        private const val DEFAULT_LIMIT_BYTES = 50 * 1024 * 1024
        private const val DEFAULT_FILE_COUNT = 2
        private const val MAX_LOG_FILE_AMOUNT = 10

        @JvmField
        var isCreateLastLogLinkFile: Boolean = true

        private fun createPatternName(): String {
            return "compile_" + SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date()) + ".%g.log"
        }

        private fun createLogger(dir: File, patternName: String, limitBytes: Int, fileCount: Int): Logger {
            dir.mkdirs()
            return Logger.getLogger(dir.absolutePath).also {
                it.useParentHandlers = false
                it.level = Level.ALL
                if (it.handlers.isNotEmpty()) {
                    it.handlers.clone().forEach { handler ->
                        it.removeHandler(handler)
                        handler.close()
                    }
                }
                it.addHandler(createFileHandler(dir, patternName, limitBytes, fileCount))
                removeOldLogFiles(dir)
            }
        }

        private fun createFileHandler(
            dir: File,
            patternName: String,
            limitBytes: Int,
            fileCount: Int,
        ): NoLockRotatingFileHandler {
            val mainLogFile = File(dir, patternName.replace("%g", "0"))
            val loggerHandler = NoLockRotatingFileHandler(
                pattern = File(dir, patternName).absolutePath,
                limitBytes = limitBytes,
                fileCount = fileCount,
                onActiveFileChanged = { updateLatestLogFile(dir, it) },
            )
            loggerHandler.level = Level.ALL
            loggerHandler.formatter = createFormatter()
            updateLatestLogFile(dir, mainLogFile)
            return loggerHandler
        }

        private fun createFormatter(): Formatter {
            return object : SimpleFormatter() {

                private val format: String = "[%1\$tF %1\$tT.%2\$03d] [%3$-7s] %4\$s%n"

                override fun format(lr: LogRecord): String {
                    val string = String.format(
                        Locale.US,
                        format,
                        Date(lr.millis),
                        lr.millis % 1000,
                        lr.level.name,
                        lr.message,
                    )
                    val outputStream = ByteArrayOutputStream()
                    lr.thrown?.printStackTrace(PrintStream(outputStream))
                    return string + outputStream.toString()
                }
            }
        }

        private fun updateLatestLogFile(dir: File, targetFile: File) {
            createBestEffortLink(File(dir, LATEST_LOG_NAME), targetFile)
        }

        private fun updateLastLatestLogFile(dir: File, targetFile: File) {
            if (!isCreateLastLogLinkFile) {
                Files.deleteIfExists(File(dir, LAST_LATEST_LOG_NAME).toPath())
                return
            }
            if (!targetFile.exists()) {
                return
            }
            createBestEffortLink(File(dir, LAST_LATEST_LOG_NAME), targetFile)
        }

        private fun createBestEffortLink(linkFile: File, targetFile: File) {
            try {
                Files.deleteIfExists(linkFile.toPath())
            } catch (_: Exception) {
                return
            }

            if (tryCreateSymbolicLink(linkFile, targetFile)) {
                return
            }
            tryCreateHardLink(linkFile, targetFile)
        }

        private fun tryCreateSymbolicLink(linkFile: File, targetFile: File): Boolean {
            return try {
                linkFile.parentFile?.mkdirs()
                val relativePath = relativeTarget(linkFile, targetFile)
                Files.createSymbolicLink(linkFile.toPath(), Path.of("./$relativePath"))
                true
            } catch (_: Exception) {
                false
            }
        }

        private fun tryCreateHardLink(linkFile: File, targetFile: File) {
            if (!targetFile.exists()) {
                return
            }
            try {
                Files.createLink(linkFile.toPath(), targetFile.toPath())
            } catch (_: Exception) {
            }
        }

        private fun relativeTarget(linkFile: File, targetFile: File): String {
            return linkFile.toPath().parent.relativize(targetFile.toPath()).toString()
        }

        private fun removeOldLogFiles(dir: File) {
            val files = dir.listFiles()
                ?.filter { it.name.startsWith("compile_") && it.name.endsWith(".log") }
                ?.filter { it.name != LATEST_LOG_NAME && it.name != LAST_LATEST_LOG_NAME }
                ?.sortedBy { it.lastModified() }
                .orEmpty()

            if (files.size <= MAX_LOG_FILE_AMOUNT) {
                return
            }

            files.take(files.size - MAX_LOG_FILE_AMOUNT).forEach { it.delete() }
        }
    }
}
