package com.sickworm.intellij.jugg.ai.skills

import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.jugg.ai.skills.agents.AgentPermissionRuleTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import java.io.File
import java.nio.file.Files

class CodexPermissionRuleInstallerTest {

    @Test
    fun install_shouldAppendJuggRuleWithoutRemovingExistingRules() {
        val userHome = Files.createTempDirectory("jugg-home-codex-rule-append").toFile()
        val rulesFile = File(userHome, ".codex/rules/default.rules")
        rulesFile.parentFile.mkdirs()
        rulesFile.writeText("prefix_rule(pattern=[\"./gradlew\", \"test\"], decision=\"allow\")\n")

        CodexPermissionRuleInstaller.install(
            listOf(
                AgentPermissionRuleTarget(
                    rulesFile = rulesFile,
                    prefixPattern = listOf(
                        "python3",
                        File(userHome, ".codex/skills/jugg-android-dev-loop/scripts/jugg.py").absolutePath,
                    ),
                ),
            ),
        )

        val lines = rulesFile.readLines()
        assertEquals("prefix_rule(pattern=[\"./gradlew\", \"test\"], decision=\"allow\")", lines[0])
        assertEquals(
            "prefix_rule(pattern=[\"python3\", \"${File(userHome, ".codex/skills/jugg-android-dev-loop/scripts/jugg.py").absolutePath}\"], decision=\"allow\")",
            lines[1],
        )
    }

    @Test
    fun install_shouldCreateRulesFileWhenMissing() {
        val userHome = Files.createTempDirectory("jugg-home-codex-rule-create").toFile()
        val rulesFile = File(userHome, ".codex/rules/default.rules")

        CodexPermissionRuleInstaller.install(
            listOf(
                AgentPermissionRuleTarget(
                    rulesFile = rulesFile,
                    prefixPattern = listOf(
                        "python3",
                        File(userHome, ".codex/skills/jugg-android-dev-loop/scripts/jugg.py").absolutePath,
                    ),
                ),
            ),
        )

        assertTrue(rulesFile.isFile)
        assertEquals(
            listOf(
                "prefix_rule(pattern=[\"python3\", \"${File(userHome, ".codex/skills/jugg-android-dev-loop/scripts/jugg.py").absolutePath}\"], decision=\"allow\")",
            ),
            rulesFile.readLines(),
        )
    }

    @Test
    fun install_shouldNotDuplicateExistingRule() {
        val userHome = Files.createTempDirectory("jugg-home-codex-rule-idempotent").toFile()
        val rulesFile = File(userHome, ".codex/rules/default.rules")
        val scriptPath = File(userHome, ".codex/skills/jugg-android-dev-loop/scripts/jugg.py").absolutePath
        val target = AgentPermissionRuleTarget(
            rulesFile = rulesFile,
            prefixPattern = listOf("python3", scriptPath),
        )

        CodexPermissionRuleInstaller.install(listOf(target))
        CodexPermissionRuleInstaller.install(listOf(target))

        assertEquals(
            listOf("prefix_rule(pattern=[\"python3\", \"$scriptPath\"], decision=\"allow\")"),
            rulesFile.readLines(),
        )
    }

    @Test
    fun install_shouldLogRuleTargetStatus() {
        val userHome = Files.createTempDirectory("jugg-home-codex-rule-log").toFile()
        val logger = mock(Logger::class.java)
        val rulesFile = File(userHome, ".codex/rules/default.rules")
        val scriptPath = File(userHome, ".codex/skills/jugg-android-dev-loop/scripts/jugg.py").absolutePath
        val target = AgentPermissionRuleTarget(
            rulesFile = rulesFile,
            prefixPattern = listOf("python3", scriptPath),
        )

        CodexPermissionRuleInstaller.install(listOf(target), logger)
        CodexPermissionRuleInstaller.install(listOf(target), logger)

        verify(logger).info(
            "[Install Codex Permission Rules] rulesFile=${rulesFile.path}, status=installed, " +
                "prefix=[python3, $scriptPath]",
        )
        verify(logger).info(
            "[Install Codex Permission Rules] rulesFile=${rulesFile.path}, status=already_installed, " +
                "prefix=[python3, $scriptPath]",
        )
    }
}
