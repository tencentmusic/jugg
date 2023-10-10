package com.sickworm.intellij.jugg.logger

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.sickworm.intellij.jugg.ide.bashPathOrDefault
import java.io.File
import java.util.concurrent.ConcurrentHashMap

private val Project.instanceKey get() = bashPathOrDefault

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

    @Synchronized
    fun listenProjectLog(project: Project, logger: Logger) {
        val logHolder = ensure(project)
        logHolder.logDispatcher.listenProjectLog(logger)
    }

    @Synchronized
    fun stopListenProjectLog(project: Project, logger: Logger) {
        val logHolder = ensure(project)
        logHolder.logDispatcher.stopListenProjectLog(logger)
    }

    @Synchronized
    fun register(project: Project, logDir: File) {
        map.remove(project.instanceKey)
        map[project.instanceKey] = ProjectLogHolder(
            FileLogger(logDir),
            LogDispatcher(project.instanceKey),
        )
    }

    @Synchronized
    fun recreateLogFileIfDeleted(project: Project) {
        val projectLogHolder = map[project.instanceKey] ?: return
        projectLogHolder.fileLogger.recreateIfDeleted()
        return
    }

    fun unregister(project: Project) {
        map[project.instanceKey]?.dispose()
        map.remove(project.instanceKey)
    }

    fun resetLatestCompileLog(project: Project) {
        map[project.instanceKey]?.fileLogger?.resetLatestCompileLog()
    }

    private val map = ConcurrentHashMap<String, ProjectLogHolder>()

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