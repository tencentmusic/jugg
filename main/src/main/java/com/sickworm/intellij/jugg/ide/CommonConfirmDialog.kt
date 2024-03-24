package com.sickworm.intellij.jugg.ide

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import javax.swing.Action
import javax.swing.JComponent
import javax.swing.JPanel

class CommonConfirmDialog(
    titleArg: String,
    content: String,
    private val okButtonText: String?,
    private val cancelButtonText: String?,
    private val isShowCancelButton: Boolean,
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

    override fun createActions(): Array<Action> {
        if (okButtonText != null) {
            setOKButtonText(okButtonText)
        }
        if (cancelButtonText != null) {
            setCancelButtonText(cancelButtonText)
        }
        return super.createActions().filter {
            if (!isShowCancelButton) {
                it != cancelAction
            } else {
                true
            }
        }.toTypedArray()
    }

    companion object {

        fun showAndGetResult(title: String,
                             content: String,
                             okButtonText: String? = null,
                             cancelButtonText: String? = null,
                             isShowCancelButton: Boolean = true,
        ): Boolean {
            var result = false
            ApplicationManager.getApplication().invokeAndWait {
                val dialog = CommonConfirmDialog(title, content, okButtonText, cancelButtonText, isShowCancelButton)
                result = dialog.showAndGet()
            }
            return result
        }
    }

}
