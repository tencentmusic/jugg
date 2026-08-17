package com.sickworm.intellij.jugg.cmdline.standalone

import com.sickworm.intellij.jugg.JuggException
import com.sickworm.intellij.jugg.ai.mcp.McpJsonRpc
import com.sickworm.intellij.jugg.ai.mcp.McpJsonRpcRequest
import com.sickworm.intellij.jugg.ai.mcp.McpProjectInfo
import com.sickworm.intellij.jugg.ai.mcp.McpToolCallResult
import com.sickworm.intellij.jugg.ai.mcp.McpToolsListResult
import com.sickworm.intellij.jugg.project.info.JuggProjectInfo
import com.sickworm.intellij.jugg.project.info.ModuleBuildPathInfo
import com.sickworm.intellij.jugg.project.info.ModuleInfo
import com.sickworm.intellij.jugg.project.info.ProjectInfoSerializer
import com.sickworm.intellij.jugg.project.runtime.CliRunConfigurationStore
import com.sickworm.intellij.jugg.project.runtime.CliRunConfigurationGenerator
import com.sickworm.intellij.jugg.project.runtime.JuggPathManager
import com.sickworm.intellij.jugg.project.runtime.ProjectDirNormalizer
import com.sickworm.intellij.jugg.project.runtime.RuntimeInfo
import com.sickworm.intellij.jugg.runtime.PluginInfoReader
import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.jugg.ide.bean.SyncMode
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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
        val expectedCapabilities = listOf(
            "version", "list-projects", "init", "compile", "deploy", "gradle-build", "get-compile-status", "status",
        )
        assertEquals(expectedCapabilities, version.data()["capabilities"])

        val toolsResponse = registry!!.invokeMcp(McpJsonRpcRequest(method = McpJsonRpc.Method.ToolsList, id = 2))
        val tools = (toolsResponse.result as McpToolsListResult).tools.map { it.name }
        assertEquals(expectedCapabilities, tools)

        @Suppress("UNCHECKED_CAST")
        val projects = call("list-projects").data()["projects"] as List<McpProjectInfo>
        assertEquals(1, projects.size)
        assertEquals(ProjectDirNormalizer.normalizeProjectDir(projectDir.canonicalPath), projects.single().projectDir)
        assertEquals(projectDir.canonicalPath, registry!!.getInitializedProjectDirs().single().path)

        val status = call("status", mapOf("projectDir" to projectDir.absolutePath)).data()
        assertTrue(status["hasDevice"] is Boolean)
        assertEquals(false, status["isCompiling"])
    }

    @Test
    fun `init creates the current standalone run configuration from Gradle project info`() {
        val projectDir = temporaryFolder.newFolder("project")
        val pathManager = JuggPathManager(projectDir)
        val moduleDir = projectDir.resolve("app")
        val app = ModuleInfo.virtualModule.copy(
            name = "app",
            moduleType = ModuleInfo.Type.Application,
            projectRootDir = projectDir,
            moduleRootDir = moduleDir,
            buildVariant = "debug",
            buildPathInfo = ModuleBuildPathInfo(projectDir, moduleDir, "debug", buildDirRelativePath = ""),
        )
        ProjectInfoSerializer(pathManager.gradleProjectInfoFile, Logger.getInstance("StandaloneRuntimeTest"))
            .save(JuggProjectInfo(linkedMapOf("app" to app), agpR8Classpath = null))
        registry = StandaloneProjectRegistry(RuntimeInfo("standalone", "4.0", "java-11", "build-1")).apply {
            initialize(projectDir)
        }

        val result = call("init", mapOf("projectDir" to projectDir.absolutePath))

        assertEquals("OK", result.structuredContent["status"])
        val configuration = CliRunConfigurationStore(pathManager).loadCurrent()
        assertEquals("app", configuration?.moduleName)
        assertEquals("./gradlew :app:assembleDebug", configuration?.compileCommand)
    }

    @Test
    fun `standalone accepts the selected remote profile and reports remote execution`() {
        val projectDir = temporaryFolder.newFolder("project")
        val pathManager = JuggPathManager(projectDir)
        val moduleDir = projectDir.resolve("app")
        val app = ModuleInfo.virtualModule.copy(
            name = "app",
            moduleType = ModuleInfo.Type.Application,
            projectRootDir = projectDir,
            moduleRootDir = moduleDir,
            buildVariant = "debug",
            buildPathInfo = ModuleBuildPathInfo(projectDir, moduleDir, "debug", buildDirRelativePath = ""),
        )
        ProjectInfoSerializer(pathManager.gradleProjectInfoFile, Logger.getInstance("StandaloneRuntimeTest"))
            .save(JuggProjectInfo(linkedMapOf("app" to app), agpR8Classpath = null))
        val configuration = CliRunConfigurationGenerator.generateForModule(app).copy(
            isRemoteCompile = true,
            remoteSshUser = "tester",
            remoteSshIp = "127.0.0.1",
            remoteSshPort = 1,
            syncMode = SyncMode.RSYNC_SIMPLE.modeName,
        )
        CliRunConfigurationStore(pathManager).apply {
            save(configuration)
            select(configuration.id)
        }
        registry = StandaloneProjectRegistry(RuntimeInfo("standalone", "4.0", "java-11", "build-1")).apply {
            initialize(projectDir)
        }

        val initResult = call("init", mapOf("projectDir" to projectDir.absolutePath))
        val statusResult = call("status", mapOf("projectDir" to projectDir.absolutePath))
        val compileResult = call("compile", mapOf("projectDir" to projectDir.absolutePath))

        assertEquals("OK", initResult.structuredContent["status"])
        assertEquals(configuration.id, CliRunConfigurationStore(pathManager).loadCurrent()?.id)
        assertEquals(true, CliRunConfigurationStore(pathManager).loadCurrent()?.isRemoteCompile)
        assertEquals("remote", statusResult.data()["executionType"])
        assertEquals("remote", compileResult.data()["executionType"])
        assertTrue(compileResult.data()["jobId"].toString().isNotBlank())
    }

    @Test
    fun `standalone credential input fails with a non interactive message`() {
        val runtimeInfo = RuntimeInfo("standalone", "4.0", "java-11", "build-1")
        val standaloneRegistry = StandaloneProjectRegistry(runtimeInfo)

        try {
            val error = assertFailsWith<JuggException> {
                StandalonePlatformApi(standaloneRegistry, runtimeInfo).showUserAndPasswordInputDialog(
                    content = "SSH Password or Key Path",
                    isPassword = true,
                )
            }

            assertTrue(error.message.orEmpty().contains("non-interactive"))
        } finally {
            standaloneRegistry.close()
        }
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

    @Test
    fun `standalone runtime exposes its dedicated compile log path`() {
        val projectDir = temporaryFolder.newFolder("project")
        registry = StandaloneProjectRegistry(RuntimeInfo("standalone", "4.0", "java-11", "build-1"))

        val runtime = registry!!.initialize(projectDir)

        assertEquals("build/jugg/log/standlone_cli/compile_latest.log", runtime.compileLatestLogPath)
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
