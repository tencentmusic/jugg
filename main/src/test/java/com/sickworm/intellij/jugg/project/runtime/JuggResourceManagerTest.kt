package com.sickworm.intellij.jugg.project.runtime

import org.junit.Rule
import org.junit.Test
import org.junit.Assume.assumeTrue
import org.junit.rules.TemporaryFolder
import java.net.URLClassLoader
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class JuggResourceManagerTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `prepare extracts verified files and repairs corrupted content`() {
        val resourcesDir = temporaryFolder.newFolder("resources")
        resourcesDir.resolve("deployer/test/installer/arm64-v8a").mkdirs()
        resourcesDir.resolve("deployer/test/installer/arm64-v8a/installer").writeText("installer-v1")
        resourcesDir.resolve("deployer/test/metadata.json").writeText(
            """{"schemaVersion":1,"protocolVersion":"test-1","files":[{"path":"installer/arm64-v8a/installer","sha256":"0f6021accfba7c54e082115fa269a53a997ad5c1ac90340ad179b2b764e126c4","executable":true}],"protocolDependencies":[]}"""
        )
        val globalRoot = temporaryFolder.newFolder("jugg-home")
        val classLoader = URLClassLoader(arrayOf(resourcesDir.toURI().toURL()), null)
        val manager = JuggResourceManager(classLoader, globalRoot)

        val prepared = manager.prepare("deployer/test", "runtime/1/deployer/test")
        assertEquals("test-1", prepared.metadata.protocolVersion)
        val installer = prepared.directory.resolve("installer/arm64-v8a/installer")
        assertEquals("installer-v1", installer.readText())
        assertTrue(installer.canExecute())

        installer.writeText("broken")
        manager.prepare("deployer/test", "runtime/1/deployer/test")
        assertEquals("installer-v1", installer.readText())

        assumeTrue(installer.setExecutable(false, true))
        manager.prepare("deployer/test", "runtime/1/deployer/test")
        assertTrue(installer.canExecute())
    }

    @Test
    fun `prepare rejects resource checksum mismatch`() {
        val resourcesDir = temporaryFolder.newFolder("bad-resources")
        resourcesDir.resolve("deployer/test").mkdirs()
        resourcesDir.resolve("deployer/test/file.bin").writeText("content")
        resourcesDir.resolve("deployer/test/metadata.json").writeText(
            """{"schemaVersion":1,"protocolVersion":"test-1","files":[{"path":"file.bin","sha256":"0000000000000000000000000000000000000000000000000000000000000000","executable":false}],"protocolDependencies":[]}"""
        )
        val manager = JuggResourceManager(
            URLClassLoader(arrayOf(resourcesDir.toURI().toURL()), null),
            temporaryFolder.newFolder("bad-home"),
        )

        assertFailsWith<IllegalStateException> {
            manager.prepare("deployer/test", "runtime/1/deployer/test")
        }
    }
}
