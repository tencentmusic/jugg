package com.sickworm.intellij.jugg.ide.logic

import com.sickworm.intellij.jugg.ai.skills.ClientSetupDocExporter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class ClientSetupDocExporterTest {

    @Test
    fun export_shouldReuseAgentSetupFileFromBundledSkillsDir() {
        val root = Files.createTempDirectory("jugg-client-setup-test").toFile()
        val userHome = Files.createTempDirectory("jugg-home-client-setup-test").toFile()
        val output = ClientSetupDocExporter.export(root, userHome)

        assertTrue(output.exists())
        assertEquals(File(userHome, ".jugg/skills/install/agent_setup.md").path, output.path)
        val text = output.readText(Charsets.UTF_8)
        assertTrue(text.contains("jugg-android-dev-loop"))
        assertTrue(text.contains("./jugg-android-dev-loop"))
        assertTrue(File(userHome, ".jugg/skills/jugg-android-dev-loop/SKILL.md").exists())
        assertFalse(File(root, "build/jugg/config").exists())
    }
}
