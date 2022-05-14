package com.android.tools.idea.run.tasks

import com.android.tools.idea.log.LogWrapper
import com.intellij.openapi.diagnostic.Logger
import org.apache.log4j.Level

/**
 * Always log as trace.
 */
class TraceLogger(private val logger: Logger): LogWrapper(object : Logger() {

    override fun isDebugEnabled(): Boolean {
        return true
    }

    override fun debug(message: String?) {
        logger.trace(message)
    }

    override fun debug(t: Throwable?) {
        logger.trace(t)
    }

    override fun debug(message: String?, t: Throwable?) {
        logger.trace(message)
        logger.trace(t)
    }

    override fun info(message: String?) {
    }

    override fun info(message: String?, t: Throwable?) {
    }

    override fun warn(message: String?, t: Throwable?) {
    }

    override fun error(message: String?, t: Throwable?, vararg details: String?) {
    }

    override fun setLevel(level: Level) {
    }

}) {
    init {
        alwaysLogAsDebug(true)
    }
}