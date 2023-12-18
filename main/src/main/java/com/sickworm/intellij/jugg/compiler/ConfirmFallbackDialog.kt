package com.sickworm.intellij.jugg.compiler

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.sickworm.intellij.jugg.ide.JuggSettings
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import javax.swing.*

class ConfirmFallbackDialog(
) : DialogWrapper(true) {
    private val mainPanel: JPanel = JPanel(GridBagLayout())
    val checkBox: JBCheckBox = JBCheckBox("Don't ask me next time")

    init {
        title = "Confirm Fallback to Gradle"

        val constraints = GridBagConstraints()
        constraints.gridx = 0
        constraints.gridy = 0
        constraints.fill = GridBagConstraints.HORIZONTAL

        constraints.insets = JBUI.insetsBottom(12)
        constraints.gridwidth = 1
        mainPanel.add(JBLabel("No file changes, continue will fallback to gradle."), constraints)
        constraints.gridy++

        constraints.insets = JBUI.insetsBottom(4)
        constraints.gridwidth = 1
        mainPanel.add(checkBox, constraints)

        isResizable = false
        init()
    }

    override fun createActions(): Array<Action> {
        setOKButtonText("Continue Fallback")
        return super.createActions()
    }

    override fun createCenterPanel(): JComponent {
        return mainPanel
    }

    companion object {

        fun showAndGetResult(): Boolean {
            if (!JuggSettings.isConfirmFallbackWhenNoFileChanges) {
                return true
            }

            var result = false
            ApplicationManager.getApplication().invokeAndWait {
                val dialog = ConfirmFallbackDialog()
                if (dialog.showAndGet()) {
                    val isChecked = dialog.checkBox.isSelected
                    if (isChecked) {
                        JuggSettings.isConfirmFallbackWhenNoFileChanges = false
                    }
                    result = true
                } else {
                    result = false
                }
            }
            return result
        }
    }

}
