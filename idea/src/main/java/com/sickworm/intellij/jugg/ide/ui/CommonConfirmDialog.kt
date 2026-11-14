package com.sickworm.intellij.jugg.ide.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.labels.LinkLabel
import com.intellij.util.ui.JBUI
import com.sickworm.intellij.jugg.ide.bean.ConfirmResult
import java.awt.*
import java.awt.event.ActionEvent
import java.awt.event.WindowEvent
import javax.swing.*
import kotlin.math.min


class CommonConfirmDialog(
    titleArg: String,
    content: String,
    private val okButtonText: String?,
    private val cancelButtonText: String?,
    private val isShowCancelButton: Boolean,
    private val leftButtonText: String?,
    isShowDoNotAsk: Boolean,
    checkBoxText: String?,
    private val linkActions: List<CustomLinkAction> = emptyList(),
) : DialogWrapper(true) {

    private val mainPanel: JPanel = JPanel(GridBagLayout())
    val checkBox: JBCheckBox = JBCheckBox(checkBoxText ?: "Don't ask me next time")

    var isClickLeftButton: Boolean = false
        private set
    var isClickCloseButton: Boolean = false
        private set
    var isClickLinkButton: Boolean = false
        private set

    init {
        title = titleArg

        val constraints = GridBagConstraints()
        constraints.gridx = 0
        constraints.gridy = 0
        constraints.fill = GridBagConstraints.BOTH
        constraints.weightx = 1.0
        constraints.weighty = 1.0

        constraints.insets = JBUI.insets(4, 0, 12, 0)
        constraints.gridwidth = 1

        val label = JBLabel()
        val contentView = object : JTextPane() {
            override fun getScrollableTracksViewportWidth(): Boolean = true
        }.apply {
            contentType = if (content.startsWith("<html>", ignoreCase = true)) "text/html" else "text/plain"
            text = content
            isEditable = false
            isOpaque = false
            isFocusable = false
            border = null
            font = label.font
            foreground = label.foreground
            putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, true)
            caretPosition = 0
        }

        val screenSize = Toolkit.getDefaultToolkit().screenSize
        val contentWidth = min(contentView.preferredSize.width, JBUI.scale(560))
        contentView.setSize(contentWidth, screenSize.height)
        val contentHeight = min(contentView.preferredSize.height, screenSize.height / 2)
        val jScrollPane = JScrollPane(contentView).apply {
            border = null
            horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
            verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
            preferredSize = Dimension(contentWidth, contentHeight)
        }
        mainPanel.add(jScrollPane, constraints)
        constraints.gridy++
        constraints.weighty = 0.0
        constraints.fill = GridBagConstraints.HORIZONTAL

        // link buttons
        if (linkActions.isNotEmpty()) {
            val linkPanel = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0))
            linkActions.forEachIndexed { index, customLinkAction ->
                val linkLabel = LinkLabel.create(customLinkAction.name) {
                    isClickLinkButton = true
                    customLinkAction.onClick(this)
                }
                linkPanel.add(linkLabel)

                // add separator
                if (index < linkActions.size - 1) {
                    linkPanel.add(JLabel(" | "))
                }
            }
            mainPanel.add(linkPanel, constraints)
            constraints.gridy++
        }

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
                               linkActions: List<CustomLinkAction> = emptyList(),
                               checkBoxText: String? = null,
                               checkBoxSelectionAction: ((Boolean) -> Unit)? = null,
        ): ConfirmResult {
            var result: ConfirmResult = ConfirmResult.NEGATIVE
            ApplicationManager.getApplication().invokeAndWait {
                val isShowDoNotAsk = doNotAskAction != null || checkBoxText != null
                val dialog = CommonConfirmDialog(
                    title,
                    content,
                    okButtonText,
                    negativeButtonText,
                    isShowCancelButton,
                    leftButtonText,
                    isShowDoNotAsk,
                    checkBoxText,
                    linkActions
                )
                if (dialog.showAndGet()) {
                    result = ConfirmResult.POSITIVE
                } else if (dialog.isClickCloseButton) {
                    result = ConfirmResult.CANCEL
                } else if (dialog.isClickLeftButton) {
                    result = ConfirmResult.LEFT
                } else if (dialog.isClickLinkButton) {
                    result = ConfirmResult.LINK_ACTION
                }

                if (isShowDoNotAsk && dialog.checkBox.isSelected) {
                    doNotAskAction?.invoke()
                }
                checkBoxSelectionAction?.invoke(result == ConfirmResult.POSITIVE && dialog.checkBox.isSelected)
            }
            return result
        }

    }

    class CustomLinkAction(
        val name: String,
        val onClick: (DialogWrapper) -> Unit,
    )
}
