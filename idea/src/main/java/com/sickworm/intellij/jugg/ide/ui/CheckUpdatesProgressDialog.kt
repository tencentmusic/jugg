package com.sickworm.intellij.jugg.ide.ui

import com.intellij.openapi.ui.DialogWrapper
import com.intellij.util.ui.JBUI
import com.sickworm.intellij.jugg.server.protocols.HotUpdateData
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Toolkit
import java.awt.datatransfer.Clipboard
import java.awt.datatransfer.StringSelection
import javax.swing.*


class CheckUpdatesProgressDialog : DialogWrapper(true) {

    private val mainPanel: JPanel = JPanel(GridBagLayout())
    private val textLabel: JLabel = JLabel("Checking for updates...")

    private val progressBar: JProgressBar = JProgressBar().also {
        it.isIndeterminate = true
    }

    init {
        title = "Check Updates"

        val constraints = GridBagConstraints()
        constraints.gridx = 0
        constraints.gridy = 0
        constraints.fill = GridBagConstraints.HORIZONTAL
        constraints.insets = JBUI.insets(12, 12, 12, 6)
        mainPanel.add(textLabel, constraints)

        constraints.gridx = 1
        constraints.insets = JBUI.insets(12, 6, 12, 12)
        constraints.weightx = 1.0
        mainPanel.add(progressBar, constraints)

        isOKActionEnabled = false
        isResizable = false
        init()
    }

    override fun createActions(): Array<Action> {
        setOKButtonText("Update")
        setCancelButtonText("Cancel")
        return arrayOf(okAction, cancelAction)
    }

    override fun createCenterPanel(): JComponent {
        return mainPanel
    }

    fun setHotUpdateData(hotUpdateData: HotUpdateData?, onConfirmUpdate: () -> Unit) {
        progressBar.isVisible = false
        SwingUtilities.invokeLater {
            if (hotUpdateData == null || !hotUpdateData.isNeedUpdate) {
                textLabel.text = "Jugg is already the latest version."
                getButton(okAction)?.isVisible = false
                getButton(cancelAction)?.isVisible = true
                setCancelButtonText("Close")
            } else {
                textLabel.text = "<html>New version available: <b>${hotUpdateData.targetVersion}</b>. Confirm update?</html>"
                okAction.isEnabled = true
                cancelAction.isEnabled = true
                setCancelButtonText("Cancel")
                getButton(okAction)?.removeAll()
                getButton(okAction)?.addActionListener {
                    startDownload(hotUpdateData.targetVersion)
                    onConfirmUpdate()
                }
            }
        }
    }

    private fun startDownload(targetVersion: String) {
        progressBar.isVisible = true
        okAction.isEnabled = false
        textLabel.text = "Downloading $targetVersion..."
    }

    fun setResult(newVersion: String, isSuccess: Boolean, failedReason: String?, onConfirmReopenProject: (() -> Unit)?) {
        setCancelButtonText("Close")
        SwingUtilities.invokeLater {
            progressBar.isVisible = false
            if (isSuccess) {
                textLabel.text = "Update to $newVersion successful. Reopen your project to apply the update."
                getButton(okAction)?.text = "Reopen projects"
                okAction.isEnabled = true
                getButton(okAction)?.removeAll()
                getButton(okAction)?.addActionListener {
                    onConfirmReopenProject?.invoke()
                }

                getButton(cancelAction)?.isVisible = false
            } else {
                textLabel.text = "Update failed, reason: $failedReason. Please report to the admin."
                getButton(okAction)?.isVisible = false
                getButton(cancelAction)?.isVisible = true
            }
        }
    }

    private fun saveTextToClipboard(text: String) {
        val clipboard: Clipboard = Toolkit.getDefaultToolkit().systemClipboard
        val stringSelection = StringSelection(text)
        clipboard.setContents(stringSelection, null)
    }
}

