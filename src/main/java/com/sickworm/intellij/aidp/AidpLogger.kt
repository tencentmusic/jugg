package com.sickworm.intellij.aidp

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import org.apache.log4j.Level
import java.lang.ref.WeakReference
import java.util.concurrent.CopyOnWriteArrayList

object AidpLogger {

    private val map = mutableMapOf<Project, MutableList<Logger>>()

    var logDebug = false

    fun getInstance(project: Project, tag: String): Logger {
        ensure(project)
        return ProxyLogger(tag, LoggerDispatcher(WeakReference(project)))
    }

    fun listenProjectLog(project: Project, logger: Logger) {
        ensure(project)
        map[project]?.add(logger)
    }

    private fun ensure(project: Project) {
        if (map[project] == null) {
            synchronized(map) {
                if (map[project] == null) {
                    map[project] = CopyOnWriteArrayList()
                    Disposer.register(project) {
                        map.remove(project)
                    }
                }
            }
        }
    }

    private class LoggerDispatcher(
        private val projectRef: WeakReference<Project>): Logger() {

        private val project: Project? get() = projectRef.get()

        override fun isDebugEnabled(): Boolean {
            return logDebug
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

    val impl = getInstance(tag)

    override fun isDebugEnabled(): Boolean {
        return AidpLogger.logDebug
    }

    override fun debug(message: String?) {
        if (isDebugEnabled) {
            impl.debug(message)
            proxy.debug(message)
        }
    }

    override fun debug(t: Throwable?) {
        if (isDebugEnabled) {
            impl.debug(t)
            proxy.debug(t)
        }
    }

    override fun debug(message: String?, t: Throwable?) {
        if (isDebugEnabled) {
            impl.debug(message, t)
            proxy.debug(message, t)
        }
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