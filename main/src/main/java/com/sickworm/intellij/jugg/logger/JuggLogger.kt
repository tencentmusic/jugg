package com.sickworm.intellij.jugg.logger

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import java.io.File

object JuggLogger {

    fun getInstance(project: Project, tag: String): Logger {
        val holder = ensure(project)
        return LogDispatcher(listOf(
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
        map.remove(project)
        map[project] = ProjectLogHolder(
            FileLogger(logDir),
            LogDispatcher(),
        )

        Disposer.register(project) {
            map.remove(project)
        }
    }

    fun resetLatestCompileLog(project: Project) {
        map[project]?.fileLogger?.resetLatestCompileLog()
    }

    private val map = mutableMapOf<Project, ProjectLogHolder>()

    private fun ensure(project: Project): ProjectLogHolder {
        map[project]?.let {
            return it
        }
        throw IllegalAccessException("project [$project] not registered")
    }

    private class ProjectLogHolder(
        val fileLogger: FileLogger,
        val logDispatcher: LogDispatcher,
    )

}

