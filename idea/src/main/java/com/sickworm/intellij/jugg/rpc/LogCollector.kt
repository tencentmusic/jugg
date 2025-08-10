package com.sickworm.intellij.jugg.rpc

import com.intellij.openapi.diagnostic.Logger
import org.apache.log4j.Level
import java.util.concurrent.ConcurrentLinkedQueue

@Suppress("UNUSED_PARAMETER")
class LogCollector : Logger() {

    private val logs = ConcurrentLinkedQueue<String>()

    fun getAllLogs(): String {
        return logs.joinToString("\n")
    }

    private fun addLog(level: String, message: String, throwable: Throwable? = null) {
        logs.offer("[$level] $message")
        
        // 限制日志数量，避免内存溢出
        if (logs.size > 100000) {
            logs.poll()
        }
    }

    override fun isDebugEnabled(): Boolean {
        return true
    }

    override fun debug(message: String) {
    }

    override fun debug(t: Throwable?) {
    }

    override fun debug(message: String, t: Throwable?) {
    }

    override fun info(message: String) {
        addLog("INFO", message)
    }

    override fun info(message: String, t: Throwable?) {
        addLog("INFO", message, t)
    }

    override fun warn(message: String, t: Throwable?) {
        addLog("WARN", message, t)
    }

    override fun error(message: String, t: Throwable?, vararg details: String?) {
        val fullMessage = if (details.isNotEmpty()) {
            "$message. Details: ${details.filterNotNull().joinToString(", ")}"
        } else {
            message
        }
        addLog("ERROR", fullMessage, t)
    }

    @Suppress("UnstableApiUsage")
    override fun setLevel(level: Level) {
    }
}