package com.sickworm.intellij.jugg.project.runtime

import com.google.gson.Gson
import com.sickworm.intellij.jugg.ide.bean.JuggSettings
import com.sickworm.intellij.jugg.server.JuggServer
import com.sickworm.intellij.jugg.server.ReportEventData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Test
import org.mockito.kotlin.mock
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class RuntimeInfoFlowTest {

    @Test
    fun `server report uses injected version and host info without runtime type`() {
        val projectDir = Files.createTempDirectory("jugg-runtime-server").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val runtimeInfo = RuntimeInfo("standalone", "4.0.0", "java-11", "standalone-build-1")
        val server = JuggServer(projectDir.name, JuggPathManager(projectDir), scope, runtimeInfo, mock())
        val report = ReportEventData()

        server.report(report)

        assertEquals("4.0.0", report.version)
        assertEquals("java-11", report.ideVersion)
        assertFalse(Gson().toJson(report).contains("\"runtime_type\""))
        scope.cancel()
    }

    @Test
    fun `custom server command uses shared settings without host dialog`() {
        val previousRoot = JuggGlobalPathManager.rootDir
        val rootDir = Files.createTempDirectory("jugg-runtime-custom-server").toFile()
        JuggGlobalPathManager.rootDir = rootDir
        val projectDir = rootDir.resolve("project").apply { mkdirs() }
        val pathManager = JuggPathManager(projectDir)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val server = JuggServer(projectDir.name, pathManager, scope, RuntimeInfo("standalone", "4.0", "java-11", "build"), mock())
        try {
            JuggSettings.serverUrl = "https://auto.example.com"
            JuggSettings.serverExpireTimeMill = System.currentTimeMillis() + 60_000
            assertEquals("", server.customServerUrl)

            server.setCustomServer(" https://custom.example.com ")

            assertEquals("https://custom.example.com", JuggSettings.serverUrl)
            assertEquals(-1L, JuggSettings.serverExpireTimeMill)
            assertEquals("https://custom.example.com", server.customServerUrl)
        } finally {
            scope.cancel()
            JuggGlobalPathManager.rootDir = previousRoot
        }
    }
}
