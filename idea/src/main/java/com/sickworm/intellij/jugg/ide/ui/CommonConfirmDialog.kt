package com.sickworm.intellij.jugg.ide.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBLabel
import com.sickworm.intellij.jugg.ide.ConfirmResult
import java.awt.*
import java.awt.event.ActionEvent
import java.awt.event.WindowEvent
import javax.swing.*


class CommonConfirmDialog(
    titleArg: String,
    content: String,
    private val okButtonText: String?,
    private val cancelButtonText: String?,
    private val isShowCancelButton: Boolean,
    private val leftButtonText: String?,
) : DialogWrapper(true) {

    private val mainPanel: JPanel = JPanel(BorderLayout())

    var isClickLeftButton: Boolean = false
        private set
    var isClickCloseButton: Boolean = false
        private set

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
        val actions = super.createActions().filter {
            if (!isShowCancelButton) {
                it != cancelAction
            } else {
                true
            }
        }.toMutableList()

        return actions.toTypedArray()
    }

    override fun doCancelAction(source: AWTEvent?) {
        super.doCancelAction(source)
        isClickCloseButton = (source as? WindowEvent)?.id == WindowEvent.WINDOW_CLOSING
    }

    override fun doOKAction() {
        super.doOKAction()
        isClickCloseButton = false
    }

    override fun createLeftSideActions(): Array<Action> {
        if (leftButtonText != null) {
            return arrayOf(object : AbstractAction(leftButtonText) {
                override fun actionPerformed(e: ActionEvent?) {
                    isClickLeftButton = true
                    close(CLOSE_EXIT_CODE)
                }
            })
        }

        return super.createLeftSideActions()
    }

    companion object {

        fun showAndGetResult(title: String,
                             content: String,
                             okButtonText: String? = null,
                             cancelButtonText: String? = null,
                             isShowCancelButton: Boolean = true,
        ): Boolean {
            return showAndGetOrCancel(title, content, okButtonText, cancelButtonText, isShowCancelButton) == ConfirmResult.POSITIVE
        }

        fun showAndGetOrCancel(title: String,
                             content: String,
                             okButtonText: String? = null,
                             cancelButtonText: String? = null,
                             isShowCancelButton: Boolean = true,
        ): ConfirmResult {
            var result: ConfirmResult = ConfirmResult.NEGATIVE
            ApplicationManager.getApplication().invokeAndWait {
                val dialog = CommonConfirmDialog(title, content, okButtonText, cancelButtonText, isShowCancelButton, null)
                if (dialog.showAndGet()) {
                    result = ConfirmResult.POSITIVE
                } else if (dialog.isClickCloseButton) {
                    result = ConfirmResult.CANCEL
                }
            }
            return result
        }

    }
}
