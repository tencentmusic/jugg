package com.sickworm.intellij.jugg.cmdline.logger

import com.intellij.openapi.diagnostic.DefaultLogger
import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.jugg.logger.FileLogger
import com.sickworm.intellij.jugg.logger.JuggLogger
import org.apache.log4j.Level
import org.jetbrains.annotations.NonNls
import java.io.File

object CmdLineLogger {

    @Suppress("OVERRIDE_DEPRECATION")
    val stdLogger = object : DefaultLogger("cmd") {
        var logLevel = Level.DEBUG

        override fun setLevel(level: Level) {
            logLevel = level
        }

        override fun debug(message: String?) {
            if (Level.DEBUG.isGreaterOrEqual(logLevel)) {
                println("[D] $message")
            }
        }

        override fun debug(t: Throwable?) {
            if (Level.DEBUG.isGreaterOrEqual(logLevel)) {
                dumpExceptionsToStderr("[D] ", t)
            }
        }

        override fun debug(@NonNls message: String?, t: Throwable?) {
            if (Level.DEBUG.isGreaterOrEqual(logLevel)) {
                dumpExceptionsToStderr("[D] $message", t)
            }
        }

        override fun info(message: String?) {
            if (Level.INFO.isGreaterOrEqual(logLevel)) {
                println("$message")
            }
        }

        override fun info(message: String?, t: Throwable?) {
            if (Level.INFO.isGreaterOrEqual(logLevel)) {
                dumpExceptionsToStderr("$message", t)
            }
        }

        override fun warn(message: String?, t: Throwable?) {
            if (Level.WARN.isGreaterOrEqual(logLevel)) {
                System.err.println("[W] $message")
                t?.printStackTrace(System.err)
            }
        }

        override fun error(message: String?, t: Throwable?, vararg details: String?) {
            if (Level.ERROR.isGreaterOrEqual(logLevel)) {
                System.err.println("[E] $message")
                t?.printStackTrace(System.err)
            }
        }
    }

    @Suppress("UnnecessaryVariable")
    fun init(name: String, logDir: File, level: Level): Logger {
        stdLogger.setLevel(level)
        FileLogger.isCreateLastLogLinkFile = false

        val instanceKey = name
        JuggLogger.register(instanceKey, logDir)
        JuggLogger.listenProjectLog(instanceKey, stdLogger)
        return JuggLogger.getInstance(instanceKey, name)
    }

    fun release(name: String) {
        JuggLogger.unregister(name)
    }
}
