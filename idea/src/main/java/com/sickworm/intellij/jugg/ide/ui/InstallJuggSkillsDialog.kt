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
import com.sickworm.intellij.jugg.ide.logic.ClientSetupDocExporter
import com.sickworm.intellij.jugg.ide.logic.InstallClient
import com.sickworm.intellij.jugg.ide.logic.InstallOptions
import com.sickworm.intellij.jugg.ide.logic.InstallSummary
import com.sickworm.intellij.jugg.ide.logic.JuggRunningTask
import com.sickworm.intellij.jugg.ide.logic.JuggSkillInstaller
import com.sickworm.intellij.jugg.project.TaskRunnerManager
import java.awt.Color
import java.awt.FlowLayout
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
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
    private val installCliCheckBox = JBCheckBox("Install CLI to PATH").apply { isSelected = true }
    private val panel = JPanel(GridBagLayout())

    init {
        title = "Install Jugg Skills"

        val constraints = GridBagConstraints().apply {
            gridx = 0
            gridy = 0
            anchor = GridBagConstraints.WEST
            insets = JBUI.emptyInsets()
        }
        val descLabel = JLabel("<html><body style='width:360px'>" +
            "Jugg Skills use Jugg CLI to enable AI agents (Claude Code, Codex, etc.) to drive the full " +
            "Android dev loop: edit → incremental compile → deploy → verify, without manual intervention. " +
            "</body></html>")
        descLabel.foreground = Color(100, 100, 100)
        panel.add(descLabel, constraints)

        constraints.gridy++
        constraints.insets = Insets(8, 0, 0, 0)
        panel.add(JLabel("Select clients to install:"), constraints)

        val clientRows = listOf(
            claudeCheckBox to InstallClient.CLAUDE,
            codexCheckBox to InstallClient.CODEX,
            geminiCheckBox to InstallClient.GEMINI,
            codebuddyCheckBox to InstallClient.CODEBUDDY,
            cursorCheckBox to InstallClient.CURSOR,
        )
        for ((checkBox, client) in clientRows) {
            constraints.gridy++
            constraints.insets = Insets(2, 0, 0, 0)
            panel.add(buildClientRow(checkBox, client), constraints)
        }

        constraints.gridy++
        constraints.insets = Insets(6, 0, 0, 0)
        panel.add(buildCliRow(), constraints)

        init()
    }

    /** Builds a row: [checkbox] [grey path hint] for a skill client. */
    private fun buildClientRow(checkBox: JBCheckBox, client: InstallClient): JPanel {
        val dirs = JuggSkillInstaller.getInstallDirs(client, userHome)
        val hintText = dirs.joinToString("  ") { toTildePath(it, userHome) }
        return rowPanel(checkBox, hintText)
    }

    /** Builds the CLI install row with a fixed path hint. */
    private fun buildCliRow(): JPanel {
        return rowPanel(installCliCheckBox, "~/.jugg/bin")
    }

    private fun rowPanel(checkBox: JBCheckBox, hintText: String): JPanel {
        val row = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0))
        row.add(checkBox)
        if (hintText.isNotEmpty()) {
            val hint = JLabel("  $hintText")
            hint.foreground = Color(128, 128, 128)
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
        return if (selectedClients().isEmpty() && !installCliCheckBox.isSelected) {
            ValidationInfo("Select at least one client or enable CLI install.", panel)
        } else {
            null
        }
    }

    override fun createLeftSideActions(): Array<Action> {
        return arrayOf(object : AbstractAction("Manual Setup Guide") {
            override fun actionPerformed(e: ActionEvent?) {
                try {
                    val logger = Logger.getInstance(InstallJuggSkillsDialog::class.java)
                    val outputFile = exportAndInstallSkills(projectDir, selectedClients(), logger)
                    val virtualFile = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(outputFile)
                        ?: throw IllegalStateException("Failed to locate exported guide file.")
                    FileEditorManager.getInstance(project).openFile(virtualFile, true)
                    close(CLOSE_EXIT_CODE)
                } catch (t: Throwable) {
                    Messages.showErrorDialog("Failed to create guide file: ${t.message}", "Client Setup Guide")
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
        return InstallOptions(selectedClients(), installCliCheckBox.isSelected)
    }

    companion object {
        fun showAndGetResult(project: Project, projectDir: File): InstallOptions {
            var result = InstallOptions(emptySet(), false)
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
            selectedClients: Set<InstallClient>,
            logger: Logger,
            userHome: File = File(System.getProperty("user.home")),
        ): File {
            JuggSkillInstaller.install(projectDir, selectedClients, logger, userHome)
            return ClientSetupDocExporter.export(projectDir)
        }

        fun installJuggMcpAndSkills(project: Project, projectDir: File, taskRunnerManager: TaskRunnerManager, logger: Logger) {
            val options = showAndGetResult(project, projectDir)
            if (options.isEmpty) {
                return
            }
            taskRunnerManager.runTaskSafe("Install Jugg Skills", {
                if (options.installCli) {
                    JuggSkillInstaller.installCli(logger)
                }
                val summary = if (options.clients.isNotEmpty()) {
                    JuggSkillInstaller.install(projectDir, options.clients, logger)
                } else {
                    InstallSummary(emptyList())
                }
                val title: String
                val balloonMessage: String
                if (summary.results.isEmpty() || summary.isAllSuccess) {
                    title = "Install Completed"
                    balloonMessage = "Jugg installation completed successfully."
                } else {
                    title = "Install Completed with Issues"
                    balloonMessage = "Jugg installation finished with issues."
                }
                JuggRunningTask.notifyByBalloon(project, balloonMessage)
                ApplicationManager.getApplication().invokeLater {
                    val displayText = buildString {
                        if (options.installCli) appendLine("Jugg CLI installed. Try \"jugg -h\" in terminal.")
                        if (summary.results.isNotEmpty()) append(summary.toDisplayText())
                    }
                    Messages.showInfoMessage(project, displayText, title)
                }
            }, isNeedShowIndicator = true, isBlockIncrementalCompile = false)
        }
    }
}
