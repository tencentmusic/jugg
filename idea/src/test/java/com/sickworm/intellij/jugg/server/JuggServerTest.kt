package com.sickworm.intellij.jugg.server

import com.sickworm.intellij.jugg.mock.TestGlobal
import com.sickworm.intellij.jugg.ide.bean.JuggSettings
import com.sickworm.intellij.jugg.project.runtime.JuggPathManager
import com.sickworm.intellij.jugg.project.runtime.RuntimeInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.sql.DriverManager
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

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

    @Test
    fun `server task failure does not cancel runtime scope`() {
        TestGlobal.init()
        val runtimeJob = Job()
        val exceptionHandler = CoroutineExceptionHandler { _, _ -> }
        val runtimeScope = CoroutineScope(runtimeJob + Dispatchers.IO + exceptionHandler)
        val server = JuggServer(
            "project",
            JuggPathManager(temporaryFolder.newFolder("isolated-project")),
            runtimeScope,
            RuntimeInfo("idea", "test", "test", "test"),
            TestGlobal.getLogger(),
        )
        val failureHandled = CountDownLatch(1)
        val runtimeTaskCompleted = CountDownLatch(1)

        try {
            server.launch {
                try {
                    throw LinkageError("hot update classloader conflict")
                } finally {
                    failureHandled.countDown()
                }
            }
            assertTrue(failureHandled.await(5, TimeUnit.SECONDS))

            runtimeScope.launch { runtimeTaskCompleted.countDown() }

            assertTrue(runtimeTaskCompleted.await(5, TimeUnit.SECONDS))
            assertTrue(runtimeJob.isActive)
        } finally {
            runtimeScope.cancel()
        }
    }

    @Test
    fun `runtime cancellation cancels server scope`() {
        TestGlobal.init()
        val runtimeJob = Job()
        val runtimeScope = CoroutineScope(runtimeJob + Dispatchers.IO)
        val server = JuggServer(
            "project",
            JuggPathManager(temporaryFolder.newFolder("lifecycle-project")),
            runtimeScope,
            RuntimeInfo("idea", "test", "test", "test"),
            TestGlobal.getLogger(),
        )
        val serverJob = requireNotNull(server.coroutineContext[Job])

        runtimeScope.cancel()
        runBlocking { serverJob.join() }

        assertTrue(serverJob.isCancelled)
    }
}
