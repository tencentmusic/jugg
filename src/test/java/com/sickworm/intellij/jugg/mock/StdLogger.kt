package com.sickworm.intellij.jugg.mock

import com.intellij.openapi.diagnostic.DefaultLogger
import org.jetbrains.annotations.NonNls

class StdLogger(category: String): DefaultLogger(category) {

    override fun debug(message: String?) {
        println(message)
    }

    override fun debug(t: Throwable?) {
        dumpExceptionsToStderr("", t)
    }

    override fun debug(@NonNls message: String?, t: Throwable?) {
        dumpExceptionsToStderr(message, t)
    }

    override fun info(message: String?) {
        println(message)
    }

    override fun info(message: String?, t: Throwable?) {
        dumpExceptionsToStderr(message, t)
    }

    override fun error(message: String?, t: Throwable?, vararg details: String?) {
        val finalT = checkException(t)
        val finalMessage = message + attachmentsToString(t)
        dumpExceptionsToStderr(finalMessage, finalT, *details)
    }
}