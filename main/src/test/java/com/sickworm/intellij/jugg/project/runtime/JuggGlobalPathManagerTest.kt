package com.sickworm.intellij.jugg.project.runtime

import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class JuggGlobalPathManagerTest {

    @Test
    fun resourceFile_shouldStoreCopiedResourcesUnderResourcesDir() {
        val rootDir = File("/tmp/jugg-home/.jugg")

        assertEquals(
            File(rootDir, "resources/tools/darwin/aapt2"),
            JuggGlobalPathManager.resourceFile("/tools/darwin/aapt2", rootDir),
        )
    }

    @Test
    fun deployCacheDbFile_shouldUseDeployCacheDirectory() {
        val rootDir = File("/tmp/jugg-home/.jugg")

        assertEquals(
            File(rootDir, "deploy_cache/.deploy_cache.db"),
            JuggGlobalPathManager.deployCacheDbFile(rootDir),
        )
    }

    @Test
    fun resolveWritableRoot_shouldKeepPreferredWhenWritable() {
        val tmp = Files.createTempDirectory("jugg_root_preferred").toFile()
        try {
            val preferred = File(tmp, "preferred").also { it.mkdirs() }
            val fallback = File(tmp, "fallback")

            val resolved = JuggGlobalPathManager.resolveWritableRoot(preferred, fallback)

            assertEquals(preferred.canonicalFile, resolved.canonicalFile)
            assertFalse(fallback.exists())
        } finally {
            tmp.deleteRecursively()
        }
    }

    @Test
    fun resolveWritableRoot_shouldUseFallbackWhenPreferredIsNotWritable() {
        val tmp = Files.createTempDirectory("jugg_root_fallback").toFile()
        try {
            val blocker = File(tmp, "blocker").apply { writeText("not-a-dir") }
            val preferred = File(blocker, ".jugg")
            val fallback = File(tmp, "fallback")

            val resolved = JuggGlobalPathManager.resolveWritableRoot(preferred, fallback)

            assertEquals(fallback.canonicalFile, resolved.canonicalFile)
            assertTrue(resolved.isDirectory)
            assertEquals(0, resolved.listFiles()?.size)
        } finally {
            tmp.deleteRecursively()
        }
    }

    @Test
    fun resolveWritableRoot_shouldFailWhenNeitherDirectoryIsWritable() {
        val tmp = Files.createTempDirectory("jugg_root_none").toFile()
        try {
            val preferredBlocker = File(tmp, "preferred-blocker").apply { writeText("x") }
            val fallbackBlocker = File(tmp, "fallback-blocker").apply { writeText("x") }

            assertFailsWith<IllegalStateException> {
                JuggGlobalPathManager.resolveWritableRoot(
                    File(preferredBlocker, ".jugg"),
                    File(fallbackBlocker, "jugg-user"),
                )
            }
        } finally {
            tmp.deleteRecursively()
        }
    }

    @Test
    fun fallbackRootDir_shouldSanitizeUserName() {
        val tmp = File("/tmp")
        assertEquals(File(tmp, "jugg-a_b"), JuggGlobalPathManager.fallbackRootDir(tmp, "a/b"))
        assertEquals(File(tmp, "jugg-user"), JuggGlobalPathManager.fallbackRootDir(tmp, "   "))
    }

    @Test
    fun rootDirFor_shouldKeepIsolatedHomeLayoutForTests() {
        val userHome = Files.createTempDirectory("jugg_fake_home").toFile()
        try {
            assertEquals(
                File(userHome, ".jugg").absoluteFile.normalize(),
                JuggGlobalPathManager.rootDirFor(userHome).absoluteFile.normalize(),
            )
        } finally {
            userHome.deleteRecursively()
        }
    }
}
