package com.sickworm.intellij.jugg.project.runtime

import org.junit.Rule
import org.junit.Test
import org.junit.Assume.assumeTrue
import org.junit.rules.TemporaryFolder
import java.net.URLClassLoader
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class JuggResourceManagerTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `prepare extracts resources to the shared directory and replaces existing content`() {
        val resourcesDir = temporaryFolder.newFolder("resources")
        resourcesDir.resolve("deployer/test/installer/arm64-v8a").mkdirs()
        resourcesDir.resolve("deployer/test/installer/arm64-v8a/installer").writeText("installer-v1")
        resourcesDir.resolve("deployer/test/metadata.json").writeText(
            """{"schemaVersion":1,"protocolVersion":"test-1","files":[{"path":"installer/arm64-v8a/installer","executable":true}]}"""
        )
        val globalRoot = temporaryFolder.newFolder("jugg-home")
        val classLoader = URLClassLoader(arrayOf(resourcesDir.toURI().toURL()), null)
        val manager = JuggResourceManager(classLoader, globalRoot)

        val prepared = manager.prepare("deployer/test")
        assertEquals(globalRoot.resolve("resources/deployer/test").canonicalFile, prepared.directory)
        assertEquals("test-1", prepared.metadata.protocolVersion)
        val installer = prepared.directory.resolve("installer/arm64-v8a/installer")
        assertEquals("installer-v1", installer.readText())
        assertTrue(installer.canExecute())

        installer.writeText("broken")
        manager.prepare("deployer/test")
        assertEquals("installer-v1", installer.readText())

        assumeTrue(installer.setExecutable(false, true))
        manager.prepare("deployer/test")
        assertTrue(installer.canExecute())
    }

    @Test
    fun `prepare fails when a missing target has no embedded resource`() {
        val resourcesDir = temporaryFolder.newFolder("bad-resources")
        resourcesDir.resolve("deployer/test").mkdirs()
        resourcesDir.resolve("deployer/test/metadata.json").writeText(
            """{"schemaVersion":1,"protocolVersion":"test-1","files":[{"path":"file.bin","executable":false}]}"""
        )
        val manager = JuggResourceManager(
            URLClassLoader(arrayOf(resourcesDir.toURI().toURL()), null),
            temporaryFolder.newFolder("bad-home"),
        )

        assertFailsWith<IllegalStateException> {
            manager.prepare("deployer/test")
        }
    }

    @Test
    fun `prepare replaces a target file symbolic link without changing its destination`() {
        assumeTrue(!System.getProperty("os.name").startsWith("Windows", ignoreCase = true))
        val resourcesDir = temporaryFolder.newFolder("linked-file-resources")
        resourcesDir.resolve("deployer/test/installer/arm64-v8a").mkdirs()
        resourcesDir.resolve("deployer/test/installer/arm64-v8a/installer").writeText("arm64")
        resourcesDir.resolve("deployer/test/metadata.json").writeText(
            """{"schemaVersion":1,"protocolVersion":"test-1","files":[{"path":"installer/arm64-v8a/installer","executable":true}]}"""
        )
        val globalRoot = temporaryFolder.newFolder("linked-file-home")
        val targetDir = globalRoot.resolve("resources/deployer/test/installer/arm64-v8a").apply { mkdirs() }
        val linkDestination = targetDir.resolve("x86-installer").apply { writeText("x86") }
        val installer = targetDir.resolve("installer")
        Files.createSymbolicLink(installer.toPath(), linkDestination.toPath())

        JuggResourceManager(URLClassLoader(arrayOf(resourcesDir.toURI().toURL()), null), globalRoot)
            .prepare("deployer/test")

        assertFalse(Files.isSymbolicLink(installer.toPath()))
        assertEquals("arm64", installer.readText())
        assertEquals("x86", linkDestination.readText())
    }

    @Test
    fun `prepare rejects a symbolic link in the target directory path`() {
        assumeTrue(!System.getProperty("os.name").startsWith("Windows", ignoreCase = true))
        val resourcesDir = temporaryFolder.newFolder("linked-directory-resources")
        resourcesDir.resolve("deployer/test/installer/arm64-v8a").mkdirs()
        resourcesDir.resolve("deployer/test/installer/arm64-v8a/installer").writeText("arm64")
        resourcesDir.resolve("deployer/test/metadata.json").writeText(
            """{"schemaVersion":1,"protocolVersion":"test-1","files":[{"path":"installer/arm64-v8a/installer","executable":true}]}"""
        )
        val globalRoot = temporaryFolder.newFolder("linked-directory-home")
        val installerRoot = globalRoot.resolve("resources/deployer/test/installer").apply { mkdirs() }
        val x86Installer = installerRoot.resolve("x86/installer").apply {
            parentFile.mkdirs()
            writeText("x86")
        }
        Files.createSymbolicLink(installerRoot.resolve("arm64-v8a").toPath(), x86Installer.parentFile.toPath())

        assertFailsWith<IllegalStateException> {
            JuggResourceManager(URLClassLoader(arrayOf(resourcesDir.toURI().toURL()), null), globalRoot)
                .prepare("deployer/test")
        }
        assertEquals("x86", x86Installer.readText())
    }

    @Test
    fun `prepare rejects a symbolic link used as the shared resource root`() {
        assumeTrue(!System.getProperty("os.name").startsWith("Windows", ignoreCase = true))
        val resourcesDir = temporaryFolder.newFolder("linked-root-resources")
        resourcesDir.resolve("deployer/test").mkdirs()
        resourcesDir.resolve("deployer/test/file.bin").writeText("resource")
        resourcesDir.resolve("deployer/test/metadata.json").writeText(
            """{"schemaVersion":1,"protocolVersion":"test-1","files":[{"path":"file.bin","executable":false}]}"""
        )
        val globalRoot = temporaryFolder.newFolder("linked-root-home")
        val linkDestination = temporaryFolder.newFolder("linked-root-destination")
        Files.createSymbolicLink(globalRoot.resolve("resources").toPath(), linkDestination.toPath())

        assertFailsWith<IllegalStateException> {
            JuggResourceManager(URLClassLoader(arrayOf(resourcesDir.toURI().toURL()), null), globalRoot)
                .prepare("deployer/test")
        }
        assertFalse(linkDestination.resolve("deployer/test/file.bin").exists())
    }

    @Test
    fun `prepare rejects a resource root with traversal segments`() {
        val exception = assertFailsWith<IllegalStateException> {
            JuggResourceManager(globalRootDir = temporaryFolder.newFolder("traversal-home"))
                .prepare("deployer/..")
        }

        assertTrue(exception.message?.contains("contains traversal") == true)
    }

    @Test
    fun `prepare rejects an absolute resource root`() {
        val exception = assertFailsWith<IllegalStateException> {
            JuggResourceManager(globalRootDir = temporaryFolder.newFolder("absolute-home"))
                .prepare("/deployer/test")
        }

        assertTrue(exception.message?.contains("is absolute") == true)
    }
}
