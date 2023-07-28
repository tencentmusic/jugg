package com.sickworm.intellij.jugg.logger

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.text.SimpleDateFormat
import java.util.*
import java.util.logging.*

/**
 * Logger Logs to jugg/log
 */
class FileLogger(
    val dir: File,
    val logger: Logger = createLogger(dir),
    ) {

    companion object {

        private fun createLogger(dir: File): Logger {
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
                val loggerHandler = createLatestCompileLogHandler(dir)
                it.addHandler(loggerHandler)
                removeOldLogFiles(dir)
            }
        }

        private fun createLatestCompileLogHandler(dir: File): FileHandler {
            // yyyy-MM-dd_HH-mm-ss.log
            val name = "compile_" + SimpleDateFormat("yyyy-MM-dd_HH:mm:ss").format(Date()) + ".log"
            return createFileHandler(dir, name)
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
            return loggerHandler
        }

        private const val maxLogFileSize = 10

        private fun removeOldLogFiles(dir: File) {
            val files = dir.listFiles()
            if (files != null && files.size > maxLogFileSize) {
                files.sortBy { it.lastModified() }
                for (i in 0 until files.size - maxLogFileSize) {
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
        val newHandler = createLatestCompileLogHandler(dir)
        logger.addHandler(newHandler)
        removeOldLogFiles(dir)
    }
}