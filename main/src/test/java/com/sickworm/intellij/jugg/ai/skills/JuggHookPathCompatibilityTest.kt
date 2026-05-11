package com.sickworm.intellij.jugg.ai.skills

import com.intellij.openapi.diagnostic.Logger
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mock
import java.io.File
import java.nio.file.Files

class JuggHookPathCompatibilityTest {

    private val logger = mock(Logger::class.java)

    @Test
    fun installForClaude_shouldInjectJuggSkillsHooksPath() {
        val userHome = Files.createTempDirectory("jugg-home-hook-config-path").toFile()

        JuggHookInstaller.installForClaude(userHome, logger)

        val content = File(userHome, ".claude/settings.json").readText()
        assertTrue(content.contains(".jugg/skills/hooks/start.py"))
        assertTrue(content.contains(".jugg/skills/hooks/stop.py"))
        assertTrue(content.contains(".jugg/skills/hooks/edit.py"))
        assertTrue(content.contains(".jugg/skills/hooks/command.py"))
    }
}
