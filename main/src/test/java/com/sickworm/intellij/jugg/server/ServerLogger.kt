package com.sickworm.intellij.jugg.server

import com.intellij.openapi.diagnostic.DefaultLogger
import org.jetbrains.annotations.NonNls

/**
 * logger for [TestServer].
 */
class ServerLogger: DefaultLogger("Server") {

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
        printlnWithTag("[D] $message")
    }

    override fun debug(t: Throwable?) {
        dumpExceptionsToStderr("[D] ", t)
    }

    override fun debug(@NonNls message: String?, t: Throwable?) {
        dumpExceptionsToStderr("[D] $message", t)
    }

    override fun info(message: String?) {
        printlnWithTag("[I] $message")
    }

    override fun info(message: String?, t: Throwable?) {
        dumpExceptionsToStderr("[I] $message", t)
    }

    override fun warn(message: String?, t: Throwable?) {
        printlnWithTag("[W]: $message")
        t?.printStackTrace(System.err)
    }

    override fun error(message: String?, t: Throwable?, vararg details: String?) {
        val finalMessage = "[E] " + message + attachmentsToString(t)
        dumpExceptionsToStderr(finalMessage, t, *details)
    }

    private fun printlnWithTag(message: String) {
        println("[SERVER] $message")
    }
}