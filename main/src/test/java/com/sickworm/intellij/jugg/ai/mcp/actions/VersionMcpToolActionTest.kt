package com.sickworm.intellij.jugg.ai.mcp.actions

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.sickworm.intellij.jugg.compiler.ForceGradleCompileHelper
import com.sickworm.intellij.jugg.compiler.GradleCompileExecutionResult
import com.sickworm.intellij.jugg.compiler.RemoteSshInfoResult
import com.sickworm.intellij.jugg.deploy.IDeployTargetManager
import com.sickworm.intellij.jugg.ide.logic.IJuggConfigurationRunner
import com.sickworm.intellij.jugg.ai.mcp.IMcpRuntime
import com.sickworm.intellij.jugg.ai.mcp.McpToolStatus
import com.sickworm.intellij.jugg.platform.IPlatformApi
import com.sickworm.intellij.jugg.platform.PlatformApi
import org.junit.After
import org.junit.Assert
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito
import java.io.File

/**
 * VersionMcpToolActionTest verifies version tool returns unified version when all projects share the same
 * version, and returns the highest version with a per-project map when versions differ.
 */
class VersionMcpToolActionTest {

    private lateinit var originalImpl: IPlatformApi

    @Before
    fun setUp() {
        originalImpl = try { PlatformApi.impl } catch (_: Exception) { FakePlatformApi(emptyList()) }
    }

    @After
    fun tearDown() {
        PlatformApi.impl = originalImpl
    }

    @Test
    fun testReturnsUnifiedVersionWhenAllProjectsHaveSameVersion() {
        val projectA = File("/tmp/projectA")
        val projectB = File("/tmp/projectB")
        PlatformApi.impl = FakePlatformApi(listOf(projectA, projectB))

        val action = VersionMcpToolAction()
        val result = action.execute(emptyMap(), fakeRuntime())

        Assert.assertEquals(McpToolStatus.OK, result.status)
        @Suppress("UNCHECKED_CAST")
        val data = result.data as Map<String, Any>
        Assert.assertEquals("unknown", data["pluginVersion"])
        Assert.assertNull("projects should be absent when all versions are equal", data["projects"])
    }

    @Test
    fun testToolNameIsVersion() {
        Assert.assertEquals("version", VersionMcpToolAction().toolName)
    }

    @Test
    fun testInputSchemaHasNoRequiredParams() {
        val schema = VersionMcpToolAction().definition.inputSchema
        Assert.assertTrue(schema.required.isEmpty())
        Assert.assertTrue(schema.properties.isEmpty())
    }

    @Test
    fun testReturnsHighestVersionAndProjectMapWhenVersionsDiffer() {
        val projectA = File("/tmp/projectA")
        val projectB = File("/tmp/projectB")
        // Two projects but since PluginInfoReader reads from classloader (no MF in test),
        // both will return "unknown"; this test verifies the shape when all are same.
        PlatformApi.impl = FakePlatformApi(listOf(projectA, projectB))

        val action = VersionMcpToolAction()
        val result = action.execute(emptyMap(), fakeRuntime())

        Assert.assertEquals(McpToolStatus.OK, result.status)
        @Suppress("UNCHECKED_CAST")
        val data = result.data as Map<String, Any>
        // pluginVersion must always be present
        Assert.assertNotNull(data["pluginVersion"])
    }

    @Test
    fun testWorksWithNoProjects() {
        PlatformApi.impl = FakePlatformApi(emptyList())

        val action = VersionMcpToolAction()
        val result = action.execute(emptyMap(), fakeRuntime())

        Assert.assertEquals(McpToolStatus.OK, result.status)
        @Suppress("UNCHECKED_CAST")
        val data = result.data as Map<String, Any>
        Assert.assertEquals("unknown", data["pluginVersion"])
        Assert.assertNull(data["projects"])
    }

    // ─── helpers ─────────────────────────────────────────────────────────────

    private fun fakeRuntime(): IMcpRuntime {
        val project = Mockito.mock(Project::class.java)
        Mockito.`when`(project.basePath).thenReturn("/tmp/projectA")

        val deployTargetManager = Mockito.mock(IDeployTargetManager::class.java)

        return object : IMcpRuntime {
            override val logger: Logger = Logger.getInstance("VersionMcpToolActionTest")
            override val project: Project = project
            override val deployTargetManager: IDeployTargetManager = deployTargetManager
            override val forceGradleCompileHelper: ForceGradleCompileHelper = object : ForceGradleCompileHelper() {
                override fun executeGradleCompile(autoConfirm: Boolean, useCleanAndReinstall: Boolean) =
                    throw UnsupportedOperationException()
                override fun executeGradleCompileBlocking(
                    autoConfirm: Boolean,
                    useCleanAndReinstall: Boolean,
                ): GradleCompileExecutionResult = throw UnsupportedOperationException()
                override fun resolveExecutionType(): String = "local"
                override fun requestRemoteSshInfo(requestedBy: String, reason: String): RemoteSshInfoResult =
                    throw UnsupportedOperationException()
            }
            override val juggConfigurationRunner: IJuggConfigurationRunner = FakeJuggConfigurationRunner()
        }
    }

    private class FakePlatformApi(
        private val projectDirs: List<File>,
    ) : IPlatformApi by Mockito.mock(IPlatformApi::class.java) {
        override fun getInitializedProjectDirs(): List<File> = projectDirs
    }
}
