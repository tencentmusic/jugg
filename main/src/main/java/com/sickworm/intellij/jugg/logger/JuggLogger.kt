package com.sickworm.intellij.jugg.logger

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import org.apache.log4j.Level
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

private val Project.instanceKey get() = basePath ?: "null"

object JuggLogger {

    fun getInstance(project: Project, tag: String): Logger {
        return getInstance(project.instanceKey, tag)
    }

    fun getInstance(logKey: String, tag: String): Logger {
        val holder = ensureKey(logKey)
        return LogDispatcher(
            logKey,
            listOf(
                FileLoggerWrapper(holder.fileLogger.logger, tag),
                holder.logDispatcher,
            ))
    }

    /**
     * Get global logger that will print to all projects
     */
    @Synchronized
    fun getGlobalLogger(tag: String): Logger {
        globalLoggers[tag]?.let {
            return it
        }

        val globalLogger = LogDispatcher("global", getAllProjectLoggers(tag))
        globalLoggers[tag] = globalLogger
        return globalLogger
    }

    @Synchronized
    private fun getAllProjectLoggers(tag: String): List<Logger> {
        val holders = map.values.toList()
        return holders.flatMap { holder ->
            listOf(
                FileLoggerWrapper(holder.fileLogger.logger, tag),
                holder.logDispatcher,
            )
        }
    }

    @Synchronized
    fun listenProjectLog(project: Project, logger: Logger) {
        listenProjectLog(project.instanceKey, logger)
    }

    @Synchronized
    fun listenProjectLog(instanceKey: String, logger: Logger) {
        val logHolder = ensureKey(instanceKey)
        logHolder.logDispatcher.listenProjectLog(logger)
    }

    @Synchronized
    fun stopListenProjectLog(project: Project, logger: Logger) {
        stopListenProjectLog(project.instanceKey, logger)
    }

    @Synchronized
    fun stopListenProjectLog(instanceKey: String, logger: Logger) {
        val logHolder = ensureKey(instanceKey)
        logHolder.logDispatcher.stopListenProjectLog(logger)
    }

    @Synchronized
    fun register(project: Project, logDir: File) {
        register(project.instanceKey, logDir)
    }

    @Synchronized
    fun register(instanceKey: String, logDir: File) {
        map.remove(instanceKey)
        val projectLogHolder = ProjectLogHolder(
            FileLogger(logDir),
            LogDispatcher(instanceKey),
        )
        map[instanceKey] = projectLogHolder
        globalLoggers.forEach { (tag, globalLogger) ->
            globalLogger.resetLoggers(getAllProjectLoggers(tag))
        }
    }

    @Synchronized
    fun unregister(project: Project) {
        unregister(project.instanceKey)
    }

    @Synchronized
    fun unregister(instanceKey: String) {
        map[instanceKey]?.dispose()
        map.remove(instanceKey)
        globalLoggers.forEach { (tag, globalLogger) ->
            globalLogger.resetLoggers(getAllProjectLoggers(tag))
        }
    }

    @Synchronized
    fun recreateLogFileIfDeleted(project: Project) {
        val projectLogHolder = map[project.instanceKey] ?: return
        projectLogHolder.fileLogger.recreateIfDeleted()
        return
    }

    @Synchronized
    fun resetLatestCompileLog(project: Project) {
        map[project.instanceKey]?.fileLogger?.resetLatestCompileLog()
    }

    private val map = ConcurrentHashMap<String, ProjectLogHolder>()
    private val globalLoggers = ConcurrentHashMap<String, LogDispatcher>()

    private fun ensure(project: Project): ProjectLogHolder {
        return ensureKey(project.instanceKey)
    }

    private fun ensureKey(logKey: String): ProjectLogHolder {
        map[logKey]?.let {
            return it
        }
        throw IllegalAccessException("project [$logKey] not registered")
    }

    private class ProjectLogHolder(
        val fileLogger: FileLogger,
        val logDispatcher: LogDispatcher,
    ) {

        fun dispose() {
            fileLogger.dispose()
            logDispatcher.dispose()
        }
    }

}

fun Logger.getInstance(tag: String): Logger {
    if (this is LogDispatcher) {
        return JuggLogger.getInstance(this.instanceKey, tag)
    } else {
        return this
    }
}