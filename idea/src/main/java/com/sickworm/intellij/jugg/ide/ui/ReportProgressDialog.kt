package com.sickworm.intellij.jugg.ide.ui

import com.intellij.openapi.ui.DialogWrapper
import com.intellij.util.ui.JBUI
import com.sickworm.intellij.jugg.ide.bean.JuggSettings
import com.sickworm.intellij.jugg.server.UploadResult
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Toolkit
import java.awt.datatransfer.Clipboard
import java.awt.datatransfer.StringSelection
import javax.swing.*


class ReportProgressDialog : DialogWrapper(true) {

    private val mainPanel: JPanel = JPanel(GridBagLayout())
    private val uploadingLabel: JLabel = JLabel("Uploading...")

    private val progressBar: JProgressBar = JProgressBar().also {
        it.isIndeterminate = true
    }

    init {
        title = "Reporting Issue"

        val constraints = GridBagConstraints()
        constraints.gridx = 0
        constraints.gridy = 0
        constraints.fill = GridBagConstraints.HORIZONTAL
        constraints.insets = JBUI.insets(12, 12, 12, 6)
        mainPanel.add(uploadingLabel, constraints)

        constraints.gridx = 1
        constraints.insets = JBUI.insets(12, 6, 12, 12)
        constraints.weightx = 1.0
        mainPanel.add(progressBar, constraints)

        isOKActionEnabled = false
        isResizable = false
        init()
    }

    override fun createActions(): Array<Action> {
        setOKButtonText("Copy Issue ID and Close")
        return arrayOf(okAction)
    }

    override fun createCenterPanel(): JComponent {
        return mainPanel
    }

    fun setProgress(message: String) {
        SwingUtilities.invokeLater {
            uploadingLabel.text = message
        }
    }

    fun setResult(uploadResult: UploadResult) {
        SwingUtilities.invokeLater {
            doSetResult(uploadResult)
        }
    }

    private fun doSetResult(uploadResult: UploadResult) {
        val text = if (uploadResult.isSuccess) {
            "<html>Report success. Report ID: ${uploadResult.reportId ?: "null"}<br/>Server: ${JuggSettings.serverUrl}</html>"
        } else {
            setOKButtonText("Copy Error and Close")
            "<html>Report failed. Error: ${uploadResult.errorMessage}<br/>Server: ${JuggSettings.serverUrl}</html>"
        }

        val copyText = if (uploadResult.isSuccess) {
            uploadResult.reportId ?: ""
        } else {
            text
        }
        getButton(okAction)?.addActionListener {
            saveTextToClipboard("Jugg report ID: $copyText\nServer: ${JuggSettings.serverUrl}")
        }

        isOKActionEnabled = true
        uploadingLabel.text = text
        mainPanel.remove(progressBar)
        mainPanel.revalidate()
        mainPanel.repaint()
    }

    private fun saveTextToClipboard(text: String) {
        val clipboard: Clipboard = Toolkit.getDefaultToolkit().systemClipboard
        val stringSelection = StringSelection(text)
        clipboard.setContents(stringSelection, null)
    }
}

