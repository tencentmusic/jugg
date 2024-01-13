package com.sickworm.intellij.jugg.ide

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import javax.swing.JComponent
import javax.swing.JPanel

class CommonConfirmDialog(
    titleArg: String,
    content: String,
) : DialogWrapper(true) {

    private val mainPanel: JPanel = JPanel(GridBagLayout())

    init {
        title = titleArg

        val constraints = GridBagConstraints()
        constraints.gridx = 0
        constraints.gridy = 0
        constraints.fill = GridBagConstraints.HORIZONTAL

        constraints.insets = JBUI.insetsBottom(12)
        constraints.gridwidth = 1
        mainPanel.add(JBLabel(content), constraints)
        constraints.gridy++

        isResizable = true
        init()
    }

    override fun createCenterPanel(): JComponent {
        return mainPanel
    }

    companion object {

        fun showAndGetResult(title: String, content: String): Boolean {
            var result = false
            ApplicationManager.getApplication().invokeAndWait {
                val dialog = CommonConfirmDialog(title, content)
                result = dialog.showAndGet()
            }
            return result
        }
    }

}
