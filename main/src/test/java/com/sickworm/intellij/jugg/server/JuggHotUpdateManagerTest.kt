package com.sickworm.intellij.jugg.server

import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.jugg.project.runtime.HotUpdateLoadManifest
import com.sickworm.intellij.jugg.project.runtime.StandaloneHotUpdateManifest
import com.sickworm.intellij.jugg.project.runtime.withGlobalResourceLock
import com.sickworm.intellij.jugg.server.protocols.HotUpdateData
import com.sickworm.intellij.jugg.server.protocols.JarFileInfo
import com.google.gson.Gson
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.mock
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class JuggHotUpdateManagerTest {

    @Test
    fun `network download does not hold global resource lock`() {
        val rootDir = Files.createTempDirectory("jugg-unlocked-update-download").toFile()
        val content = "runtime".toByteArray()
        val downloadStarted = CountDownLatch(1)
        val releaseDownload = CountDownLatch(1)
        val resourceLockAcquired = CountDownLatch(1)
        val updateFailure = AtomicReference<Throwable?>()
        val server = mock<JuggServer> {
            on { downloadFile(any(), any()) } doAnswer { invocation ->
                downloadStarted.countDown()
                releaseDownload.await(5, TimeUnit.SECONDS)
                invocation.getArgument<File>(1).apply {
                    parentFile?.mkdirs()
                    writeBytes(content)
                }
                Unit
            }
        }
        val manager = newManager(rootDir, server)
        val updateThread = Thread {
            runCatching { manager.prepareUpdate(updateData(false, "runtime.jar", content)) }
                .onFailure(updateFailure::set)
        }
        val resourceThread = Thread {
            withGlobalResourceLock("Update runtime settings", rootDir) { resourceLockAcquired.countDown() }
        }

        updateThread.start()
        assertTrue(downloadStarted.await(5, TimeUnit.SECONDS))
        resourceThread.start()
        try {
            assertTrue(resourceLockAcquired.await(500, TimeUnit.MILLISECONDS))
        } finally {
            releaseDownload.countDown()
            updateThread.join(5_000)
            resourceThread.join(5_000)
        }
        assertFalse(updateThread.isAlive)
        assertFalse(resourceThread.isAlive)
        assertEquals(null, updateFailure.get())
    }

    @Test
    fun `slower concurrent update cannot overwrite a newer commit`() {
        val rootDir = Files.createTempDirectory("jugg-superseded-update").toFile()
        val oldContent = "old".toByteArray()
        val newContent = "new".toByteArray()
        val oldDownloadStarted = CountDownLatch(1)
        val releaseOldDownload = CountDownLatch(1)
        val oldFailure = AtomicReference<Throwable?>()
        val server = mock<JuggServer> {
            on { downloadFile(any(), any()) } doAnswer { invocation ->
                val target = invocation.getArgument<File>(1)
                target.parentFile?.mkdirs()
                if (invocation.getArgument<String>(0).endsWith("old.jar")) {
                    oldDownloadStarted.countDown()
                    releaseOldDownload.await(5, TimeUnit.SECONDS)
                    target.writeBytes(oldContent)
                } else {
                    target.writeBytes(newContent)
                }
                Unit
            }
        }
        val oldData = updateData(false, "old.jar", oldContent).copy(targetVersion = "4.1.0")
        val newData = updateData(false, "new.jar", newContent).copy(targetVersion = "4.2.0")
        val oldThread = Thread {
            runCatching { newManager(rootDir, server).prepareUpdate(oldData) }
                .onFailure(oldFailure::set)
        }

        oldThread.start()
        assertTrue(oldDownloadStarted.await(5, TimeUnit.SECONDS))
        try {
            newManager(rootDir, server).prepareUpdate(newData)
        } finally {
            releaseOldDownload.countDown()
        }
        oldThread.join(5_000)

        assertTrue(oldFailure.get()?.message?.contains("superseded") == true)
        val manager = newManager(rootDir, server)
        assertEquals(listOf("new.jar"), manager.resolveLoadManifest("standalone-build-1")?.jarFileNames)
        assertTrue(manager.hotUpdateDataFile.readText().contains("4.2.0"))
        assertFalse(manager.storageDir.resolve("old.jar").exists())
        assertTrue(manager.hotUpdateDir.listFiles().orEmpty().none { it.name.startsWith(".prepare-") })
    }

    @Test
    fun `legacy hot update JSON keeps standalone fields absent`() {
        val data = Gson().fromJson(
            """{"isNeedUpdate":true,"targetVersion":"4.0","updateInfo":null,"jarFileInfos":[],"isNeedReinstall":false}""",
            HotUpdateData::class.java,
        )

        assertTrue(data.standaloneJarFileInfos.orEmpty().isEmpty())
        assertEquals(null, data.standaloneBundleFileInfo)
    }

    @Test
    fun `compatible update publishes independent idea and standalone manifests`() {
        val rootDir = Files.createTempDirectory("jugg-dual-runtime-update").toFile()
        val idea = "idea".toByteArray()
        val standalone = "standalone".toByteArray()
        val server = downloadServer(mapOf("https://server/idea.jar" to idea, "https://server/standalone.jar" to standalone))
        val manager = newManager(rootDir, server)
        val data = updateData(false, "idea.jar", idea).copy(
            releaseBuildId = "release-2",
            releaseChannel = "stable",
            standaloneJarFileInfos = listOf(JarFileInfo("standalone.jar", "https://server/standalone.jar", standalone.md5())),
        )

        manager.prepareUpdate(data)

        assertEquals(listOf("idea.jar"), manager.resolveLoadManifest("standalone-build-1")?.jarFileNames)
        assertEquals(listOf("standalone.jar"), manager.resolveStandaloneLoadManifest()?.jarFileNames)
        assertEquals("release-2", manager.resolveStandaloneLoadManifest()?.releaseBuildId)
    }

    @Test
    fun `server file names reject traversal and platform separators`() {
        val rootDir = Files.createTempDirectory("jugg-invalid-update-name").toFile()
        val content = "jar".toByteArray()
        val manager = newManager(rootDir, downloadServer(emptyMap()))

        listOf("../runtime.jar", "/runtime.jar", "dir/runtime.jar", "dir\\runtime.jar", "bad\u0000.jar").forEach { name ->
            assertFailsWith<IllegalArgumentException>(name) {
                manager.prepareUpdate(updateData(false, name, content))
            }
        }
    }

    @Test
    fun `reinstall candidate activates only for the exact plugin build`() {
        val rootDir = Files.createTempDirectory("jugg-reinstall-identity").toFile()
        val standalone = "standalone".toByteArray()
        val bundle = "bundle".toByteArray()
        val server = downloadServer(mapOf(
            "https://server/standalone.jar" to standalone,
            "https://server/standalone.zip" to bundle,
        ))
        val manager = newManager(rootDir, server)
        val data = updateData(true, "idea.jar", "idea".toByteArray()).copy(
            jarFileInfos = emptyList(),
            releaseBuildId = "release-2",
            releaseChannel = "stable",
            standaloneJarFileInfos = listOf(JarFileInfo("standalone.jar", "https://server/standalone.jar", standalone.md5())),
            standaloneBundleFileInfo = JarFileInfo("standalone.zip", "https://server/standalone.zip", bundle.md5()),
        )

        manager.prepareUpdate(data)

        assertFalse(manager.activateReinstallCandidate("other-build"))
        assertEquals(null, manager.resolveStandaloneLoadManifest())
        assertTrue(manager.activateReinstallCandidate("release-2"))
        assertEquals("release-2", manager.resolveStandaloneLoadManifest()?.releaseBuildId)
        assertFalse(manager.candidatesDir.resolve("release-2").exists())
    }

    @Test
    fun `update notifications are published and consumed by the hot update store`() {
        val rootDir = Files.createTempDirectory("jugg-update-notification").toFile()
        val content = "runtime".toByteArray()
        val manager = newManager(rootDir, downloadServer(mapOf("https://server/runtime.jar" to content)))
        val data = updateData(false, "runtime.jar", content)
        manager.prepareUpdate(data)

        assertTrue(manager.publishUpdateNotification(data))

        assertTrue(manager.hasHotUpdateNotification())
        assertEquals(data.targetVersion, manager.consumeHotUpdateNotification()?.targetVersion)
        assertFalse(manager.hasHotUpdateNotification())

        val reinstallData = data.copy(isNeedReinstall = true)
        manager.prepareUpdate(reinstallData)
        assertTrue(manager.publishUpdateNotification(reinstallData))

        assertEquals(data.targetVersion, manager.readInstallUpdateNotification()?.targetVersion)
        assertTrue(manager.clearInstallUpdateNotification(reinstallData))
        assertEquals(null, manager.readInstallUpdateNotification())
    }

    @Test
    fun `stale update cannot publish or clear a newer notification`() {
        val rootDir = Files.createTempDirectory("jugg-stale-update-notification").toFile()
        val oldContent = "old".toByteArray()
        val newContent = "new".toByteArray()
        val server = downloadServer(mapOf(
            "https://server/old.jar" to oldContent,
            "https://server/new.jar" to newContent,
        ))
        val manager = newManager(rootDir, server)
        val oldData = updateData(false, "old.jar", oldContent).copy(targetVersion = "4.1.0")
        val newData = updateData(true, "new.jar", newContent).copy(targetVersion = "4.2.0")

        manager.prepareUpdate(oldData)
        manager.prepareUpdate(newData)

        assertFalse(manager.publishUpdateNotification(oldData))
        assertTrue(manager.publishUpdateNotification(newData))
        assertFalse(manager.clearInstallUpdateNotification(oldData))
        assertEquals(newData, manager.readInstallUpdateNotification())
    }

    @Test
    fun `compatible update downloads validates and publishes runtime manifest`() {
        val rootDir = Files.createTempDirectory("jugg-runtime-update").toFile()
        val content = "runtime jar".toByteArray()
        val server = downloadServer(mapOf("https://server/runtime.jar" to content))
        val manager = JuggHotUpdateManager(
            server,
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
        assertTrue(manager.hotUpdateDir.listFiles().orEmpty().none { it.name.startsWith(".prepare-") })
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
        val standaloneJar = manager.storageDir.resolve("standalone.jar").apply {
            writeText("standalone")
            setLastModified(expiredMillis)
        }
        manager.standaloneLoadManifestFile.writeText(Gson().toJson(StandaloneHotUpdateManifest(
            1, 1, 1, "4.0", "build", "stable", "build", "external",
            listOf(standaloneJar.name), mapOf(standaloneJar.name to "sha"),
        )))
        manager.publishLoadManifest(HotUpdateLoadManifest("standalone-build-1", listOf(activeJar.name)))

        manager.cleanupExpiredJars(nowMillis)

        assertTrue(activeJar.exists())
        assertFalse(expiredJar.exists())
        assertTrue(recentJar.exists())
        assertTrue(standaloneJar.exists())
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

    private fun newManager(rootDir: File, server: JuggServer, buildTime: String = "standalone-build-1"): JuggHotUpdateManager {
        return JuggHotUpdateManager(
            server,
            buildTime,
            rootDir.resolve("hot_update"),
            mock(),
        )
    }

    private fun ByteArray.md5(): String = MessageDigest.getInstance("MD5").digest(this).joinToString("") { "%02x".format(it) }

}
