package com.sickworm.intellij.jugg.ai.mcp.actions

import com.sickworm.intellij.jugg.ai.mcp.McpToolStatus
import com.sickworm.intellij.jugg.compiler.BuildTarget
import com.sickworm.intellij.jugg.deploy.FullBuildInfo
import com.sickworm.intellij.jugg.deploy.FullBuildInfoSerializer
import com.sickworm.intellij.jugg.platform.IPlatformApi
import com.sickworm.intellij.jugg.platform.PlatformApi
import com.sickworm.intellij.jugg.project.JuggPathManager
import org.junit.After
import org.junit.Assert
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito
import java.io.File

/**
 * ListProjectsMcpToolActionTest verifies list-projects returns per-project compile history flags.
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
    fun listProjectsReturnsHasCompiledBeforeFlag() {
        val compiledProject = tempFolder.newFolder("compiled")
        writeFullBuildInfo(compiledProject)
        val neverCompiledProject = tempFolder.newFolder("never_compiled")

        PlatformApi.impl = FakePlatformApi(
            initializedProjectDirs = listOf(compiledProject, neverCompiledProject),
        )

        val result = ListProjectsMcpToolAction().executeGlobal()

        Assert.assertEquals(McpToolStatus.OK, result.status)
        @Suppress("UNCHECKED_CAST")
        val data = result.data as Map<String, Any>
        @Suppress("UNCHECKED_CAST")
        val projects = data["projects"] as List<Any>
        val byDir = projects.associateBy { readStringProperty(it, "projectDir") }

        Assert.assertEquals(true, readBooleanProperty(byDir[compiledProject.absolutePath], "hasCompiledBefore"))
        Assert.assertEquals(false, readBooleanProperty(byDir[neverCompiledProject.absolutePath], "hasCompiledBefore"))
    }

    private fun writeFullBuildInfo(projectDir: File) {
        val pathManager = JuggPathManager(projectDir)
        val fullBuildInfo = FullBuildInfo(
            compileCommand = "./gradlew :app:assembleDebug",
            buildTarget = BuildTarget.APP,
            createdAt = 123L,
        )
        val fullBuildInfoFile = File(pathManager.compileContextDbDir, "full_build_info.json")
        fullBuildInfoFile.parentFile?.mkdirs()
        fullBuildInfoFile.writeText(FullBuildInfoSerializer().serialize(fullBuildInfo), Charsets.UTF_8)
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
