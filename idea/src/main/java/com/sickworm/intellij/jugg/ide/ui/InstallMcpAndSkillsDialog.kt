package com.sickworm.intellij.jugg.ide.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.ValidationInfo
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
    private val projectDir: File,
) : DialogWrapper(true) {

    private val codexCheckBox = JBCheckBox("Codex")
    private val claudeCheckBox = JBCheckBox("Claude Code")
    private val geminiCheckBox = JBCheckBox("Gemini")
    private val panel = JPanel(GridBagLayout())

    init {
        title = "Install Jugg MCP and skills"

        val constraints = GridBagConstraints()
        constraints.gridx = 0
        constraints.gridy = 0
        constraints.anchor = GridBagConstraints.WEST
        constraints.insets = JBUI.insets(4, 0, 4, 0)
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
        return arrayOf(object : AbstractAction("View all") {
            override fun actionPerformed(e: ActionEvent?) {
                try {
                    val outputFile = ClientSetupDocExporter.export(projectDir)
                    Messages.showInfoMessage(
                        "Guide file created:\n${outputFile.path}",
                        "Client Setup Guide",
                    )
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
        fun showAndGetResult(projectDir: File): Set<InstallClient> {
            var result: Set<InstallClient> = emptySet()
            ApplicationManager.getApplication().invokeAndWait {
                val dialog = InstallMcpAndSkillsDialog(projectDir)
                dialog.setOKButtonText("Install")
                if (dialog.showAndGet()) {
                    result = dialog.selectedClients()
                }
            }
            return result
        }
    }
}
