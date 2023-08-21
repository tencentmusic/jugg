package com.sickworm.intellij.jugg.gradle.compile
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.util.ui.JBUI
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import javax.swing.*

class UserAndPasswordInputDialog(content: String) : DialogWrapper(true) {
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

        mainPanel.add(JLabel("$content: "), constraints)

        constraints.gridx = 1
        mainPanel.add(passwordField, constraints)

        isResizable = false
        init()
    }

    override fun createCenterPanel(): JComponent {
        return mainPanel
    }

    fun showAndGetResult(): String? {
        if (!showAndGet()) {
            return null
        }
        return passwordField.text
    }
}

