package com.sickworm.intellij.jugg.logger

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.lang.ref.WeakReference
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

        private var runningFileHandlers = mutableSetOf<WeakReference<FileHandler>>()
        private var latestCompileFileHandlers = mutableSetOf<WeakReference<FileHandler>>()

        fun isLatestCompileFileHandler(handler: Handler): Boolean {
            return latestCompileFileHandlers.any { it.get() == handler }
        }

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
                val loggerHandler = createRunningLogHandler(dir)
                runningFileHandlers.add(WeakReference(loggerHandler))
                it.addHandler(loggerHandler)

                val loggerHandler2 = createLatestCompileLogHandler(dir, false)
                latestCompileFileHandlers.add(WeakReference(loggerHandler2))
                it.addHandler(loggerHandler2)
            }
        }

        private fun createRunningLogHandler(dir: File): FileHandler {
            return createFileHandler(dir, "running.log", true)
        }

        private fun createLatestCompileLogHandler(dir: File, isClearLog: Boolean): FileHandler {
            return createFileHandler(dir, "latest_compile.log", !isClearLog)
        }

        private const val limit: Int = 10_000_000
        private const val count: Int = 1

        private fun createFileHandler(dir: File, name: String, isAppend: Boolean): FileHandler {
            val loggerHandler = FileHandler(
                dir.absolutePath + "/" + name,
                limit, count, isAppend)
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
    }

    fun resetLatestCompileLog() {
        logger.handlers.clone().forEach {
            if (isLatestCompileFileHandler(it)) {
                logger.removeHandler(it)
                (it as? FileHandler)?.close()
            }
        }
        val newHandler = createLatestCompileLogHandler(dir, true)
        latestCompileFileHandlers.add(WeakReference(newHandler))
        logger.addHandler(newHandler)
    }

}