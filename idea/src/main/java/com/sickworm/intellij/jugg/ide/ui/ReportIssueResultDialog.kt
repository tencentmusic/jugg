package com.sickworm.intellij.jugg.ide.ui

import com.intellij.openapi.ui.DialogWrapper
import com.intellij.util.ui.JBUI
import com.sickworm.intellij.jugg.diagnostics.IssueReportUploadResult
import java.awt.BorderLayout
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import javax.swing.Action
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel

/**
 * Displays the issue report outcome without exposing the temporary bundle path.
 */
class ReportIssueResultDialog(
    private val uploadResult: IssueReportUploadResult?,
    private val onRetry: (() -> Unit)? = null,
) : DialogWrapper(true) {
    init {
        title = "Jugg Issue Report"
        setOKButtonText("Copy Result and Close")
        init()
    }

    override fun createActions(): Array<Action> {
        if (uploadResult?.isSuccess != false || onRetry == null) {
            return arrayOf(okAction)
        }
        val retryAction = object : DialogWrapperAction("Retry Upload") {
            override fun doAction(e: java.awt.event.ActionEvent?) {
                close(CANCEL_EXIT_CODE)
                onRetry.invoke()
            }
        }
        return arrayOf(retryAction, okAction)
    }

    override fun createCenterPanel(): JComponent {
        val message = when {
            uploadResult == null -> "Diagnostics bundle saved locally."
            uploadResult.isSuccess -> "Report uploaded. Jugg Report ID: ${uploadResult.reportId}"
            else -> "Upload failed: ${uploadResult.errorMessage}"
        }
        return JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(12)
            add(JLabel("<html>$message</html>"), BorderLayout.CENTER)
        }
    }

    override fun doOKAction() {
        val result = uploadResult?.reportId ?: uploadResult?.errorMessage ?: "Diagnostics bundle saved locally"
        Toolkit.getDefaultToolkit().systemClipboard.setContents(
            StringSelection("Jugg report: $result"),
            null,
        )
        super.doOKAction()
    }
}
