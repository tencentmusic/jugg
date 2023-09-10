package com.sickworm.intellij.jugg.logger

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.text.SimpleDateFormat
import java.util.*
import java.util.logging.*


/**
 * Logger Logs to jugg/log
 */
class FileLogger(
    val dir: File,
    private var patternName: String = createPatternName(),
    val logger: Logger = createLogger(dir, patternName),
    ) {

    fun recreateIfDeleted() {
        if (!dir.exists()) {
            dir.mkdirs()
            resetLatestCompileLog()
            return
        }
        // assume that the log file is single
        val logFile = File(dir, patternName)
        if (!logFile.exists()) {
            resetLatestCompileLog()
        }
    }

    companion object {

        private const val LATEST_LOG_NAME = "compile_latest.log"
        private const val LAST_LATEST_LOG_NAME = "compile_latest-1.log"

        private fun createPatternName(): String {
            return "compile_" + SimpleDateFormat("yyyy-MM-dd_HH:mm:ss").format(Date()) + ".log"
        }

        private fun createLogger(dir: File, patterName: String): Logger {
            dir.mkdirs()
            return Logger.getLogger(dir.absolutePath).also {
                it.useParentHandlers = false
                it.level = Level.ALL
                if (it.handlers.isNotEmpty()) {
                    it.handlers.clone().forEach { handler ->
                        it.removeHandler(handler)
                        (handler as? FileHandler)?.close()
                    }
                }
                // yyyy-MM-dd_HH-mm-ss.log
                val loggerHandler = createFileHandler(dir, patterName)
                it.addHandler(loggerHandler)
                removeOldLogFiles(dir)
            }
        }


        private fun createFileHandler(dir: File, name: String): FileHandler {
            val loggerHandler = FileHandler(
                dir.absolutePath + "/" + name,
                0, 1, false)
            val formatter = object : SimpleFormatter() {

                private val format: String = "[%1\$tF %1\$tT] [%2$-7s] %3\$s%n"

                override fun format(lr: LogRecord): String {
                    val string = String.format(format,
                        Date(lr.millis),
                        lr.level.name,
                        lr.message
                    )
                    val outputStream = ByteArrayOutputStream()
                    lr.thrown?.printStackTrace(PrintStream(outputStream))
                    return string + outputStream.toString()
                }
            }
            loggerHandler.formatter = formatter

            // link file to compile_latest.log and compile_latest-1.log
            val latestLogFile = File(dir, LATEST_LOG_NAME)
            if (latestLogFile.exists()) {
                val lastLatestLogFile = File(dir, LAST_LATEST_LOG_NAME)
                if (lastLatestLogFile.exists()) {
                    lastLatestLogFile.delete()
                }
                val lastLatestPath = Files.readSymbolicLink(latestLogFile.toPath())
                Files.createSymbolicLink(lastLatestLogFile.toPath(), lastLatestPath)
                latestLogFile.delete()
            }
            Files.createSymbolicLink(latestLogFile.toPath(), Path.of(dir.absolutePath, name))

            return loggerHandler
        }

        private const val MAX_LOG_FILE_AMOUNT = 10

        private fun removeOldLogFiles(dir: File) {
            val files = dir.listFiles()
            var maxFileAmount = MAX_LOG_FILE_AMOUNT
            if (File(dir, LATEST_LOG_NAME).exists()) {
                maxFileAmount += 1
            }
            if (File(dir, LAST_LATEST_LOG_NAME).exists()) {
                maxFileAmount += 1
            }
            if (files != null && files.size > maxFileAmount) {
                files.sortBy { it.lastModified() }
                for (i in 0 until files.size - maxFileAmount) {
                    files[i].delete()
                }
            }
        }
    }

    fun resetLatestCompileLog() {
        logger.handlers.clone().forEach {
            logger.removeHandler(it)
            (it as? FileHandler)?.close()
        }
        patternName = createPatternName()
        val newHandler = createFileHandler(dir, patternName)
        logger.addHandler(newHandler)
        removeOldLogFiles(dir)
    }

    fun dispose() {
        logger.handlers.clone().forEach {
            logger.removeHandler(it)
            (it as? FileHandler)?.close()
        }
    }
}