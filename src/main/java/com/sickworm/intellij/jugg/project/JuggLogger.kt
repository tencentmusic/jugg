package com.sickworm.intellij.jugg.project

import com.intellij.openapi.diagnostic.DefaultLogger
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import org.apache.log4j.Level
import java.lang.ref.WeakReference
import java.util.concurrent.CopyOnWriteArrayList

object JuggLogger {

    private val map = mutableMapOf<Project, MutableList<Logger>>()

    fun getInstance(project: Project, tag: String): Logger {
        ensure(project)
        return ProxyLogger(tag, LoggerDispatcher(WeakReference(project)))
    }

    fun listenProjectLog(project: Project, logger: Logger) {
        val loggerList = ensure(project)
        if (!loggerList.contains(logger)) {
            loggerList.add(logger)
        }
    }

    private fun ensure(project: Project): MutableList<Logger> {
        map[project]?.let {
            return it
        }

        synchronized(map) {
            map[project]?.let {
                return it
            }
            val loggerList: MutableList<Logger> = CopyOnWriteArrayList()
            map[project] = loggerList
            Disposer.register(project) {
                map.remove(project)
            }
            return loggerList
        }
    }

    private class LoggerDispatcher(
        private val projectRef: WeakReference<Project>): Logger() {

        private val project: Project? get() = projectRef.get()

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
            map[project]?.forEach { it.debug(message) }
        }

        override fun debug(t: Throwable?) {
            map[project]?.forEach { it.debug(t) }
        }

        override fun debug(message: String?, t: Throwable?) {
            map[project]?.forEach { it.debug(message, t) }
        }

        override fun info(message: String?) {
            map[project]?.forEach { it.info(message) }
        }

        override fun info(message: String?, t: Throwable?) {
            map[project]?.forEach { it.info(message, t) }
        }

        override fun warn(message: String?, t: Throwable?) {
            map[project]?.forEach { it.warn(message, t) }
        }

        override fun error(message: String?, t: Throwable?, vararg details: String?) {
            map[project]?.forEach { it.error(message, t, *details) }
        }

        override fun setLevel(level: Level) {
        }
    }
}

private class ProxyLogger(
    tag: String,
    private val proxy: Logger
): Logger() {

    val impl = ErrorSafeDefaultLogger(tag)

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
        impl.debug(message)
        proxy.debug(message)
    }

    override fun debug(t: Throwable?) {
        impl.debug(t)
        proxy.debug(t)
    }

    override fun debug(message: String?, t: Throwable?) {
        impl.debug(message, t)
        proxy.debug(message, t)
    }

    override fun info(message: String?) {
        impl.info(message)
        proxy.info(message)
    }

    override fun info(message: String?, t: Throwable?) {
        impl.info(message, t)
        proxy.info(message, t)
    }

    override fun warn(message: String?, t: Throwable?) {
        impl.warn(message, t)
        proxy.warn(message, t)
    }

    override fun error(message: String?, t: Throwable?, vararg details: String?) {
        impl.error(message, t, *details)
        proxy.error(message, t, *details)
    }

    override fun setLevel(level: Level) {
        impl.setLevel(level)
        proxy.setLevel(level)
    }
}

/**
 * Log to idea.log
 */
class ErrorSafeDefaultLogger(category: String): DefaultLogger(category) {

    override fun warn(message: String?, t: Throwable?) {
        val finalT = checkException(t)
        val finalMessage = message + attachmentsToString(t)
    }

    override fun error(message: String?, t: Throwable?, vararg details: String?) {
        val finalT = checkException(t)
        val finalMessage = message + attachmentsToString(t)
        dumpExceptionsToStderr(finalMessage, finalT, *details)
    }

    private fun dumpWarnExceptions(message: String?, t: Throwable?, vararg details: String?) {
        if (shouldDumpExceptionToStderr()) {
            println("WARN: $message")
            t?.printStackTrace(System.err)
            if (details.isNotEmpty()) {
                println("details: ")
                for (detail in details) {
                    println(detail)
                }
            }
        }
    }
}