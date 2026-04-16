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
import com.sickworm.intellij.jugg.ide.logic.JuggRunningTask
import com.sickworm.intellij.jugg.ide.logic.JuggSkillInstaller
import com.sickworm.intellij.jugg.project.TaskRunnerManager
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
class InstallMcpAndSkillsDialog(
    private val project: Project,
    private val projectDir: File,
) : DialogWrapper(true) {

    @Suppress("DialogTitleCapitalization")
    private val claudeCheckBox = JBCheckBox("Claude Code")
    private val codexCheckBox = JBCheckBox("Codex")
    private val geminiCheckBox = JBCheckBox("Gemini")
    private val codebuddyCheckBox = JBCheckBox("CodeBuddy")
    private val panel = JPanel(GridBagLayout())

    init {
        title = "Install Jugg Skills"

        val constraints = GridBagConstraints()
        constraints.gridx = 0
        constraints.gridy = 0
        constraints.anchor = GridBagConstraints.WEST
        constraints.insets = JBUI.emptyInsets()
        panel.add(JLabel("Select clients to install:"), constraints)

        constraints.gridy++
        panel.add(claudeCheckBox, constraints)
        constraints.gridy++
        panel.add(codexCheckBox, constraints)
        constraints.gridy++
        panel.add(geminiCheckBox, constraints)
        constraints.gridy++
        panel.add(codebuddyCheckBox, constraints)

        init()
    }

    override fun createCenterPanel(): JComponent {
        return panel
    }

    override fun doValidate(): ValidationInfo? {
        return if (selectedClients().isEmpty()) {
            ValidationInfo("Select at least one client.", panel)
        } else {
            null
        }
    }

    override fun createLeftSideActions(): Array<Action> {
        return arrayOf(object : AbstractAction("Manual Setup Guide") {
            override fun actionPerformed(e: ActionEvent?) {
                try {
                    val logger = Logger.getInstance(InstallMcpAndSkillsDialog::class.java)
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
        return selected
    }

    companion object {
        fun showAndGetResult(project: Project, projectDir: File): Set<InstallClient> {
            var result: Set<InstallClient> = emptySet()
            ApplicationManager.getApplication().invokeAndWait {
                val dialog = InstallMcpAndSkillsDialog(project, projectDir)
                dialog.setOKButtonText("Install")
                if (dialog.showAndGet()) {
                    result = dialog.selectedClients()
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
            val selectedClients = showAndGetResult(project, projectDir)
            if (selectedClients.isEmpty()) {
                return
            }
            taskRunnerManager.runTaskSafe("Install Jugg Skills", {
                val summary = JuggSkillInstaller.install(projectDir, selectedClients, logger)
                val title: String
                val balloonMessage: String
                if (summary.isAllSuccess) {
                    title = "Install Completed"
                    balloonMessage = "Jugg skills installed successfully."
                } else {
                    title = "Install Completed with Issues"
                    balloonMessage = "Jugg skills installation finished with issues."
                }
                JuggRunningTask.notifyByBalloon(project, balloonMessage)
                ApplicationManager.getApplication().invokeLater {
                    Messages.showInfoMessage(project, summary.toDisplayText(), title)
                }
            }, isNeedShowIndicator = true, isBlockIncrementalCompile = false)
        }
    }
}
