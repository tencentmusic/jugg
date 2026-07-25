package com.sickworm.intellij.jugg.ide.ui

import com.sickworm.intellij.jugg.ai.skills.InstallClient
import com.sickworm.intellij.jugg.ai.skills.InstallSummary
import com.sickworm.intellij.jugg.ai.skills.InstallAgentResult
import com.sickworm.intellij.jugg.ai.skills.InstallOptions
import com.sickworm.intellij.jugg.ai.skills.HookInstallSummary
import com.sickworm.intellij.jugg.ai.skills.HookInstallResult
import com.sickworm.intellij.jugg.ai.skills.agents.InstallAgents
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class InstallJuggSkillsDialogTest {

    @Test
    fun descriptionHtml_shouldNotUseFixedBodyWidth() {
        assertFalse(InstallJuggSkillsDialog.descriptionHtml().contains("width:360px"))
    }

    @Test
    fun sectionTitles_shouldExposeAdditionalOptionsTitle() {
        assertEquals("Select agents to install:", InstallJuggSkillsDialog.selectAgentsTitle())
        assertEquals("Additional options: (Recommended)", InstallJuggSkillsDialog.additionalOptionsTitle())
        assertEquals("~/.jugg/skills/hooks", InstallJuggSkillsDialog.hooksInstallPathHint())
    }

    @Test
    fun defaultClientSelection_shouldTreatCursorHomeAsInstalledWithoutSkillsDir() {
        val userHome = Files.createTempDirectory("jugg-home-cursor-default").toFile()
        assertTrue(File(userHome, ".cursor").mkdirs())
        assertTrue(InstallJuggSkillsDialog.shouldDefaultCheck(InstallClient.CURSOR, userHome))
    }

    @Test
    fun defaultClientSelection_shouldTreatAllAgentRootsAsInstalledWithoutSkillsDir() {
        val userHome = Files.createTempDirectory("jugg-home-all-agent-roots").toFile()
        InstallClient.values().forEach { client ->
            val agentRoot = InstallAgents.resolveAgentInstaller(client).resolvePrimarySkillRoot(userHome).parentFile
                ?: throw AssertionError("missing_agent_root_for_${client.name}")
            if (!agentRoot.exists()) {
                assertTrue("failed_to_create_agent_root_${client.name}", agentRoot.mkdirs())
            }
            assertTrue(
                "agent_${client.name}_should_be_default_checked_when_root_exists",
                InstallJuggSkillsDialog.shouldDefaultCheck(client, userHome)
            )
        }
    }

    @Test
    fun exportAndInstallSkills_shouldReturnBundledSetupGuide() {
        val projectDir = Files.createTempDirectory("jugg-project-manual-empty").toFile()
        val userHome = Files.createTempDirectory("jugg-home-manual-empty").toFile()

        val setupGuide = InstallJuggSkillsDialog.exportAndInstallSkills(
            projectDir = projectDir,
            userHome = userHome,
        )
        assertEquals(File(userHome, ".jugg/skills/install/agent_setup.md").path, setupGuide.path)
    }

    @Test
    fun exportAndInstallSkills_shouldNotInstallSkillsCliOrHooks() {
        val projectDir = Files.createTempDirectory("jugg-project-manual").toFile()
        val userHome = Files.createTempDirectory("jugg-home-manual").toFile()

        InstallJuggSkillsDialog.exportAndInstallSkills(
            projectDir = projectDir,
            userHome = userHome,
        )

        assertFalse(File(userHome, ".claude/skills/jugg-android-dev-loop/SKILL.md").exists())
        assertFalse(File(userHome, ".jugg/bin/jugg.py").exists())
        assertFalse(File(userHome, ".claude/settings.json").exists())
    }

    @Test
    fun buildInstallResultText_whenSkillAndHooksInstalled_shouldRenderHookInSameAgentLine() {
        val userHome = Files.createTempDirectory("jugg-home-format-merged").toFile()
        val display = InstallJuggSkillsDialog.buildInstallResultText(
            options = InstallOptions(
                clients = setOf(InstallClient.CLAUDE),
                installCli = false,
                installHooks = true,
            ),
            shouldInstallCli = true,
            skillSummary = InstallSummary(
                listOf(
                    InstallAgentResult(
                        agent = "claude",
                        skillStatus = "ok",
                        reasons = emptyList(),
                    ),
                ),
            ),
            hookSummary = HookInstallSummary(
                listOf(
                    HookInstallResult(
                        path = File(userHome, ".claude/settings.json").path,
                        status = "ok",
                        reason = null,
                    ),
                ),
            ),
            userHome = userHome,
        )

        assertTrue(display.contains("Install summary (1 agent):"))
        assertTrue(display.contains("- claude | skill: OK | hook: OK"))
        assertFalse(display.contains("agent="))
        assertFalse(display.contains("hook=" + File(userHome, ".claude/settings.json").path))
    }

    @Test
    fun buildInstallResultText_whenHooksOnly_shouldKeepReadableAgentSummary() {
        val userHome = Files.createTempDirectory("jugg-home-format-hooks-only").toFile()
        val display = InstallJuggSkillsDialog.buildInstallResultText(
            options = InstallOptions(
                clients = emptySet(),
                installCli = false,
                installHooks = true,
            ),
            shouldInstallCli = true,
            skillSummary = InstallSummary(emptyList()),
            hookSummary = HookInstallSummary(
                listOf(
                    HookInstallResult(
                        path = File(userHome, ".claude/settings.json").path,
                        status = "ok",
                        reason = null,
                    ),
                ),
            ),
            userHome = userHome,
        )

        assertTrue(display.contains("Install summary (1 agent):"))
        assertTrue(display.contains("- claude | skill: SKIP | hook: OK"))
    }

    @Test
    fun buildInstallResultText_whenCliInstalled_shouldUseReadableCliPrefix() {
        val display = InstallJuggSkillsDialog.buildInstallResultText(
            options = InstallOptions(
                clients = emptySet(),
                installCli = true,
                installHooks = false,
            ),
            shouldInstallCli = true,
            skillSummary = InstallSummary(emptyList()),
            hookSummary = HookInstallSummary(emptyList()),
        )

        assertTrue(display.contains("CLI: installed. Try \"jugg -h\" in a NEW terminal."))
    }

    @Test
    fun buildInstallResultText_whenMultipleAgentsWithHooks_shouldRenderOneLinePerAgent() {
        val userHome = Files.createTempDirectory("jugg-home-format-multi-agent").toFile()
        val claudeHookPath = InstallAgents.resolveAgentInstaller(InstallClient.CLAUDE)
            .resolveHookTargets(userHome)
            .first()
            .settingsFile
            .path
        val codexHookPath = InstallAgents.resolveAgentInstaller(InstallClient.CODEX)
            .resolveHookTargets(userHome)
            .first()
            .settingsFile
            .path
        val display = InstallJuggSkillsDialog.buildInstallResultText(
            options = InstallOptions(
                clients = setOf(InstallClient.CLAUDE, InstallClient.CODEX),
                installCli = false,
                installHooks = true,
            ),
            shouldInstallCli = true,
            skillSummary = InstallSummary(
                listOf(
                    InstallAgentResult(agent = "claude", skillStatus = "ok", reasons = emptyList()),
                    InstallAgentResult(agent = "codex", skillStatus = "ok", reasons = emptyList()),
                ),
            ),
            hookSummary = HookInstallSummary(
                listOf(
                    HookInstallResult(path = claudeHookPath, status = "ok", reason = null),
                    HookInstallResult(path = codexHookPath, status = "ok", reason = null),
                ),
            ),
            userHome = userHome,
        )

        assertTrue(display.contains("Install summary (2 agents):"))
        assertTrue(display.contains("- claude | skill: OK | hook: OK"))
        assertTrue(display.contains("- codex | skill: OK | hook: OK"))
        assertFalse(display.contains("\nhook="))
    }

    @Test
    fun ccSwitchSetupPrompt_shouldExplainHowToUseExportedConfig() {
        assertEquals("[Optional] CC Switch Setup", InstallJuggSkillsDialog.ccSwitchSetupTitle())
        assertEquals("No, Thanks", InstallJuggSkillsDialog.ccSwitchSetupCancelText())
        assertEquals("Yes, Open JSON", InstallJuggSkillsDialog.ccSwitchSetupConfirmText())
        assertEquals(
            "CC Switch or CC Switch CLI config was found.\n\n" +
                "CC Switch overwrites Claude hooks when switching models, causing Jugg hooks to stop working.\n\n" +
                "Add Jugg hooks to Common Config to keep them active. Open the Common Config JSON now?",
            InstallJuggSkillsDialog.ccSwitchSetupPrompt(),
        )
    }
}
