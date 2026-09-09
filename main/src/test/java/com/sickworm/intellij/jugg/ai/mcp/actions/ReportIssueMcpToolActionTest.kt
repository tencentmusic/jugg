package com.sickworm.intellij.jugg.ai.mcp.actions

import com.sickworm.intellij.jugg.ai.mcp.IMcpRuntime
import com.sickworm.intellij.jugg.ai.mcp.McpErrorCode
import com.sickworm.intellij.jugg.ai.mcp.McpToolStatus
import com.sickworm.intellij.jugg.deploy.IDeployTargetManager
import com.sickworm.intellij.jugg.ide.bean.JuggSettings
import com.sickworm.intellij.jugg.platform.IPlatformApi
import com.sickworm.intellij.jugg.platform.PlatformApi
import com.sickworm.intellij.jugg.project.runtime.JuggGlobalPathManager
import com.sickworm.intellij.jugg.project.runtime.JuggPathManager
import com.sickworm.intellij.jugg.project.runtime.RuntimeInfo
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.io.File

class ReportIssueMcpToolActionTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private lateinit var originalPlatformApi: IPlatformApi
    private lateinit var originalGlobalRoot: File

    @Before
    fun setUp() {
        originalPlatformApi = runCatching { PlatformApi.impl }.getOrElse { mock() }
        originalGlobalRoot = JuggGlobalPathManager.rootDir
        JuggGlobalPathManager.rootDir = temporaryFolder.newFolder("global")
        JuggSettings.reload()
        val platformApi = mock<IPlatformApi>()
        whenever(platformApi.getRuntimeInfo()).thenReturn(RuntimeInfo("standalone", "test", "test", ""))
        PlatformApi.impl = platformApi
    }

    @After
    fun tearDown() {
        PlatformApi.impl = originalPlatformApi
        JuggGlobalPathManager.rootDir = originalGlobalRoot
        JuggSettings.reload()
    }

    @Test
    fun `default registry exposes both report phases`() {
        val toolNames = McpToolActionRegistry.defaultActions().map { it.toolName }

        assertTrue(McpToolActionRegistry.ToolNames.REPORT_PREPARE in toolNames)
        assertTrue(McpToolActionRegistry.ToolNames.REPORT_UPLOAD in toolNames)
    }

    @Test
    fun `upload requires the prepared report identity and digest`() {
        val schema = UploadIssueReportMcpToolAction().definition.inputSchema

        assertEquals(listOf("projectDir", "reportId", "sha256"), schema.required)
        assertEquals("^[0-9a-f]{8}$", schema.properties.getValue("reportId").pattern)
        assertEquals("^[0-9a-fA-F]{64}$", schema.properties.getValue("sha256").pattern)
    }

    @Test
    fun `upload rejects missing report identity before reading files`() {
        val result = UploadIssueReportMcpToolAction().execute(emptyMap(), mock<IMcpRuntime>())

        assertEquals(McpToolStatus.ERROR, result.status)
        assertEquals(McpErrorCode.INVALID_PARAMS, result.errorCode)
    }

    @Test
    fun `prepare succeeds when device log collection fails`() {
        val projectDir = temporaryFolder.newFolder("project")
        val deployTargetManager = mock<IDeployTargetManager>()
        doThrow(IllegalStateException("Multiple devices are online"))
            .whenever(deployTargetManager).dumpErrorLogs()
        val runtime = mock<IMcpRuntime>()
        whenever(runtime.projectDir).thenReturn(projectDir.absolutePath)
        whenever(runtime.logger).thenReturn(mock())
        whenever(runtime.deployTargetManager).thenReturn(deployTargetManager)

        val result = PrepareIssueReportMcpToolAction().execute(
            mapOf("projectDir" to projectDir.absolutePath),
            runtime,
        )

        assertEquals(McpToolStatus.OK, result.status)
        @Suppress("UNCHECKED_CAST")
        val data = result.data as Map<String, Any>
        assertTrue(File(data.getValue("filePath") as String).isFile)
        val entries = data.getValue("entries") as List<*>
        assertFalse(entries.any { (it as Map<*, *>)["path"] == "diagnostics/device/logcat.log" })
    }

    @Test
    fun `prepare includes project snapshots`() {
        val projectDir = temporaryFolder.newFolder("project-snapshots")
        val pathManager = JuggPathManager(projectDir)
        pathManager.projectInfosDir.mkdirs()
        pathManager.ideProjectInfoFile.writeText("{\"modules\":[]}")
        val deployTargetManager = mock<IDeployTargetManager>()
        whenever(deployTargetManager.dumpErrorLogs()).thenReturn("")
        val runtime = mock<IMcpRuntime>()
        whenever(runtime.projectDir).thenReturn(projectDir.absolutePath)
        whenever(runtime.logger).thenReturn(mock())
        whenever(runtime.deployTargetManager).thenReturn(deployTargetManager)

        val result = PrepareIssueReportMcpToolAction().execute(
            mapOf("projectDir" to projectDir.absolutePath),
            runtime,
        )

        assertEquals(McpToolStatus.OK, result.status)
        @Suppress("UNCHECKED_CAST")
        val entries = (result.data as Map<String, Any>).getValue("entries") as List<*>
        assertTrue(entries.any { (it as Map<*, *>)["path"] == "diagnostics/project-info/project_infos.json" })
    }

    @Test
    fun `prepare ignores explicit serial and collects all device logs`() {
        val projectDir = temporaryFolder.newFolder("serial-project")
        val deployTargetManager = mock<IDeployTargetManager>()
        whenever(deployTargetManager.dumpErrorLogs()).thenReturn("all device logcat")
        doThrow(AssertionError("serial-specific log collection must not be used"))
            .whenever(deployTargetManager).dumpErrorLogs("device-2")
        val runtime = mock<IMcpRuntime>()
        whenever(runtime.projectDir).thenReturn(projectDir.absolutePath)
        whenever(runtime.logger).thenReturn(mock())
        whenever(runtime.deployTargetManager).thenReturn(deployTargetManager)

        val result = PrepareIssueReportMcpToolAction().execute(
            mapOf("projectDir" to projectDir.absolutePath, "serial" to "device-2"),
            runtime,
        )

        assertEquals(McpToolStatus.OK, result.status)
        @Suppress("UNCHECKED_CAST")
        val entries = (result.data as Map<String, Any>).getValue("entries") as List<*>
        assertTrue(entries.any { (it as Map<*, *>)["path"] == "diagnostics/device/logcat.log" })
    }

    @Test
    fun `upload success matches IDEA message without temporary file details`() {
        val result = reportUploadSuccessResult("a1b2c3d4")

        assertEquals("Report uploaded. Jugg Report ID: a1b2c3d4", result.message)
        assertEquals(mapOf("reportId" to "a1b2c3d4"), result.data)
        assertTrue(result.artifacts.isEmpty())
        val output = result.toString()
        assertFalse(output.contains("entries"))
        assertFalse(output.contains("filePath"))
        assertFalse(output.contains("path="))
        assertFalse(output.contains("type="))
    }
}
