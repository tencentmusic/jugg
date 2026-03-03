package com.sickworm.intellij.jugg.ide.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class ClientSetupDocExporterTest {

    @Test
    fun export_shouldCopyClientSetupFileToBuildConfig() {
        val root = Files.createTempDirectory("jugg-client-setup-test").toFile()
        val output = ClientSetupDocExporter.export(root)
        val resourceText = ClientSetupDocExporter::class.java.classLoader
            .getResourceAsStream("docs/skills/install/client_setup.md")
            ?.bufferedReader(Charsets.UTF_8)
            ?.use { it.readText() }

        assertTrue(output.exists())
        assertEquals(File(root, "build/jugg/config/client_setup.md").path, output.path)
        assertTrue(resourceText != null)
        assertEquals(resourceText, output.readText(Charsets.UTF_8))
    }
}
