package com.sickworm.intellij.jugg.ide

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import java.awt.*
import javax.swing.*


class CommonConfirmDialog(
    titleArg: String,
    content: String,
    private val okButtonText: String?,
    private val cancelButtonText: String?,
    private val isShowCancelButton: Boolean,
) : DialogWrapper(true) {

    private val mainPanel: JPanel = JPanel(BorderLayout())

    init {
        title = titleArg

        val jLabel = JBLabel(content)
        val jScrollPane = JScrollPane(jLabel)
        jScrollPane.border = null

        // maximumSize not worked, preferredSize won't auto size
//        val screenSize = Toolkit.getDefaultToolkit().screenSize
//        mainPanel.preferredSize = Dimension(screenSize.width / 2, screenSize.height / 2)
        mainPanel.add(jScrollPane)
        mainPanel.add(jScrollPane, BorderLayout.CENTER)

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
