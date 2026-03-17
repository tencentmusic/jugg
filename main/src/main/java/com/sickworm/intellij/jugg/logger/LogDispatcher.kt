package com.sickworm.intellij.jugg.logger

import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.jugg.ide.logic.TestModeManager
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Dispatch log to [loggers].
 */
open class LogDispatcher(
    val instanceKey: String,
    loggersArg: List<Logger> = emptyList()
): Logger() {

    private val loggers: CopyOnWriteArrayList<Logger> = CopyOnWriteArrayList(loggersArg.toMutableList())

    @Synchronized
    fun listenProjectLog(logger: Logger) {
        if (!loggers.contains(logger)) {
            loggers.add(logger)
        }
    }

    @Synchronized
    fun stopListenProjectLog(logger: Logger) {
        if (loggers.contains(logger)) {
            loggers.remove(logger)
        }
    }

    @Synchronized
    fun resetLoggers(loggers: List<Logger>) {
        this.loggers.clear()
        this.loggers.addAll(loggers)
    }

    @Synchronized
    fun dispose() {
        loggers.clear()
    }

    override fun isTraceEnabled(): Boolean {
        return TestModeManager.isLogVerboseEnabled
    }

    override fun trace(message: String?) {
        if (isTraceEnabled) {
            super.trace(message)
        }
    }

    override fun trace(t: Throwable?) {
        if (isTraceEnabled) {
            super.trace(t)
        }
    }

    override fun isDebugEnabled(): Boolean {
        return true
    }

    override fun debug(message: String?) {
        loggers.forEach { it.debug(message) }
    }

    override fun debug(t: Throwable?) {
        loggers.forEach { it.debug(t) }
    }

    override fun debug(message: String?, t: Throwable?) {
        loggers.forEach { it.debug(message, t) }
    }

    override fun info(message: String?) {
        loggers.forEach { it.info(message) }
    }

    override fun info(message: String?, t: Throwable?) {
        loggers.forEach { it.info(message, t) }
    }

    override fun warn(message: String?, t: Throwable?) {
        loggers.forEach { it.warn(message, t) }
    }

    override fun error(message: String?, t: Throwable?, vararg details: String?) {
        loggers.forEach { it.error(message, t, *details) }
    }

    override fun setLevel(level: org.apache.log4j.Level) {
    }
}