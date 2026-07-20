package com.sickworm.intellij.jugg.server

import com.sickworm.intellij.jugg.mock.TestGlobal
import com.sickworm.intellij.jugg.ide.bean.JuggSettings
import com.sickworm.intellij.jugg.project.runtime.JuggPathManager
import com.sickworm.intellij.jugg.project.runtime.RuntimeInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.sql.DriverManager
import kotlin.test.assertEquals

class JuggServerTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `report persists locally without an available server`() {
        TestGlobal.init()
        JuggSettings.serverUrl = null
        JuggSettings.serverExpireTimeMill = 0L
        val projectDir = temporaryFolder.newFolder("project")
        val databaseFile = temporaryFolder.root.resolve("action.db")
        val logger = TestGlobal.getLogger()
        val server = JuggServer(
            projectDir.name,
            JuggPathManager(projectDir),
            CoroutineScope(Dispatchers.IO),
            RuntimeInfo("idea", "test", "test", "test"),
            logger,
            JuggEventLocalStore(databaseFile, logger),
        )

        runBlocking {
            server.report {
                action = "compile"
                isSuccess = false
            }.join()
        }

        DriverManager.getConnection("jdbc:sqlite:${databaseFile.absolutePath}").use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT action, is_success FROM jugg_event").use { result ->
                    result.next()
                    assertEquals("compile", result.getString("action"))
                    assertEquals(false, result.getBoolean("is_success"))
                }
            }
        }
    }
}
