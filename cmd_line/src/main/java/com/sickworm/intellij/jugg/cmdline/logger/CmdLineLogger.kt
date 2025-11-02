package com.sickworm.intellij.jugg.cmdline.logger

import com.intellij.openapi.diagnostic.DefaultLogger
import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.jugg.logger.JuggLogger
import org.apache.log4j.Level
import org.jetbrains.annotations.NonNls
import java.io.File

object CmdLineLogger {

    lateinit var logger: Logger

    val stdLogger = object : DefaultLogger("cmd") {
        var logLevel = Level.INFO

        override fun debug(message: String?) {
            if (logLevel.isGreaterOrEqual(Level.DEBUG)) {
                println("[D] $message")
            }
        }

        override fun debug(t: Throwable?) {
            if (logLevel.isGreaterOrEqual(Level.DEBUG)) {
                dumpExceptionsToStderr("[D] ", t)
            }
        }

        override fun debug(@NonNls message: String?, t: Throwable?) {
            if (logLevel.isGreaterOrEqual(Level.DEBUG)) {
                dumpExceptionsToStderr("[D] $message", t)
            }
        }

        override fun info(message: String?) {
            if (logLevel.isGreaterOrEqual(Level.INFO)) {
                println("[I] $message")
            }
        }

        override fun info(message: String?, t: Throwable?) {
            if (logLevel.isGreaterOrEqual(Level.INFO)) {
                dumpExceptionsToStderr("[I] $message", t)
            }
        }

        override fun warn(message: String?, t: Throwable?) {
            if (logLevel.isGreaterOrEqual(Level.WARN)) {
                System.err.println("[W] $message")
                t?.printStackTrace(System.err)
            }
        }

        override fun error(message: String?, t: Throwable?, vararg details: String?) {
            if (logLevel.isGreaterOrEqual(Level.ERROR)) {
                System.err.println("[E] $message")
                t?.printStackTrace(System.err)
            }
        }
    }

    fun init(logDir: File, level: Level) {
        val instanceKey = "cmd"
        JuggLogger.register(instanceKey, logDir)
        JuggLogger.listenProjectLog(instanceKey, stdLogger)
        logger = JuggLogger.getInstance(instanceKey, "cmd")
        stdLogger.setLevel(level)
    }
}