package com.sickworm.intellij.jugg.mock

import com.intellij.openapi.diagnostic.DefaultLogger
import org.jetbrains.annotations.NonNls

val logger = StdLogger("JuggTest")

/**
 * Output log to [System.out].
 */
class StdLogger(category: String): DefaultLogger(category) {

    var isEnableDebug = true
    var isEnableInfo = true

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
        if (isEnableDebug) {
            println("[D] $message")
        }
    }

    override fun debug(t: Throwable?) {
        if (isEnableDebug) {
            dumpExceptionsToStderr("[D] ", t)
        }
    }

    override fun debug(@NonNls message: String?, t: Throwable?) {
        if (isEnableDebug) {
            dumpExceptionsToStderr("[D] $message", t)
        }

    }

    override fun info(message: String?) {
        if (isEnableInfo) {
            println("[I] $message")
        }
    }

    override fun info(message: String?, t: Throwable?) {
        if (isEnableInfo) {
            dumpExceptionsToStderr("[I] $message", t)
        }
    }

    override fun warn(message: String?, t: Throwable?) {
        println("[W] $message")
        t?.printStackTrace(System.err)
    }

    override fun error(message: String?, t: Throwable?, vararg details: String?) {
        println("[E] $message")
        t?.printStackTrace(System.err)
    }
}