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
import com.sickworm.intellij.jugg.ide.logic.ClientSetupDocExporter
import com.sickworm.intellij.jugg.ide.logic.InstallClient
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

    private val codexCheckBox = JBCheckBox("Codex")
    @Suppress("DialogTitleCapitalization")
    private val claudeCheckBox = JBCheckBox("Claude Code")
    private val geminiCheckBox = JBCheckBox("Gemini")
    private val panel = JPanel(GridBagLayout())

    init {
        title = "Install Jugg MCP and Skills"

        val constraints = GridBagConstraints()
        constraints.gridx = 0
        constraints.gridy = 0
        constraints.anchor = GridBagConstraints.WEST
        constraints.insets = JBUI.emptyInsets()
        panel.add(JLabel("Select clients to install:"), constraints)

        constraints.gridy++
        panel.add(codexCheckBox, constraints)
        constraints.gridy++
        panel.add(claudeCheckBox, constraints)
        constraints.gridy++
        panel.add(geminiCheckBox, constraints)

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
                    val outputFile = ClientSetupDocExporter.export(projectDir)
                    val virtualFile = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(outputFile)
                        ?: throw IllegalStateException("Failed to locate exported guide file.")
                    FileEditorManager.getInstance(project).openFile(virtualFile, true)
                } catch (t: Throwable) {
                    Messages.showErrorDialog("Failed to create guide file: ${t.message}", "Client Setup Guide")
                }
            }
        })
    }

    fun selectedClients(): Set<InstallClient> {
        val selected = linkedSetOf<InstallClient>()
        if (codexCheckBox.isSelected) {
            selected.add(InstallClient.CODEX)
        }
        if (claudeCheckBox.isSelected) {
            selected.add(InstallClient.CLAUDE)
        }
        if (geminiCheckBox.isSelected) {
            selected.add(InstallClient.GEMINI)
        }
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
    }
}
