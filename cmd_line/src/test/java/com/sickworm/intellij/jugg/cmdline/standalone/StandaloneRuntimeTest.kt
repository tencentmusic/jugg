package com.sickworm.intellij.jugg.cmdline.standalone

import com.sickworm.intellij.jugg.ai.mcp.McpJsonRpc
import com.sickworm.intellij.jugg.ai.mcp.McpJsonRpcRequest
import com.sickworm.intellij.jugg.ai.mcp.McpProjectInfo
import com.sickworm.intellij.jugg.ai.mcp.McpToolCallResult
import com.sickworm.intellij.jugg.ai.mcp.McpToolsListResult
import com.sickworm.intellij.jugg.project.runtime.ProjectDirNormalizer
import com.sickworm.intellij.jugg.project.runtime.RuntimeInfo
import com.sickworm.intellij.jugg.runtime.PluginInfoReader
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StandaloneRuntimeTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private var registry: StandaloneProjectRegistry? = null

    @After
    fun tearDown() {
        registry?.close()
    }

    @Test
    fun `standalone distribution exposes runtime version metadata`() {
        assertFalse(PluginInfoReader.getPluginVersion() == "unknown")
        assertTrue(PluginInfoReader.getPluginCompileTimestamp().isNotBlank())
    }

    @Test
    fun `registry serves version list projects and status without IDEA`() {
        val projectDir = temporaryFolder.newFolder("project")
        val runtimeInfo = RuntimeInfo(
            runtimeType = "standalone",
            runtimeVersion = "4.0",
            hostVersion = "java-11",
            buildTime = "build-1",
        )
        registry = StandaloneProjectRegistry(runtimeInfo).apply { initialize(projectDir) }

        val version = call("version")
        assertEquals("standalone", version.data()["runtimeType"])
        assertEquals("4.0", version.data()["runtimeVersion"])
        assertEquals(listOf("version", "list-projects", "status"), version.data()["capabilities"])

        val toolsResponse = registry!!.invokeMcp(McpJsonRpcRequest(method = McpJsonRpc.Method.ToolsList, id = 2))
        val tools = (toolsResponse.result as McpToolsListResult).tools.map { it.name }
        assertEquals(listOf("version", "list-projects", "status"), tools)

        @Suppress("UNCHECKED_CAST")
        val projects = call("list-projects").data()["projects"] as List<McpProjectInfo>
        assertEquals(1, projects.size)
        assertEquals(ProjectDirNormalizer.normalizeProjectDir(projectDir.canonicalPath), projects.single().projectDir)
        assertEquals(projectDir.canonicalPath, registry!!.getInitializedProjectDirs().single().path)

        val status = call("status", mapOf("projectDir" to projectDir.absolutePath)).data()
        assertEquals(false, status["hasDevice"])
        assertEquals(false, status["isCompiling"])
    }

    @Test
    fun `registry keeps one runtime per canonical project directory`() {
        val projectDir = temporaryFolder.newFolder("project")
        registry = StandaloneProjectRegistry(RuntimeInfo("standalone", "4.0", "java-11", "build-1"))

        val first = registry!!.initialize(projectDir)
        val second = registry!!.initialize(projectDir.resolve("."))

        assertTrue(first === second)
        assertEquals(1, registry!!.getInitializedProjectDirs().size)
    }

    private fun call(toolName: String, arguments: Map<String, Any?> = emptyMap()): McpToolCallResult {
        val response = registry!!.invokeMcp(
            McpJsonRpcRequest(
                method = McpJsonRpc.Method.ToolsCall,
                id = 1,
                params = mapOf("name" to toolName, "arguments" to arguments),
            )
        )
        assertFalse(response.result == null)
        return response.result as McpToolCallResult
    }

    @Suppress("UNCHECKED_CAST")
    private fun McpToolCallResult.data(): Map<String, Any?> {
        return structuredContent["data"] as Map<String, Any?>
    }
}
