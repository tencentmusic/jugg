package com.sickworm.intellij.jugg.deploy.run

import com.android.tools.deployer.Version
import com.sickworm.intellij.jugg.project.runtime.JuggGlobalPathManager
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StandaloneDeployerResourceTest {

    private val originalRootDir = JuggGlobalPathManager.rootDir

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @After
    fun tearDown() {
        JuggGlobalPathManager.rootDir = originalRootDir
    }

    @Test
    fun `prepare validates protocol and extracts all installer binaries`() {
        JuggGlobalPathManager.rootDir = temporaryFolder.newFolder("jugg-home")

        val prepared = StandaloneDeployerResources.prepare()

        assertEquals(JuggGlobalPathManager.resourceFile("deployer/quail").canonicalFile, prepared.directory)
        assertEquals(1, prepared.metadata.schemaVersion)
        assertEquals("c52d6b25", Version.hash())
        assertEquals(Version.hash(), prepared.metadata.protocolVersion)
        listOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64").forEach { abi ->
            assertTrue(prepared.directory.resolve("installer/$abi/installer").isFile)
        }
        assertTrue(prepared.directory.resolve("LICENSE-APACHE-2.0.txt").isFile)
        assertTrue(prepared.directory.resolve("SOURCE_CLASSES.sha256").readLines().size > 40)
    }
}
