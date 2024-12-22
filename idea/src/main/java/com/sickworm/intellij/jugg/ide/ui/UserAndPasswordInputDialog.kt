package com.sickworm.intellij.jugg.ide.ui
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.util.ui.JBUI
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import javax.swing.*

class UserAndPasswordInputDialog(
    content: String,
    subTitle: String?,
    isPassword: Boolean,
    defaultInputText: String?,
    title: String,
) : DialogWrapper(true) {
    private val mainPanel: JPanel
    private val textField: JTextField

    init {
        this.title = title
        mainPanel = JPanel(GridBagLayout())
        textField = if (isPassword) {
            JPasswordField()
        } else {
            JTextField()
        }
        textField.preferredSize = JBUI.size(180, 30)
        if (defaultInputText != null) {
            textField.text = defaultInputText
        }

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
        mainPanel.add(textField, constraints)

        init()
    }

    override fun createCenterPanel(): JComponent {
        return mainPanel
    }

    companion object {

        fun showAndGetResult(content: String,
                             subTitle: String? = null,
                             isPassword: Boolean = false,
                             defaultInputText: String? = null,
                             title: String? = null,
        ): String? {
            var result: String? = null
            ApplicationManager.getApplication().invokeAndWait {
                val dialog = UserAndPasswordInputDialog(content, subTitle, isPassword, defaultInputText,
                    title ?: "Please input your $content")
                if (dialog.showAndGet()) {
                    result = dialog.textField.text
                }
            }
            return result
        }
    }

}

