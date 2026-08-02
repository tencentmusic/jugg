package com.sickworm.intellij.jugg.server

import com.intellij.openapi.diagnostic.Logger
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.kotlin.mock
import java.sql.DriverManager
import kotlin.test.assertEquals

class JuggEventLocalStoreTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `append creates schema and preserves backend event fields`() {
        val databaseFile = temporaryFolder.newFolder().resolve("action.db")
        val store = JuggEventLocalStore(databaseFile, mock<Logger>())
        val event = ReportEventData(
            version = "3.2.0",
            ideVersion = "Android Studio",
            username = "user",
            projectId = "project",
            sessionId = "1_2",
            action = "compile",
            isSuccess = false,
            costTime = 123L,
            detail = "failed",
        )

        store.append(event)

        DriverManager.getConnection("jdbc:sqlite:${databaseFile.absolutePath}").use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT * FROM jugg_event").use { result ->
                    result.next()
                    assertEquals("3.2.0", result.getString("version"))
                    assertEquals("Android Studio", result.getString("ide_version"))
                    assertEquals("user", result.getString("username"))
                    assertEquals("project", result.getString("project_id"))
                    assertEquals("1_2", result.getString("session_id"))
                    assertEquals("compile", result.getString("action"))
                    assertEquals(false, result.getBoolean("is_success"))
                    assertEquals(123L, result.getLong("cost_time"))
                    assertEquals("failed", result.getString("detail"))
                }
            }
        }
    }

    @Test
    fun `append adds one row per report event`() {
        val databaseFile = temporaryFolder.newFolder().resolve("action.db")
        val store = JuggEventLocalStore(databaseFile, mock<Logger>())

        store.append(ReportEventData().apply { action = "first" })
        store.append(ReportEventData().apply { action = "second" })

        DriverManager.getConnection("jdbc:sqlite:${databaseFile.absolutePath}").use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT COUNT(*) FROM jugg_event").use { result ->
                    result.next()
                    assertEquals(2, result.getInt(1))
                }
            }
        }
    }
}
