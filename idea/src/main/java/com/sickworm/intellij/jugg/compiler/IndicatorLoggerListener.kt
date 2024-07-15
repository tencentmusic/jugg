package com.sickworm.intellij.jugg.compiler

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProgressIndicator
import org.apache.log4j.Level

class IndicatorLoggerListener(private val indicator: ProgressIndicator) : Logger() {
    override fun isDebugEnabled(): Boolean {
        return true
    }

    override fun debug(message: String?) {
        if (message == null) {
            return
        }
        if (message.startsWith("Compiling")) {
            indicator.text = message
        }
    }

    override fun debug(t: Throwable?) {
    }

    override fun debug(message: String?, t: Throwable?) {
    }

    override fun info(message: String?) {
    }

    override fun info(message: String?, t: Throwable?) {
    }

    override fun warn(message: String?, t: Throwable?) {
    }

    override fun error(message: String?, t: Throwable?, vararg details: String?) {
    }

    @Suppress("UnstableApiUsage")
    @Deprecated("Deprecated in Java")
    override fun setLevel(level: Level) {
    }
}