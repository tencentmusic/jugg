package com.sickworm.intellij.jugg.cmdline.standalone

import com.sickworm.intellij.jugg.JuggException
import com.sickworm.intellij.jugg.ai.mcp.McpErrorCode
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
import com.sickworm.intellij.jugg.logger.JuggLogger
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
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
    fun `registry automatically initializes a project on its first valid request`() {
        val projectA = temporaryFolder.newFolder("project-a")
        val projectB = temporaryFolder.newFolder("project-b")
        registry = StandaloneProjectRegistry(RuntimeInfo("standalone", "4.0", "java-11", "build-1")).apply {
            initialize(projectA)
        }

        val status = call("status", mapOf("projectDir" to projectB.absolutePath))

        assertEquals("OK", status.structuredContent["status"])
        assertEquals(
            listOf(projectA.canonicalPath, projectB.canonicalPath),
            registry!!.getInitializedProjectDirs().map(File::getCanonicalPath),
        )
    }

    @Test
    fun `registry does not initialize a project for an invalid request`() {
        val projectA = temporaryFolder.newFolder("project-a")
        val projectB = temporaryFolder.newFolder("project-b")
        registry = StandaloneProjectRegistry(RuntimeInfo("standalone", "4.0", "java-11", "build-1")).apply {
            initialize(projectA)
        }

        val response = registry!!.invokeMcp(
            McpJsonRpcRequest(
                method = McpJsonRpc.Method.ToolsCall,
                id = 1,
                params = mapOf(
                    "name" to "status",
                    "arguments" to mapOf("projectDir" to projectB.absolutePath, "unknown" to true),
                ),
            )
        )

        assertFalse(response.result == null)
        assertEquals(listOf(projectA.canonicalPath), registry!!.getInitializedProjectDirs().map(File::getCanonicalPath))
    }

    @Test
    fun `registry validates the tool before requiring project directory`() {
        val projectDir = temporaryFolder.newFolder("project")
        registry = StandaloneProjectRegistry(RuntimeInfo("standalone", "4.0", "java-11", "build-1")).apply {
            initialize(projectDir)
        }

        val missingName = registry!!.invokeMcp(
            McpJsonRpcRequest(method = McpJsonRpc.Method.ToolsCall, id = 1, params = emptyMap<String, Any?>())
        ).result as McpToolCallResult
        val unknownTool = call("unknown-tool")

        assertEquals(McpErrorCode.INVALID_PARAMS, missingName.structuredContent["errorCode"])
        assertTrue(missingName.content.first().text.contains("Tool name is required"))
        assertEquals(McpErrorCode.TOOL_NOT_FOUND, unknownTool.structuredContent["errorCode"])
    }

    @Test
    fun `failed automatic initialization does not affect registered projects`() {
        val projectA = temporaryFolder.newFolder("project-a")
        val missingProject = temporaryFolder.root.resolve("missing-project")
        registry = StandaloneProjectRegistry(RuntimeInfo("standalone", "4.0", "java-11", "build-1")).apply {
            initialize(projectA)
        }

        val failed = call("status", mapOf("projectDir" to missingProject.absolutePath))
        val existing = call("status", mapOf("projectDir" to projectA.absolutePath))

        assertEquals(McpErrorCode.PROJECT_NOT_INITIALIZED, failed.structuredContent["errorCode"])
        assertEquals("OK", existing.structuredContent["status"])
        assertEquals(listOf(projectA.canonicalPath), registry!!.getInitializedProjectDirs().map(File::getCanonicalPath))
    }

    @Test
    fun `initializing one project does not block requests for another project`() {
        val projectA = temporaryFolder.newFolder("project-a")
        val projectB = temporaryFolder.newFolder("project-b")
        registry = StandaloneProjectRegistry(RuntimeInfo("standalone", "4.0", "java-11", "build-1")).apply {
            initialize(projectA)
        }
        val executor = Executors.newFixedThreadPool(3)
        val lockHeld = CountDownLatch(1)
        val releaseLock = CountDownLatch(1)
        val lockHolder = holdProjectLock(projectB, executor, lockHeld, releaseLock)

        try {
            assertTrue(lockHeld.await(2, TimeUnit.SECONDS))
            val initialization = executor.submit<StandaloneProjectRuntime> { registry!!.initialize(projectB) }
            Thread.sleep(100)
            assertFalse(initialization.isDone)

            val compileStatus = executor.submit<McpToolCallResult> {
                call(
                    "get-compile-status",
                    mapOf("projectDir" to projectA.absolutePath, "jobId" to "missing-job"),
                )
            }.get(2, TimeUnit.SECONDS)

            assertEquals("ERROR", compileStatus.structuredContent["status"])
            releaseLock.countDown()
            initialization.get(2, TimeUnit.SECONDS)
        } finally {
            releaseLock.countDown()
            lockHolder.get(2, TimeUnit.SECONDS)
            executor.shutdownNow()
        }
    }

    @Test
    fun `interrupted automatic initialization cleans resources and can retry`() {
        val projectA = temporaryFolder.newFolder("project-a")
        val projectB = temporaryFolder.newFolder("project-b")
        registry = StandaloneProjectRegistry(RuntimeInfo("standalone", "4.0", "java-11", "build-1")).apply {
            initialize(projectA)
        }
        val executor = Executors.newSingleThreadExecutor()
        val lockHeld = CountDownLatch(1)
        val releaseLock = CountDownLatch(1)
        val lockHolder = holdProjectLock(projectB, executor, lockHeld, releaseLock)
        val failure = AtomicReference<Throwable>()

        try {
            assertTrue(lockHeld.await(2, TimeUnit.SECONDS))
            val initializationThread = thread(name = "standalone-project-initialization") {
                try {
                    registry!!.initialize(projectB)
                } catch (error: Throwable) {
                    failure.set(error)
                }
            }
            waitUntilBlocked(initializationThread)
            initializationThread.interrupt()
            initializationThread.join(2_000)

            assertTrue(failure.get() is InterruptedException)
            assertFailsWith<IllegalAccessException> {
                JuggLogger.getInstance(projectB.canonicalPath, "StandaloneRuntimeTest")
            }

            releaseLock.countDown()
            lockHolder.get(2, TimeUnit.SECONDS)
            assertEquals(projectB.canonicalPath, registry!!.initialize(projectB).projectDir)
        } finally {
            releaseLock.countDown()
            lockHolder.get(2, TimeUnit.SECONDS)
            executor.shutdownNow()
        }
    }

    @Test
    fun `construction failure cleans resources and can retry`() {
        val projectA = temporaryFolder.newFolder("project-a")
        val projectB = temporaryFolder.newFolder("project-b")
        registry = StandaloneProjectRegistry(RuntimeInfo("standalone", "4.0", "java-11", "build-1")).apply {
            initialize(projectA)
        }
        val apkInfoFile = File(JuggPathManager(projectB).compileContextDbDir, "apks/apks.json")
        apkInfoFile.parentFile.mkdirs()
        apkInfoFile.writeText("{")

        val failure = runCatching { registry!!.initialize(projectB) }.exceptionOrNull()

        assertTrue(failure != null)
        assertFailsWith<IllegalAccessException> {
            JuggLogger.getInstance(projectB.canonicalPath, "StandaloneRuntimeTest")
        }
        apkInfoFile.delete()
        assertEquals(projectB.canonicalPath, registry!!.initialize(projectB).projectDir)
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

    private fun waitUntilBlocked(thread: Thread) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (thread.state !in blockedThreadStates && System.nanoTime() < deadline) {
            Thread.sleep(10)
        }
        assertTrue(thread.state in blockedThreadStates)
    }

    private fun holdProjectLock(
        projectDir: File,
        executor: java.util.concurrent.ExecutorService,
        lockHeld: CountDownLatch,
        releaseLock: CountDownLatch,
    ): Future<*> {
        val lockFile = JuggPathManager(projectDir).runtimeLockFile
        lockFile.parentFile.mkdirs()
        return executor.submit {
            RandomAccessFile(lockFile, "rw").channel.use { channel ->
                channel.lock().use {
                    lockHeld.countDown()
                    releaseLock.await()
                }
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun McpToolCallResult.data(): Map<String, Any?> {
        return structuredContent["data"] as Map<String, Any?>
    }

    private companion object {
        val blockedThreadStates = setOf(Thread.State.WAITING, Thread.State.TIMED_WAITING)
    }
}
