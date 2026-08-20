package com.sickworm.intellij.jugg.ai.mcp

import com.sickworm.intellij.jugg.platform.IPlatformApi
import com.sickworm.intellij.jugg.platform.PlatformApi
import com.sickworm.intellij.jugg.project.ProjectDirNormalizer
import org.junit.After
import org.junit.Assert
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito
import java.io.File

/**
 * IdeaMcpRuntimeTest verifies project routing errors include initialized project discovery data.
 */
class IdeaMcpRuntimeTest {

    private lateinit var originalImpl: IPlatformApi

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Before
    fun setUp() {
        originalImpl = try {
            PlatformApi.impl
        } catch (_: Exception) {
            FakePlatformApi(emptyList())
        }
    }

    @After
    fun tearDown() {
        PlatformApi.impl = originalImpl
    }

    @Test
    fun invokeMcp_uninitializedProject_listsInitializedProjects() {
        val openedProject = tempFolder.newFolder("opened_app")
        PlatformApi.impl = FakePlatformApi(listOf(openedProject))

        val response = IdeaMcpRuntime.invokeMcp(
            McpJsonRpcRequest(
                method = McpJsonRpc.Method.ToolsCall,
                id = 1,
                params = mapOf(
                    "name" to "status",
                    "arguments" to mapOf("projectDir" to "/tmp/not_initialized_demo"),
                ),
            )
        )

        val result = response.result as McpToolCallResult
        Assert.assertTrue(result.isError)
        Assert.assertEquals(McpErrorCode.PROJECT_NOT_INITIALIZED, result.structuredContent["errorCode"])

        val message = result.structuredContent["message"] as String
        val openedDir = ProjectDirNormalizer.normalizeProjectDir(openedProject.absolutePath)
        Assert.assertTrue(message.contains("project is not initialized"))
        Assert.assertTrue(message.contains("/tmp/not_initialized_demo") || message.contains("Requested"))
        Assert.assertTrue(message.contains(openedDir))

        @Suppress("UNCHECKED_CAST")
        val data = result.structuredContent["data"] as Map<String, Any>
        @Suppress("UNCHECKED_CAST")
        val projects = data["projects"] as List<Any>
        Assert.assertEquals(1, projects.size)
        val projectInfo = projects.first()
        val projectDir = when (projectInfo) {
            is McpProjectInfo -> projectInfo.projectDir
            is Map<*, *> -> projectInfo["projectDir"] as String
            else -> projectInfo.javaClass.getDeclaredField("projectDir").let {
                it.isAccessible = true
                it.get(projectInfo) as String
            }
        }
        Assert.assertEquals(openedDir, projectDir)
    }

    @Test
    fun invokeMcp_uninitializedProject_withNoOpenProjects_usesEmptyList() {
        PlatformApi.impl = FakePlatformApi(emptyList())

        val response = IdeaMcpRuntime.invokeMcp(
            McpJsonRpcRequest(
                method = McpJsonRpc.Method.ToolsCall,
                id = 2,
                params = mapOf(
                    "name" to "devices",
                    "arguments" to mapOf("projectDir" to "/tmp/missing"),
                ),
            )
        )

        val result = response.result as McpToolCallResult
        Assert.assertTrue(result.isError)
        Assert.assertEquals(McpErrorCode.PROJECT_NOT_INITIALIZED, result.structuredContent["errorCode"])
        Assert.assertTrue((result.structuredContent["message"] as String).contains("(none)"))
        @Suppress("UNCHECKED_CAST")
        val data = result.structuredContent["data"] as Map<String, Any>
        @Suppress("UNCHECKED_CAST")
        val projects = data["projects"] as List<*>
        Assert.assertTrue(projects.isEmpty())
    }

    private class FakePlatformApi(
        private val initializedProjectDirs: List<File>,
    ) : IPlatformApi by Mockito.mock(IPlatformApi::class.java) {
        override fun getInitializedProjectDirs(): List<File> = initializedProjectDirs
    }
}
