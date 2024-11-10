package com.sickworm.intellij.jugg.ide.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
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
    isShowDoNotAsk: Boolean,
) : DialogWrapper(true) {

    private val mainPanel: JPanel = JPanel(GridBagLayout())
    val checkBox: JBCheckBox = JBCheckBox("Don't ask me next time")

    var isClickLeftButton: Boolean = false
        private set
    var isClickCloseButton: Boolean = false
        private set

    init {
        title = titleArg

        val constraints = GridBagConstraints()
        constraints.gridx = 0
        constraints.gridy = 0
        constraints.fill = GridBagConstraints.HORIZONTAL

        constraints.insets = JBUI.insets(4, 0, 12, 0)
        constraints.gridwidth = 1

        val jLabel = JBLabel(content)
        val jScrollPane = JScrollPane(jLabel)
        jScrollPane.border = null

        // maximumSize not worked, preferredSize won't auto size
//        val screenSize = Toolkit.getDefaultToolkit().screenSize
//        mainPanel.preferredSize = Dimension(screenSize.width / 2, screenSize.height / 2)
        mainPanel.add(jScrollPane, constraints)
        constraints.gridy++

        if (isShowDoNotAsk) {
            constraints.insets = JBUI.insetsBottom(4)
            constraints.gridwidth = 1
            mainPanel.add(checkBox, constraints)
        }

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
        if ((source as? WindowEvent)?.id == WindowEvent.WINDOW_CLOSING) {
            isClickCloseButton = true
        } else if ((source as? ActionEvent)?.actionCommand == null) {
            isClickCloseButton = true
        }
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
                               negativeButtonText: String? = null,
                               isShowCancelButton: Boolean = true,
                               leftButtonText: String? = null,
                               doNotAskAction: (() -> Unit)? = null,
        ): ConfirmResult {
            var result: ConfirmResult = ConfirmResult.NEGATIVE
            ApplicationManager.getApplication().invokeAndWait {
                val isShowDoNotAsk = doNotAskAction != null
                val dialog = CommonConfirmDialog(title, content, okButtonText, negativeButtonText, isShowCancelButton, leftButtonText, isShowDoNotAsk)
                if (dialog.showAndGet()) {
                    result = ConfirmResult.POSITIVE
                } else if (dialog.isClickCloseButton) {
                    result = ConfirmResult.CANCEL
                } else if (dialog.isClickLeftButton) {
                    result = ConfirmResult.LEFT
                }

                if (isShowDoNotAsk && dialog.checkBox.isSelected) {
                    doNotAskAction?.invoke()
                }
            }
            return result
        }

    }
}
