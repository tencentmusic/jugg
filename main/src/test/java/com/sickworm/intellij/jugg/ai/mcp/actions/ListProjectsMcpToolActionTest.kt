package com.sickworm.intellij.jugg.ai.mcp.actions

import com.sickworm.intellij.jugg.ai.mcp.McpToolStatus
import com.sickworm.intellij.jugg.ai.mcp.McpToolRegistry
import com.sickworm.intellij.jugg.deploy.DeployHistoryData
import com.sickworm.intellij.jugg.platform.IPlatformApi
import com.sickworm.intellij.jugg.platform.PlatformApi
import com.sickworm.intellij.jugg.project.runtime.JuggPathManager
import com.sickworm.intellij.jugg.project.runtime.ProjectDirNormalizer
import org.junit.After
import org.junit.Assert
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito
import java.io.File

/**
 * ListProjectsMcpToolActionTest verifies list-projects returns per-project full-compile flags.
 */
class ListProjectsMcpToolActionTest {

    private lateinit var originalImpl: IPlatformApi

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Before
    fun setUp() {
        originalImpl = try { PlatformApi.impl } catch (_: Exception) { FakePlatformApi(emptyList()) }
    }

    @After
    fun tearDown() {
        PlatformApi.impl = originalImpl
    }

    @Test
    fun listProjectsReturnsHasBeenFullCompiledFlag() {
        val compiledProject = tempFolder.newFolder("compiled")
        writeFullCompileState(compiledProject)
        val neverCompiledProject = tempFolder.newFolder("never_compiled")

        PlatformApi.impl = FakePlatformApi(
            initializedProjectDirs = listOf(compiledProject, neverCompiledProject),
        )

        val result = ListProjectsMcpToolAction().executeGlobal(McpToolRegistry())

        Assert.assertEquals(McpToolStatus.OK, result.status)
        @Suppress("UNCHECKED_CAST")
        val data = result.data as Map<String, Any>
        @Suppress("UNCHECKED_CAST")
        val projects = data["projects"] as List<Any>
        val byDir = projects.associateBy { readStringProperty(it, "projectDir") }

        Assert.assertEquals(
            true,
            readBooleanProperty(
                byDir[ProjectDirNormalizer.normalizeProjectDir(compiledProject.absolutePath)],
                "hasBeenFullCompiled",
            ),
        )
        Assert.assertEquals(
            false,
            readBooleanProperty(
                byDir[ProjectDirNormalizer.normalizeProjectDir(neverCompiledProject.absolutePath)],
                "hasBeenFullCompiled",
            ),
        )
    }

    @Test
    fun listProjectsDoesNotTreatPartialFullBuildInfoAsFullCompiled() {
        val partialProject = tempFolder.newFolder("partial")
        writeOnlyFullBuildInfo(partialProject)

        PlatformApi.impl = FakePlatformApi(
            initializedProjectDirs = listOf(partialProject),
        )

        val result = ListProjectsMcpToolAction().executeGlobal(McpToolRegistry())

        @Suppress("UNCHECKED_CAST")
        val data = result.data as Map<String, Any>
        @Suppress("UNCHECKED_CAST")
        val projects = data["projects"] as List<Any>

        Assert.assertEquals(false, readBooleanProperty(projects.first(), "hasBeenFullCompiled"))
    }

    private fun writeFullCompileState(projectDir: File) {
        val pathManager = JuggPathManager(projectDir)
        writeOnlyFullBuildInfo(projectDir)
        File(pathManager.compileContextDbDir, "complete_flag").createNewFile()
        DeployHistoryData(
            fullCompileGitCommitHash = "abcdef",
            subModulesFullCompileGitCommitHash = null,
            incDeployTimes = 0,
            changedFiles = emptyMap(),
        ).save(File(pathManager.deployHistoryDbDir, "deploy_history.json"))
    }

    private fun writeOnlyFullBuildInfo(projectDir: File) {
        val fullBuildInfoFile = File(JuggPathManager(projectDir).compileContextDbDir, "full_build_info.json")
        fullBuildInfoFile.parentFile?.mkdirs()
        fullBuildInfoFile.writeText(
            """{"version":1,"compileCommand":"./gradlew :app:assembleDebug","buildTarget":"APP","createdAt":123}""",
            Charsets.UTF_8,
        )
    }

    private fun readStringProperty(target: Any?, propertyName: String): String {
        requireNotNull(target)
        return target.javaClass.getDeclaredField(propertyName).let {
            it.isAccessible = true
            it.get(target) as String
        }
    }

    private fun readBooleanProperty(target: Any?, propertyName: String): Boolean? {
        requireNotNull(target)
        return runCatching {
            target.javaClass.getDeclaredField(propertyName).let {
                it.isAccessible = true
                it.get(target) as Boolean
            }
        }.getOrNull()
    }

    private class FakePlatformApi(
        private val initializedProjectDirs: List<File>,
    ) : IPlatformApi by Mockito.mock(IPlatformApi::class.java) {
        override fun getInitializedProjectDirs(): List<File> = initializedProjectDirs
    }
}
