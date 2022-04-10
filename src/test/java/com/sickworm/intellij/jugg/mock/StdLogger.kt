package com.sickworm.intellij.jugg.mock

import com.intellij.openapi.diagnostic.DefaultLogger
import org.jetbrains.annotations.NonNls

/**
 * Output log to [System.out].
 */
class StdLogger(category: String): DefaultLogger(category) {

    override fun isTraceEnabled(): Boolean {
        return false
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
        println("[D] $message")
    }

    override fun debug(t: Throwable?) {
        dumpExceptionsToStderr("[D] ", t)
    }

    override fun debug(@NonNls message: String?, t: Throwable?) {
        dumpExceptionsToStderr("[D] $message", t)
    }

    override fun info(message: String?) {
        println("[I] $message")
    }

    override fun info(message: String?, t: Throwable?) {
        dumpExceptionsToStderr("[I] $message", t)
    }

    override fun warn(message: String?, t: Throwable?) {
        // no need for logging warn, JuggLogger will print it via System.err.out
    }

    override fun error(message: String?, t: Throwable?, vararg details: String?) {
        // no need for logging error, JuggLogger will print it via System.err.out
    }
}