package com.sickworm.intellij.jugg.logger

import com.intellij.openapi.diagnostic.Attachment
import com.intellij.openapi.diagnostic.ExceptionWithAttachments
import com.intellij.openapi.util.text.StringUtil
import com.intellij.util.ExceptionUtil
import java.io.File
import java.util.*
import java.util.function.Function
import java.util.logging.*
import java.util.stream.Collectors
import java.util.stream.Stream

/**
 * Log to jugg/jugg.log
 */
class FileLoggerWrapper(
    private val logger: Logger,
    private val category: String,
): com.intellij.openapi.diagnostic.Logger() {

    companion object {

        private const val limit: Int = 2_000_000
        private const val count: Int = 5

        fun createLogger(dir: File): Logger {
            dir.mkdirs()
            return Logger.getLogger(dir.absolutePath).also {
                it.useParentHandlers = false
                it.level = Level.ALL
                if (it.handlers.isEmpty()) {
                    val loggerHandler = FileHandler(
                        dir.absolutePath + "/running_log.log",
                        limit, count, true)
                    loggerHandler.formatter = object : SimpleFormatter() {

                        private val format: String = "[%1\$tF %1\$tT] [%2$-7s] %3\$s %n"

                        override fun format(lr: LogRecord): String {
                            return String.format(format,
                                Date(lr.millis),
                                lr.level.name,
                                lr.message
                            );
                        }
                    }
                    it.addHandler(loggerHandler)
                }
            }
        }
    }

    init {
        logger.level = Level.ALL
    }

    override fun isTraceEnabled(): Boolean {
        return true
    }

    override fun trace(message: String) {
        if (isTraceEnabled) {
            logger.log(Level.ALL, message.withPrefix)
        }
    }

    override fun trace(t: Throwable?) {
        if (isTraceEnabled) {
            logger.log(Level.ALL, "".withPrefix, t)
        }
    }

    override fun isDebugEnabled(): Boolean {
        return true
    }

    override fun debug(message: String) {
        logger.log(Level.FINE, message.withPrefix)
    }

    override fun debug(t: Throwable?) {
        logger.log(Level.FINE, "".withPrefix, t)
    }

    override fun debug(message: String, t: Throwable?) {
        logger.log(Level.FINE, message.withPrefix, t)
    }

    override fun info(message: String) {
        logger.log(Level.INFO, message.withPrefix)
    }

    override fun info(message: String, t: Throwable?) {
        logger.log(Level.INFO, message.withPrefix, t)
    }

    override fun warn(message: String, t: Throwable?) {
        logger.log(Level.WARNING, message.withPrefix, t)
    }

    override fun error(message: String, t: Throwable?, vararg details: String) {
        val finalT = checkException(t)
        val finalMessage = message.withPrefix + attachmentsToString(t)
        printError(finalMessage, finalT, *details)
    }

    private val String.withPrefix get() = "[$category] $this"

    private fun printError(message: String, t: Throwable?, vararg details: String) {
        logger.log(Level.SEVERE, "ERROR: ${message.withPrefix}", t)
        if (details.isNotEmpty()) {
            logger.log(Level.SEVERE, "details: ")
            for (detail in details) {
                logger.log(Level.SEVERE, detail)
            }
        }
    }

    private fun attachmentsToString(t: Throwable?): String {
        if (t != null) {
            val attachments = ExceptionUtil.findCauseAndSuppressed(t, ExceptionWithAttachments::class.java)
                .stream()
                .flatMap { e: ExceptionWithAttachments ->
                    Stream.of(
                        *e.attachments
                    )
                }
                .collect(Collectors.toList())
            if (attachments.isNotEmpty()) {
                return "\n\nAttachments:\n" + StringUtil.join(attachments,
                    { attachmentStringFunction.apply(it) }, "\n----\n"
                )
            }
        }
        return ""
    }

    private val attachmentStringFunction = Function { attachment: Attachment ->
        """
            ${attachment.path}
            ${attachment.displayText}
            """.trimIndent()
    }

    override fun setLevel(level: org.apache.log4j.Level) {

    }
}