package com.sickworm.intellij.jugg.logger

import java.util.logging.*

/**
 * Wrapper from [java.util.logging.Logger] to [com.intellij.openapi.diagnostic.Logger].
 */
class FileLoggerWrapper(
    private val logger: Logger,
    private val category: String,
): com.intellij.openapi.diagnostic.Logger() {

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
        printError(message, t, *details)
    }

    private val String.withPrefix get() = "[$category] $this"

    private fun printError(message: String, t: Throwable?, vararg details: String) {
        val finalMessage = message.withPrefix + attachmentsToString(t)
        logger.log(Level.SEVERE, finalMessage, t)
        if (details.isNotEmpty()) {
            logger.log(Level.SEVERE, "details: ")
            for (detail in details) {
                logger.log(Level.SEVERE, detail)
            }
        }
    }

    private fun attachmentsToString(t: Throwable?): String {
        return t.toString()
    }

    override fun setLevel(level: org.apache.log4j.Level) {

    }
}