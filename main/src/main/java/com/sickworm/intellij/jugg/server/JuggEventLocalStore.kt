package com.sickworm.intellij.jugg.server

import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.jugg.deploy.data.SqLiteDriverLoader
import java.io.File
import java.sql.DriverManager

/**
 * Persists report events in the user-level action database independently from remote reporting.
 */
class JuggEventLocalStore(
    private val databaseFile: File,
    private val logger: Logger,
) {

    fun append(event: ReportEventData) = synchronized(writeLock) {
        databaseFile.parentFile?.mkdirs()
        SqLiteDriverLoader.load(logger)
        DriverManager.getConnection("jdbc:sqlite:${databaseFile.absolutePath}").use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("PRAGMA busy_timeout = 3000")
                statement.execute(CREATE_TABLE_SQL)
            }
            connection.prepareStatement(INSERT_SQL).use { statement ->
                statement.setString(1, event.version)
                statement.setString(2, event.ideVersion)
                statement.setString(3, event.username)
                statement.setString(4, event.projectId)
                statement.setString(5, event.sessionId)
                statement.setString(6, event.action)
                statement.setBoolean(7, event.isSuccess)
                statement.setLong(8, event.costTime)
                statement.setString(9, event.detail)
                statement.executeUpdate()
            }
        }
    }

    companion object {
        private val writeLock = Any()

        private val CREATE_TABLE_SQL = """
            CREATE TABLE IF NOT EXISTS jugg_event (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                version TEXT NOT NULL,
                ide_version TEXT NOT NULL,
                username TEXT NOT NULL,
                project_id TEXT NOT NULL,
                session_id TEXT NOT NULL,
                action TEXT NOT NULL,
                is_success INTEGER NOT NULL DEFAULT 1,
                cost_time INTEGER NOT NULL DEFAULT 0,
                detail TEXT NULL
            )
        """.trimIndent()

        private const val INSERT_SQL = """
            INSERT INTO jugg_event (
                version, ide_version, username, project_id, session_id,
                action, is_success, cost_time, detail
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
        """
    }
}
