@file:Suppress("IncorrectParentDisposable")

package com.sickworm.intellij.jugg.gradle

import com.sickworm.intellij.jugg.compiler.JuggCompilerHelper
import com.sickworm.intellij.jugg.project.data.ModuleBuildPathInfo
import com.sickworm.intellij.jugg.gradle.compile.IGradleCompileClient
import com.sickworm.intellij.jugg.gradle.compile.RemoteGradleCompileClient
import com.sickworm.intellij.jugg.ide.JuggRunConfigurationOptions
import com.sickworm.intellij.jugg.ide.logic.toCompileOptions
import com.sickworm.intellij.jugg.logger.JuggLogger
import com.sickworm.intellij.jugg.mock.*
import com.sickworm.intellij.jugg.project.JuggPathManager
import org.json.JSONObject
import org.junit.BeforeClass
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import kotlin.system.measureTimeMillis
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import com.google.gson.Gson
import java.nio.file.Files
import java.util.Base64
import java.util.UUID

class RemoteGradleCompileClientTest : LocalGradleCompileClientTest() {

    @Test
    fun testSimulatedCancellation() {
        val ready = java.util.concurrent.CountDownLatch(1)
        val result = java.util.concurrent.atomic.AtomicInteger(Int.MIN_VALUE)
        val client = RemoteGradleCompileClient(project, logger = logger)
        client.terminalOutputListener = object : IGradleCompileClient.TerminalOutputListener {
            override fun onOutput(line: String, isNeedPrint: Boolean) {
                if (line.trim() == "FIXTURE_READY") ready.countDown()
            }
            override fun onOutputErr(line: String) = Unit
        }
        val worker = kotlin.concurrent.thread {
            result.set(client.executeRemoteCommand(juggGradleCompileOptions.copy(projectRootPath = "/", remoteSyncPath = "/"),
                "printf 'FIXTURE_READY\\n'; sleep 30"))
        }
        try {
            assertTrue(ready.await(20, java.util.concurrent.TimeUnit.SECONDS))
            client.cancelAction(false)
            worker.join(10_000)
            assertFalse(worker.isAlive)
            assertEquals(IGradleCompileClient.Error.ERROR_CANCELED, result.get())
        } finally {
            client.cancelAction(false)
            client.dispose()
            worker.join(10_000)
        }
    }

    @Test
    fun testSimulatedArtifactFetch() {
        val root = Files.createTempDirectory("jugg-remote-fixture-").toFile()
        val remoteRoot = "/tmp/jugg-e2e-${UUID.randomUUID()}"
        val paths = JuggPathManager(root)
        val options = juggGradleCompileOptions.copy(projectRootPath = root.path, remoteSyncPath = remoteRoot,
            localClasspathStoragePath = paths.localClasspathStoragePathManager,
            outputApkName = "app/build/outputs/apk/debug/app-debug.apk",
            initGradleFilePath = File(root, ".gradle/jugg/init.gradle.kts").path)
        val module = ModuleBuildPathInfo(root, File(root, "app"), "debug",
            customClasspath = listOf("app/custom/classpath.jar"),
            customSyncFilePath = listOf("app/custom/generated.txt"), buildDirRelativePath = "")
        val files = module.allBuildPathRelative.map { it.invariantSeparatorsPath }.map {
            if (it.endsWith(".txt") || it.endsWith(".jar")) it else "$it/fixture.bin"
        } + "app/build/outputs/apk/debug/app-debug.apk"
        File(root, "source.txt").writeText("uploaded source")
        val simulated = options.copy(compileCommand = artifactFixtureCommand(files))
        val bootstrap = options.copy(projectRootPath = "/", remoteSyncPath = "/")
        val client = RemoteGradleCompileClient(project, logger = logger)
        try {
            assertEquals(0, client.executeRemoteCommand(bootstrap, "mkdir -p '$remoteRoot/${root.name}'"))
            client.login(simulated)
            assertTrue(client.compileAndFetchResult().isSuccess)
            assertNotNull(client.fetchClasspathResult(listOf(module)))
            files.dropLast(1).forEach {
                assertEquals("tiny artifact", File(paths.localClasspathStoragePathManager.classpathDir, it).readText())
            }
            val link = File(paths.localClasspathStoragePathManager.classpathDir, "app/build/generated/a-link.txt").toPath()
            assertTrue(Files.isSymbolicLink(link))
            assertEquals("tiny artifact", Files.readString(link))
            val result = client.compileAndFetchResult(isOnlyFetchResult = true)
            assertTrue(result.isSuccess)
            assertTrue(paths.localClasspathStoragePathManager.apkDir.walkTopDown().any {
                it.isFile && it.name.endsWith(".apk") && it.readText() == "tiny artifact"
            })
            client.login(simulated.copy(compileCommand = "exit 23"))
            assertFalse(client.compileAndFetchResult().isSuccess)
            client.login(simulated)
            assertTransferCancellation(client, module)
        } finally {
            try {
                cleanupRemoteFixture(bootstrap, remoteRoot)
            } finally {
                client.dispose()
                root.deleteRecursively()
            }
        }
    }

    private fun assertTransferCancellation(client: RemoteGradleCompileClient, module: ModuleBuildPathInfo) {
        val descendants = mutableListOf<ProcessHandle>()
        val started = java.util.concurrent.atomic.AtomicBoolean()
        client.terminalOutputListener = object : IGradleCompileClient.TerminalOutputListener {
            override fun onOutput(line: String, isNeedPrint: Boolean) {
                if (line.contains("receiving file list") && started.compareAndSet(false, true)) {
                    descendants += nativeTransferProcesses()
                    client.cancelAction(false)
                }
            }
            override fun onOutputErr(line: String) = Unit
        }
        assertNull(client.fetchClasspathResult(listOf(module)))
        assertTrue(started.get(), "Transfer did not reach native rsync")
        assertTrue(descendants.isNotEmpty(), "Native rsync descendants were not observed")
        descendants.forEach {
            if (it.isAlive) it.onExit().get(5, java.util.concurrent.TimeUnit.SECONDS)
            assertFalse(it.isAlive)
        }
    }

    private fun nativeTransferProcesses(): List<ProcessHandle> = ProcessHandle.current().descendants().use { processes ->
        processes.filter { process ->
            val command = process.info().command().orElse("").lowercase()
            command.endsWith("rsync.exe") || command.endsWith("ssh.exe")
        }.collect(java.util.stream.Collectors.toList())
    }

    private fun cleanupRemoteFixture(options: com.sickworm.intellij.jugg.ide.bean.JuggGradleCompileOptions, root: String) {
        val cleaner = RemoteGradleCompileClient(project, logger = logger)
        try {
            assertEquals(0, cleaner.executeRemoteCommand(options, "rm -rf -- '$root'"))
        } finally {
            cleaner.dispose()
        }
    }

    private fun artifactFixtureCommand(files: List<String>): String {
        val encoded = Base64.getEncoder().encodeToString(Gson().toJson(files).toByteArray())
        val script = "import json,base64,os; from pathlib import Path; " +
                "assert Path(\"source.txt\").read_text()==\"uploaded source\"; " +
                "names=json.loads(base64.b64decode(\"$encoded\")); " +
                "[(Path(n).parent.mkdir(parents=True,exist_ok=True),Path(n).write_text(\"tiny artifact\")) for n in names]; " +
                "p=Path(\"app/build/generated/a-link.txt\"); p.unlink(missing_ok=True); os.symlink(\"fixture.bin\",p)"
        return "python3 -c '$script'"
    }

    companion object {

        private lateinit var project: JuggMockProject
        private var isNeedTest = false

        @JvmStatic
        @BeforeClass
        fun beforeClass() {
            val clientInfoFilePath = System.getenv("JUGG_REMOTE_CONFIG_FILE")
            assumeTrue("Set JUGG_REMOTE_CONFIG_FILE to run remote integration tests", !clientInfoFilePath.isNullOrBlank())
            requireNotNull(clientInfoFilePath)
            val clientInfoFile = File(clientInfoFilePath)
            assertTrue(clientInfoFile.exists())

            project = JuggMockProject(projectInfo.projectRoot)
            val pathManager = JuggPathManager(projectInfo.projectRoot)
            JuggLogger.register(project, pathManager.logDir)

            val options = JuggRunConfigurationOptions().also {
                val jsonObject = JSONObject(clientInfoFile.readText())
                it.compileCommand = jsonObject.getString("compileCommand")
                it.outputApkName = jsonObject.getString("outputApkName")
                it.isRemoteCompile = true
                it.remoteSshUser = jsonObject.getString("remoteSshUser")
                it.remoteSshPassword = jsonObject.getString("remoteSshPassword")
                it.remoteSshIp = jsonObject.getString("remoteSshIp")
                it.remoteSshPort = jsonObject.getInt("remoteSshPort")
                it.httpProxyIp = jsonObject.optString("httpProxyIp", "")
                it.httpProxyPort = jsonObject.optInt("httpProxyPort", 0)
                it.syncMode = "rsync_simple"
                it.remoteSyncPath = jsonObject.optString("remoteSyncPath", "")
                it.environmentVariables = jsonObject.optString("environmentVariables", "")
            }
            juggGradleCompileOptions = options.toCompileOptions(pathManager)

            isNeedTest = true

            val initGradleFile = File(juggGradleCompileOptions.initGradleFilePath)
            JuggCompilerHelper::class.java.getResource("/gradle/readProjectInfo.gradle.kts")!!.openStream().use { ins ->
                val text = ins.reader().readText()
                initGradleFile.parentFile.mkdirs()
                initGradleFile.writeText(text)
            }
        }
    }

    @Test
    override fun testCompile() {
        if (!isNeedTest) return

        val remoteClient = RemoteGradleCompileClient(project, logger = logger)
        remoteClient.login(juggGradleCompileOptions)
        val remoteCompileResult = remoteClient.compileAndFetchResult()
        assertTrue(remoteCompileResult.isSuccess)
    }

    @Test
    fun testFetchClasspath() {
        if (!isNeedTest) return

        val modules = GradleSettingsDummyReader(projectInfo.projectRoot).readProjectDirs()
            .map { ModuleBuildPathInfo(projectInfo.projectRoot, it, mockModule.buildVariant, buildDirRelativePath = "") }

        val remoteClient = RemoteGradleCompileClient(project)
        remoteClient.login(juggGradleCompileOptions)

        val costTime = measureTimeMillis {
            val fetchClasspathResult = remoteClient.fetchClasspathResult(modules)
            assertTrue(fetchClasspathResult != null)
        }
        println("costTime ${costTime}ms")
    }

    @Test
    override fun testCancel() {
        if (!isNeedTest) return

        val remoteClient = RemoteGradleCompileClient(project)
        remoteClient.terminalOutputListener = object : IGradleCompileClient.TerminalOutputListener {

            override fun onOutput(line: String, isNeedPrint: Boolean) {
                if (isNeedPrint) {
                    println(line)
                }
                if (line.contains(":preBuild")) {
                    remoteClient.cancelAction(true)
                }
            }

            override fun onOutputErr(line: String) {
                System.err.println(line)
            }
        }
        remoteClient.login(juggGradleCompileOptions)
        val remoteCompileResult = remoteClient.compileAndFetchResult()
        assertFalse(remoteCompileResult.isSuccess)
        assertTrue(remoteCompileResult.isCanceled)
    }


    override fun testFetchLibraryChanges() {
        if (!isNeedTest) return
        super.testFetchLibraryChanges()
    }

    override fun testFetchLocalLibraryChanges() {
        if (!isNeedTest) return
        super.testFetchLocalLibraryChanges()
    }

    override fun testFetchLocalLibraryAarChanges() {
        if (!isNeedTest) return
        super.testFetchLocalLibraryAarChanges()
    }

    override fun getClient(): IGradleCompileClient {
        return RemoteGradleCompileClient(project, false, logger)
    }
}
