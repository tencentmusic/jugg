package com.sickworm.intellij.jugg.mock

import com.intellij.openapi.diagnostic.DefaultLogger
import org.jetbrains.annotations.NonNls
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * Output log to [System.out].
 */
class StdLogger(private val category: String): DefaultLogger(category) {

    var isEnableDebug = true
    var isEnableInfo = true

    private fun timestamp(): String =
        LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss.SSS"))

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
            println("[${timestamp()}] [FINE   ] [$category] $message")
        }
    }

    override fun debug(t: Throwable?) {
        if (isEnableDebug) {
            dumpExceptionsToStderr("[${timestamp()}] [FINE   ] [$category]", t)
        }
    }

    override fun debug(@NonNls message: String?, t: Throwable?) {
        if (isEnableDebug) {
            dumpExceptionsToStderr("[${timestamp()}] [FINE   ] [$category] $message", t)
        }

    }

    override fun info(message: String?) {
        if (isEnableInfo) {
            println("[${timestamp()}] [INFO   ] [$category] $message")
        }
    }

    override fun info(message: String?, t: Throwable?) {
        if (isEnableInfo) {
            dumpExceptionsToStderr("[${timestamp()}] [INFO   ] [$category] $message", t)
        }
    }

    override fun warn(message: String?, t: Throwable?) {
        println("[${timestamp()}] [WARN   ] [$category] $message")
        t?.printStackTrace(System.err)
    }

    override fun error(message: String?, t: Throwable?, vararg details: String?) {
        println("[${timestamp()}] [ERROR  ] [$category] $message")
        t?.printStackTrace(System.err)
    }
}