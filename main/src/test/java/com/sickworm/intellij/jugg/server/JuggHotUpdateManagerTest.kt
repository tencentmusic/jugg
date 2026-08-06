package com.sickworm.intellij.jugg.server

import com.sickworm.intellij.jugg.deploy.api.IDevice
import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.jugg.deploy.FileProcessingWaitResult
import com.sickworm.intellij.jugg.deploy.IDeployStateManager
import com.sickworm.intellij.jugg.deploy.JuggDeployState
import com.sickworm.intellij.jugg.project.runtime.IHostTaskExecutor
import com.sickworm.intellij.jugg.project.runtime.JuggPathManager
import com.sickworm.intellij.jugg.project.runtime.TaskRunnerManager
import com.sickworm.intellij.jugg.server.protocols.HotUpdateData
import com.sickworm.intellij.jugg.server.protocols.JarFileInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.mock
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class JuggHotUpdateManagerTest {

    @Test
    fun `compatible update downloads validates and publishes runtime manifest`() {
        val rootDir = Files.createTempDirectory("jugg-runtime-update").toFile()
        val content = "runtime jar".toByteArray()
        val server = downloadServer(mapOf("https://server/runtime.jar" to content))
        val manager = JuggHotUpdateManager(
            server,
            newTaskRunner(rootDir, server),
            "standalone-build-1",
            rootDir.resolve("hot_update"),
            mock(),
        )

        manager.prepareUpdate(updateData(false, "runtime.jar", content))

        assertEquals(content.toList(), manager.storageDir.resolve("runtime.jar").readBytes().toList())
        assertEquals(
            listOf("runtime.jar"),
            manager.resolveLoadManifest("standalone-build-1")?.jarFileNames,
        )
    }

    @Test
    fun `reinstall update records verified jars without replacing active manifest`() {
        val rootDir = Files.createTempDirectory("jugg-runtime-reinstall").toFile()
        val server = downloadServer(mapOf("https://server/reinstall.jar" to "reinstall jar".toByteArray()))
        val manager = JuggHotUpdateManager(
            server,
            newTaskRunner(rootDir, server),
            "standalone-build-1",
            rootDir.resolve("hot_update"),
            mock(),
        )
        manager.publishLoadManifest(
            com.sickworm.intellij.jugg.project.runtime.HotUpdateLoadManifest(
                "standalone-build-1",
                listOf("active.jar"),
            )
        )
        val content = "reinstall jar".toByteArray()

        manager.prepareUpdate(updateData(true, "reinstall.jar", content))

        assertEquals(
            listOf("active.jar"),
            manager.resolveLoadManifest("standalone-build-1")?.jarFileNames,
        )
        assertTrue(manager.hotUpdateDataFile.readText().contains("reinstall.jar"))
    }

    @Test
    fun `reinstall update keeps reused expired jar for host installation`() {
        val rootDir = Files.createTempDirectory("jugg-runtime-reinstall-expired").toFile()
        val content = "reinstall jar".toByteArray()
        val manager = newManager(rootDir, downloadServer(emptyMap()))
        manager.storageDir.mkdirs()
        val cachedJar = manager.storageDir.resolve("reinstall.jar").apply {
            writeBytes(content)
            setLastModified(System.currentTimeMillis() - 91L * 24 * 60 * 60 * 1000)
        }

        val result = manager.prepareUpdate(updateData(true, cachedJar.name, content))

        assertEquals(cachedJar, result.jarFiles.single())
        assertTrue(cachedJar.exists())
    }

    @Test
    fun `invalid download is rejected without publishing jar or metadata`() {
        val rootDir = Files.createTempDirectory("jugg-runtime-invalid-update").toFile()
        val server = downloadServer(mapOf("https://server/runtime.jar" to "corrupt".toByteArray()))
        val manager = JuggHotUpdateManager(
            server,
            newTaskRunner(rootDir, server),
            "standalone-build-1",
            rootDir.resolve("hot_update"),
            mock(),
        )
        val expected = "expected".toByteArray()

        assertFailsWith<IllegalStateException> {
            manager.prepareUpdate(updateData(false, "runtime.jar", expected))
        }

        assertFalse(manager.storageDir.resolve("runtime.jar").exists())
        assertFalse(manager.hotUpdateDataFile.exists())
        assertFalse(manager.loadManifestFile.exists())
    }

    @Test
    fun `embedded snapshot does not create hot update directory`() {
        val rootDir = Files.createTempDirectory("jugg-hot-update-disabled").toFile()
        val server = downloadServer(emptyMap())
        val manager = newManager(rootDir, server)
        val embeddedLibDir = rootDir.resolve("lib").apply { mkdirs() }
        embeddedLibDir.resolve("main.jar").writeText("main")

        assertFalse(manager.publishEmbeddedIfNeeded(embeddedLibDir))
        assertFalse(manager.hotUpdateDir.exists())
    }

    @Test
    fun `embedded snapshot publishes and replaces packaged jars for a new build`() {
        val rootDir = Files.createTempDirectory("jugg-hot-update-embedded").toFile()
        val server = downloadServer(emptyMap())
        val firstManager = newManager(rootDir, server, "embedded-1")
        firstManager.hotUpdateDir.mkdirs()
        val embeddedLibDir = rootDir.resolve("lib").apply { mkdirs() }
        embeddedLibDir.resolve("main.jar").writeText("old")
        assertTrue(firstManager.publishEmbeddedIfNeeded(embeddedLibDir))
        embeddedLibDir.resolve("main.jar").writeText("new")
        val secondManager = newManager(rootDir, server, "embedded-2")

        assertTrue(secondManager.publishEmbeddedIfNeeded(embeddedLibDir))
        assertEquals("new", secondManager.storageDir.resolve("main.jar").readText())
        assertEquals("embedded-2", secondManager.resolveLoadManifest("embedded-2")?.baseEmbeddedBuildTime)
    }

    @Test
    fun `cleanup removes only unreferenced jars older than ninety days`() {
        val rootDir = Files.createTempDirectory("jugg-hot-update-cleanup").toFile()
        val server = downloadServer(emptyMap())
        val manager = newManager(rootDir, server)
        manager.storageDir.mkdirs()
        val nowMillis = 1_800_000_000_000L
        val expiredMillis = nowMillis - 91L * 24 * 60 * 60 * 1000
        val recentMillis = nowMillis - 89L * 24 * 60 * 60 * 1000
        val activeJar = manager.storageDir.resolve("active.jar").apply {
            writeText("active")
            setLastModified(expiredMillis)
        }
        val expiredJar = manager.storageDir.resolve("expired.jar").apply {
            writeText("expired")
            setLastModified(expiredMillis)
        }
        val recentJar = manager.storageDir.resolve("recent.jar").apply {
            writeText("recent")
            setLastModified(recentMillis)
        }

        manager.cleanupExpiredJars(setOf(activeJar.name), nowMillis)

        assertTrue(activeJar.exists())
        assertFalse(expiredJar.exists())
        assertTrue(recentJar.exists())
    }

    private fun updateData(isNeedReinstall: Boolean, name: String, content: ByteArray): HotUpdateData {
        return HotUpdateData(
            isNeedUpdate = true,
            targetVersion = "4.1.0",
            updateInfo = null,
            jarFileInfos = listOf(JarFileInfo(name, "https://server/$name", content.md5())),
            isNeedReinstall = isNeedReinstall,
        )
    }

    private fun downloadServer(files: Map<String, ByteArray>): JuggServer {
        return mock {
            on { downloadFile(any(), any()) } doAnswer { invocation ->
                val url = invocation.getArgument<String>(0)
                val target = invocation.getArgument<File>(1)
                target.parentFile?.mkdirs()
                target.writeBytes(files.getValue(url))
                Unit
            }
        }
    }

    private fun newTaskRunner(rootDir: File, server: JuggServer): TaskRunnerManager {
        val pathManager = JuggPathManager(rootDir.resolve("project"))
        return TaskRunnerManager(
            logger = mock(),
            deployStateManager = ReadyDeployStateManager(),
            juggServer = server,
            hostTaskExecutor = object : IHostTaskExecutor {
                override val isOnEdt: Boolean = false
                override fun submit(title: String, cancelText: String, showIndicator: Boolean, action: Runnable) = action.run()
            },
            pathManager = pathManager,
            runtimeType = "standalone",
            runtimeVersion = "4.0.0",
            coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
        )
    }

    private fun newManager(rootDir: File, server: JuggServer, buildTime: String = "standalone-build-1"): JuggHotUpdateManager {
        return JuggHotUpdateManager(
            server,
            newTaskRunner(rootDir, server),
            buildTime,
            rootDir.resolve("hot_update"),
            mock(),
        )
    }

    private fun ByteArray.md5(): String = MessageDigest.getInstance("MD5").digest(this).joinToString("") { "%02x".format(it) }

    private class ReadyDeployStateManager : IDeployStateManager {
        override val deployState: JuggDeployState = JuggDeployState.READY
        override var isBuildFileChanged: Boolean = false
        override var whatBuildFileChanged: String = ""
        override var isInitializingIncrementalCompile: Boolean = false
        override fun updateDeployState(): JuggDeployState = deployState
        override fun getDeployState(device: IDevice): JuggDeployState = deployState
        override fun beginFileProcessing() = Unit
        override fun endFileProcessing() = Unit
        override fun hasPendingFileProcessing(): Boolean = false
        override fun waitForPendingFileProcessing(timeoutMs: Long) = FileProcessingWaitResult(false, 0, 0, 0)
    }
}
