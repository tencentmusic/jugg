package com.sickworm.intellij.jugg.loader

import com.sickworm.intellij.jugg.project.runtime.HotUpdateLoadManifest
import org.junit.Test
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JuggHotUpdateManagerTest {

    @Test
    fun `load manifest is active only for matching embedded build`() {
        val directory = Files.createTempDirectory("jugg-hot-update-manifest").toFile()
        val manifestFile = directory.resolve("load_manifest.json")
        val manifest = HotUpdateLoadManifest("embedded-2", listOf("main.jar", "idea.jar"))

        JuggHotUpdateManager.publishLoadManifest(manifest, manifestFile)

        assertEquals(
            manifest,
            JuggHotUpdateManager.resolveLoadManifest(manifestFile, "embedded-2"),
        )
        assertNull(JuggHotUpdateManager.resolveLoadManifest(manifestFile, "embedded-1"))
    }

    @Test
    fun `legacy load list is ignored`() {
        val directory = Files.createTempDirectory("jugg-hot-update-legacy").toFile()
        directory.resolve("load_list.txt").writeText("main.jar")

        assertNull(
            JuggHotUpdateManager.resolveLoadManifest(
                directory.resolve("load_manifest.json"),
                "embedded-1",
            )
        )
    }

    @Test
    fun `embedded snapshot does not create hot update directory`() {
        val directory = Files.createTempDirectory("jugg-hot-update-disabled").toFile()
        val hotUpdateDir = directory.resolve("hot_update")
        val embeddedLibDir = directory.resolve("lib").apply { mkdirs() }
        embeddedLibDir.resolve("main.jar").writeText("main")

        val isPublished = JuggHotUpdateManager.publishEmbeddedIfNeeded(
            embeddedLibDir,
            hotUpdateDir,
            "embedded-1",
        )

        assertFalse(isPublished)
        assertFalse(hotUpdateDir.exists())
    }

    @Test
    fun `embedded snapshot publishes packaged jars`() {
        val directory = Files.createTempDirectory("jugg-hot-update-embedded").toFile()
        val hotUpdateDir = directory.resolve("hot_update").apply { mkdirs() }
        val embeddedLibDir = directory.resolve("lib").apply { mkdirs() }
        embeddedLibDir.resolve("main.jar").writeText("main")
        embeddedLibDir.resolve("idea.jar").writeText("idea")

        val isPublished = JuggHotUpdateManager.publishEmbeddedIfNeeded(
            embeddedLibDir,
            hotUpdateDir,
            "embedded-2",
        )

        assertTrue(isPublished)
        assertEquals(
            setOf("main.jar", "idea.jar"),
            hotUpdateDir.resolve("jars").listFiles().orEmpty().map { it.name }.toSet(),
        )
        assertEquals(
            setOf("main.jar", "idea.jar"),
            JuggHotUpdateManager.resolveLoadManifest(
                hotUpdateDir.resolve("load_manifest.json"),
                "embedded-2",
            )?.jarFileNames?.toSet(),
        )
    }

    @Test
    fun `embedded snapshot keeps current server manifest`() {
        val directory = Files.createTempDirectory("jugg-hot-update-server").toFile()
        val hotUpdateDir = directory.resolve("hot_update").apply { mkdirs() }
        val manifestFile = hotUpdateDir.resolve("load_manifest.json")
        val serverManifest = HotUpdateLoadManifest("embedded-2", listOf("server.jar"))
        JuggHotUpdateManager.publishLoadManifest(serverManifest, manifestFile)
        val embeddedLibDir = directory.resolve("lib").apply { mkdirs() }
        embeddedLibDir.resolve("embedded.jar").writeText("embedded")

        val isPublished = JuggHotUpdateManager.publishEmbeddedIfNeeded(
            embeddedLibDir,
            hotUpdateDir,
            "embedded-2",
        )

        assertFalse(isPublished)
        assertEquals(
            serverManifest,
            JuggHotUpdateManager.resolveLoadManifest(manifestFile, "embedded-2"),
        )
    }

    @Test
    fun `replace file publishes complete snapshot`() {
        val directory = Files.createTempDirectory("jugg-hot-update-publish").toFile()
        val source = directory.resolve("load_manifest.tmp").apply { writeText("new") }
        val target = directory.resolve("load_manifest.json").apply { writeText("old") }

        JuggHotUpdateManager.replaceFile(source, target)

        assertEquals("new", target.readText())
        assertFalse(source.exists())
    }

    @Test
    fun `cleanup removes only unreferenced jars older than ninety days`() {
        val storageDir = Files.createTempDirectory("jugg-hot-update-cleanup").toFile()
        val nowMillis = 1_800_000_000_000L
        val expiredMillis = nowMillis - 91L * 24 * 60 * 60 * 1000
        val recentMillis = nowMillis - 89L * 24 * 60 * 60 * 1000
        val activeJar = storageDir.resolve("active.jar").apply {
            writeText("active")
            setLastModified(expiredMillis)
        }
        val expiredJar = storageDir.resolve("expired.jar").apply {
            writeText("expired")
            setLastModified(expiredMillis)
        }
        val recentJar = storageDir.resolve("recent.jar").apply {
            writeText("recent")
            setLastModified(recentMillis)
        }
        val expiredTempFile = storageDir.resolve("download.tmp").apply {
            writeText("temp")
            setLastModified(expiredMillis)
        }

        JuggHotUpdateManager.cleanupExpiredJars(storageDir, setOf(activeJar.name), nowMillis)

        assertTrue(activeJar.exists())
        assertFalse(expiredJar.exists())
        assertTrue(recentJar.exists())
        assertTrue(expiredTempFile.exists())
    }
}
