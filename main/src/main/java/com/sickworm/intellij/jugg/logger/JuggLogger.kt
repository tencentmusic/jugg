package com.sickworm.intellij.jugg.logger

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import java.io.File

import java.util.logging.Logger as FileLogger

object JuggLogger {

    fun getInstance(project: Project, tag: String): Logger {
        val holder = ensure(project)
        return LogDispatcher(listOf(
            FileLoggerWrapper(holder.fileLogger, tag),
            holder.logDispatcher,
        ))
    }

    @Synchronized
    fun listenProjectLog(project: Project, logger: Logger) {
        val logHolder = ensure(project)
        logHolder.logDispatcher.listenProjectLog(logger)
    }

    @Synchronized
    fun register(project: Project, logDir: File) {
        map.remove(project)
        map[project] = ProjectLogHolder(
            FileLoggerWrapper.createLogger(logDir),
            LogDispatcher(),
        )

        Disposer.register(project) {
            map.remove(project)
        }
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

