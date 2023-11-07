package com.sickworm.intellij.jugg.gradle.compile
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.util.ui.JBUI
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import javax.swing.*

class UserAndPasswordInputDialog(content: String, subTitle: String? = null) : DialogWrapper(true) {
    private val mainPanel: JPanel
    private val passwordField: JTextField

    init {
        title = "Please input your $content"
        mainPanel = JPanel(GridBagLayout())
        passwordField = JTextField(20)

        val constraints = GridBagConstraints()
        constraints.gridx = 0
        constraints.gridy = 0
        constraints.fill = GridBagConstraints.HORIZONTAL
        constraints.insets = JBUI.insets(10)

        if (subTitle != null) {
            constraints.gridwidth = 2
            constraints.insets = JBUI.insets(10, 10, 4, 10)
            mainPanel.add(JLabel(subTitle), constraints)
            constraints.gridy++
            constraints.insets = JBUI.insets(10, 10, 4, 10)
        }

        constraints.gridwidth = 1
        mainPanel.add(JLabel("$content: "), constraints)

        constraints.gridx = 1
        mainPanel.add(passwordField, constraints)

        isResizable = false
        init()
    }

    override fun createCenterPanel(): JComponent {
        return mainPanel
    }

    companion object {

        fun showAndGetResult(content: String, subTitle: String? = null): String? {
            var result: String? = null
            ApplicationManager.getApplication().invokeAndWait {
                val dialog = UserAndPasswordInputDialog(content, subTitle)
                if (dialog.showAndGet()) {
                    result = dialog.passwordField.text
                }
            }
            return result
        }
    }

}

