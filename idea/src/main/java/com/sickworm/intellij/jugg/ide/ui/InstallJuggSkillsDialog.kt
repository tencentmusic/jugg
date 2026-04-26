package com.sickworm.intellij.jugg.ide.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.components.JBCheckBox
import com.intellij.util.ui.JBUI
import com.intellij.openapi.diagnostic.Logger
import com.intellij.ui.Gray
import com.sickworm.intellij.jugg.ide.logic.ClientSetupDocExporter
import com.sickworm.intellij.jugg.ide.logic.InstallClient
import com.sickworm.intellij.jugg.ide.logic.InstallOptions
import com.sickworm.intellij.jugg.ide.logic.InstallSummary
import com.sickworm.intellij.jugg.ide.logic.HookInstallSummary
import com.sickworm.intellij.jugg.ide.logic.JuggHookInstaller
import com.sickworm.intellij.jugg.ide.logic.JuggSkillInstaller
import com.sickworm.intellij.jugg.project.TaskRunnerManager
import java.awt.FlowLayout
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.event.ActionEvent
import java.io.File
import javax.swing.AbstractAction
import javax.swing.Action
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel

/**
 * Multi-select dialog for choosing target clients for MCP and skill installation.
 */
class InstallJuggSkillsDialog(
    private val project: Project,
    private val projectDir: File,
    private val userHome: File = File(System.getProperty("user.home")),
) : DialogWrapper(true) {

    @Suppress("DialogTitleCapitalization")
    private val claudeCheckBox = JBCheckBox("Claude Code").applyDefaultCheck(InstallClient.CLAUDE)
    private val codexCheckBox = JBCheckBox("Codex").applyDefaultCheck(InstallClient.CODEX)
    private val geminiCheckBox = JBCheckBox("Gemini").applyDefaultCheck(InstallClient.GEMINI)
    private val codebuddyCheckBox = JBCheckBox("CodeBuddy").applyDefaultCheck(InstallClient.CODEBUDDY)
    private val cursorCheckBox = JBCheckBox("Cursor").applyDefaultCheck(InstallClient.CURSOR)
    private val installCliCheckBox = JBCheckBox("Install CLI to \$PATH").apply { isSelected = true }
    private val installHooksCheckBox = JBCheckBox("Install agent hooks").apply { isSelected = true }
    private val panel = JPanel(GridBagLayout())

    init {
        title = "Install Jugg Skills"

        val constraints = GridBagConstraints().apply {
            gridx = 0
            gridy = 0
            anchor = GridBagConstraints.WEST
            insets = JBUI.emptyInsets()
        }
        val descLabel = JLabel(descriptionHtml())
        descLabel.foreground = Gray._100
        panel.add(descLabel, constraints)

        constraints.gridy++
        constraints.insets = JBUI.insetsTop(8)
        panel.add(JLabel(selectAgentsTitle()), constraints)

        val clientRows = listOf(
            claudeCheckBox to InstallClient.CLAUDE,
            codexCheckBox to InstallClient.CODEX,
            geminiCheckBox to InstallClient.GEMINI,
            codebuddyCheckBox to InstallClient.CODEBUDDY,
            cursorCheckBox to InstallClient.CURSOR,
        )
        for ((checkBox, client) in clientRows) {
            constraints.gridy++
            constraints.insets = JBUI.insetsTop(2)
            panel.add(buildClientRow(checkBox, client), constraints)
        }

        constraints.gridy++
        constraints.insets = JBUI.insetsTop(8)
        panel.add(JLabel(additionalOptionsTitle()), constraints)

        constraints.gridy++
        constraints.insets = JBUI.insetsTop(2)
        panel.add(buildCliRow(), constraints)

        constraints.gridy++
        constraints.insets = JBUI.insetsTop(4)
        panel.add(buildHooksRow(), constraints)

        constraints.gridy++
        constraints.insets = JBUI.insetsTop(2)
        val hookDesc = JLabel(
            "<html><body style='width:640px;color:#808080'>" +
                "↑ Injects command hooks to run on SessionStart and Stop. " +
                "Stop will blocked when Android changes are detected " +
                "without jugg-android-dev-loop compile verification." +
                "</body></html>"
        )
        panel.add(hookDesc, constraints)

        init()
    }

    /** Builds a row: \[checkbox] [gray path hint] for a skill client. */
    private fun buildClientRow(checkBox: JBCheckBox, client: InstallClient): JPanel {
        val dirs = JuggSkillInstaller.getInstallDirs(client, userHome)
        val hintText = dirs.joinToString("  ") { toTildePath(it, userHome) }
        return rowPanel(checkBox, hintText)
    }

    /** Builds the CLI install row with a fixed path hint. */
    private fun buildCliRow(): JPanel {
        return rowPanel(installCliCheckBox, "~/.jugg/bin")
    }

    /** Builds the hook install row with target Claude settings paths. */
    private fun buildHooksRow(): JPanel {
        return rowPanel(
            installHooksCheckBox,
            hooksInstallPathHint(),
        )
    }

    private fun rowPanel(checkBox: JBCheckBox, hintText: String): JPanel {
        val row = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0))
        row.add(checkBox)
        if (hintText.isNotEmpty()) {
            val hint = JLabel("  $hintText")
            hint.foreground = Gray._128
            row.add(hint)
        }
        return row
    }

    /** Converts an absolute path to a ~-prefixed display string. */
    private fun toTildePath(file: File, home: File): String {
        val homePath = home.canonicalPath
        val filePath = file.canonicalPath
        return if (filePath.startsWith(homePath)) "~${filePath.substring(homePath.length)}" else filePath
    }

    /** Checks the box by default if any install dir for this client already exists on disk. */
    private fun JBCheckBox.applyDefaultCheck(client: InstallClient): JBCheckBox {
        isSelected = JuggSkillInstaller.getInstallDirs(client, userHome).any { it.exists() }
        return this
    }

    override fun createCenterPanel(): JComponent = panel

    override fun doValidate(): ValidationInfo? {
        return if (selectedClients().isEmpty() && !installCliCheckBox.isSelected && !installHooksCheckBox.isSelected) {
            ValidationInfo("Select at least one install target (client, CLI, or hooks).", panel)
        } else {
            null
        }
    }

    override fun createLeftSideActions(): Array<Action> {
        return arrayOf(object : AbstractAction("Manual Setup Guide") {
            override fun actionPerformed(e: ActionEvent?) {
                try {
                    val logger = Logger.getInstance(InstallJuggSkillsDialog::class.java)
                    val outputFile = exportAndInstallSkills(projectDir, selectedOptions(), logger)
                    val virtualFile = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(outputFile)
                        ?: throw IllegalStateException("Failed to locate exported guide file.")
                    FileEditorManager.getInstance(project).openFile(virtualFile, true)
                    close(CLOSE_EXIT_CODE)
                } catch (t: Throwable) {
                    Messages.showErrorDialog("Failed to create guide file: ${t.message}", "Agent Setup Guide")
                }
            }
        })
    }

    fun selectedClients(): Set<InstallClient> {
        val selected = linkedSetOf<InstallClient>()
        if (codexCheckBox.isSelected) selected.add(InstallClient.CODEX)
        if (claudeCheckBox.isSelected) selected.add(InstallClient.CLAUDE)
        if (geminiCheckBox.isSelected) selected.add(InstallClient.GEMINI)
        if (codebuddyCheckBox.isSelected) selected.add(InstallClient.CODEBUDDY)
        if (cursorCheckBox.isSelected) selected.add(InstallClient.CURSOR)
        return selected
    }

    fun selectedOptions(): InstallOptions {
        return InstallOptions(
            selectedClients(),
            installCliCheckBox.isSelected,
            installHooksCheckBox.isSelected,
        )
    }

    companion object {
        internal fun descriptionHtml(): String {
            return "<html><body style='width:640px;color:#808080'>" +
                "Jugg Skills use Jugg CLI to enable AI agents (Claude Code, Codex, etc.) to drive the full " +
                "Android dev loop: edit → incremental compile → deploy → verify, without manual intervention." +
                "</body></html>"
        }

        internal fun selectAgentsTitle(): String = "Select agents to install:"

        internal fun additionalOptionsTitle(): String = "Additional options: (Recommended)"

        internal fun hooksInstallPathHint(): String = "~/.jugg/skills/hooks"

        fun showAndGetResult(project: Project, projectDir: File): InstallOptions {
            var result = InstallOptions(emptySet(), installCli = false, installHooks = false)
            ApplicationManager.getApplication().invokeAndWait {
                val dialog = InstallJuggSkillsDialog(project, projectDir)
                dialog.setOKButtonText("Install")
                if (dialog.showAndGet()) {
                    result = dialog.selectedOptions()
                }
            }
            return result
        }

        fun exportAndInstallSkills(
            projectDir: File,
            options: InstallOptions,
            logger: Logger,
            userHome: File = File(System.getProperty("user.home")),
        ): File {
            val shouldInstallCli = options.installCli || options.installHooks
            if (shouldInstallCli) {
                JuggSkillInstaller.installCli(logger, userHome)
            }
            if (options.clients.isNotEmpty()) {
                JuggSkillInstaller.install(projectDir, options.clients, logger, userHome)
            }
            if (options.installHooks) {
                JuggSkillInstaller.installHooks(logger, userHome)
                JuggHookInstaller.installForClaude(userHome, logger)
            }
            return ClientSetupDocExporter.export(projectDir, userHome)
        }

        fun installJuggMcpAndSkills(project: Project, projectDir: File, taskRunnerManager: TaskRunnerManager, logger: Logger) {
            val options = showAndGetResult(project, projectDir)
            if (options.isEmpty) {
                return
            }
            taskRunnerManager.runTaskSafe("Install Jugg Skills", {
                val shouldInstallCli = options.installCli || options.installHooks
                if (shouldInstallCli) {
                    JuggSkillInstaller.installCli(logger)
                }
                val skillSummary = if (options.clients.isNotEmpty()) {
                    JuggSkillInstaller.install(projectDir, options.clients, logger)
                } else {
                    InstallSummary(emptyList())
                }
                val hookSummary = if (options.installHooks) {
                    JuggSkillInstaller.installHooks(logger)
                    JuggHookInstaller.installForClaude(logger = logger)
                } else {
                    HookInstallSummary(emptyList())
                }
                val title: String = if ((skillSummary.results.isEmpty() || skillSummary.isAllSuccess) &&
                    (hookSummary.results.isEmpty() || hookSummary.isAllSuccess)
                ) {
                    "Install Completed"
                } else {
                    "Install Completed with Issues"
                }
                ApplicationManager.getApplication().invokeLater {
                    val displayText = buildString {
                        if (shouldInstallCli) {
                            val reason = if (options.installHooks && !options.installCli) {
                                " (installed automatically for hooks)"
                            } else {
                                ""
                            }
                            appendLine("Jugg CLI installed$reason. Try \"jugg -h\" in terminal.")
                        }
                        if (skillSummary.results.isNotEmpty()) appendLine(skillSummary.toDisplayText())
                        if (hookSummary.results.isNotEmpty()) append(hookSummary.toDisplayText())
                    }
                    Messages.showInfoMessage(project, displayText, title)
                }
            }, isNeedShowIndicator = true, isBlockIncrementalCompile = false)
        }
    }
}
