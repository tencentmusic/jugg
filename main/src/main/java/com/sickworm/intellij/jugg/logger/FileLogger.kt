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
        val logFile = File(dir, patternName.replace("%g", "0"))
        if (!logFile.exists()) {
            resetLatestCompileLog()
        }
    }

    companion object {

        private const val LATEST_LOG_NAME = "compile_latest.log"
        private const val LAST_LATEST_LOG_NAME = "compile_latest-1.log"

        var isCreateLastLogLinkFile: Boolean = true

        private fun createPatternName(): String {
            return "compile_" + SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date()) + ".%g.log"
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
            val limit = 50 * 1024 * 1024 // 100MB
            val loggerHandler = FileHandler(
                dir.absolutePath + "/" + name,
                limit, 2, false)
            val formatter = object : SimpleFormatter() {

                private val format: String = "[%1\$tF %1\$tT.%2\$03d] [%3$-7s] %4\$s%n"

                override fun format(lr: LogRecord): String {
                    val string = String.format(Locale.US, format,
                        Date(lr.millis),
                        lr.millis % 1000,
                        lr.level.name,
                        lr.message
                    )
                    val outputStream = ByteArrayOutputStream()
                    lr.thrown?.printStackTrace(PrintStream(outputStream))
                    return string + outputStream.toString()
                }
            }
            loggerHandler.formatter = formatter

            if (isCreateLastLogLinkFile) {
                createLastLogFiles(dir, name)
            }

            return loggerHandler
        }

        private fun createLastLogFiles(dir: File, name: String) {
            val latestLogFile = File(dir, LATEST_LOG_NAME)
            val lastLatestLogFile = File(dir, LAST_LATEST_LOG_NAME)
            try {
                // link file to compile_latest.log and compile_latest-1.log
                if (latestLogFile.exists()) {
                    if (lastLatestLogFile.exists()) {
                        lastLatestLogFile.delete()
                    }
                    val lastLatestPath = Files.readSymbolicLink(latestLogFile.toPath())
                    Files.createSymbolicLink(lastLatestLogFile.toPath(), lastLatestPath)
                    latestLogFile.delete()
                }
            } catch (e: Exception) {
                // robust
                com.intellij.openapi.diagnostic.Logger.getInstance("Jugg")
                    .warn("createFileHandler $lastLatestLogFile error", e)
            }

            try {
                latestLogFile.delete()
                val source = Path.of(dir.absolutePath, name.replace("%g", "0"))
                val link = latestLogFile.toPath()
                val relativePath = link.parent.relativize(source)
                Files.createSymbolicLink(link, Path.of("./$relativePath")) // "./" is required for finder in macOS to recognize the link
            } catch (e: Exception) {
                // robust
                com.intellij.openapi.diagnostic.Logger.getInstance("Jugg")
                    .warn("createFileHandler $latestLogFile error", e)
            }
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