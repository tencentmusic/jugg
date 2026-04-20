package com.sickworm.intellij.jugg.ide.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class ClientSetupDocExporterTest {

    @Test
    fun export_shouldCopyClientSetupFileToBuildConfig() {
        val root = Files.createTempDirectory("jugg-client-setup-test").toFile()
        val output = ClientSetupDocExporter.export(root)

        assertTrue(output.exists())
        assertEquals(File(root, "build/jugg/config/client_setup.md").path, output.path)
        val text = output.readText(Charsets.UTF_8)
        assertTrue(text.contains("jugg-android-dev-loop"))
        assertFalse(text.contains("docs/skills/jugg-android-dev-loop"))

        val skillFile = File(root, "build/jugg/config/jugg-android-dev-loop/SKILL.md")
        assertTrue(skillFile.exists())
    }
}
