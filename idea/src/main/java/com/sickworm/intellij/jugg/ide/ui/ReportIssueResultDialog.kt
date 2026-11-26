package com.sickworm.intellij.jugg.ide.ui

import com.intellij.ide.actions.RevealFileAction
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.util.ui.JBUI
import com.sickworm.intellij.jugg.diagnostics.IssueReportUploadResult
import java.awt.BorderLayout
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.io.File
import javax.swing.Action
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel

/**
 * Displays the issue report outcome without exposing the temporary bundle path.
 */
class ReportIssueResultDialog(
    private val uploadResult: IssueReportUploadResult?,
    private val serverUrl: String? = null,
    private val bundleFile: File? = null,
    private val onRetry: (() -> Unit)? = null,
) : DialogWrapper(true) {
    init {
        title = "Jugg issue report"
        setOKButtonText("Copy result and close")
        init()
    }

    override fun createActions(): Array<Action> {
        val actions = mutableListOf<Action>()
        if (bundleFile != null) {
            actions.add(object : DialogWrapperAction("Save to local") {
                override fun doAction(e: java.awt.event.ActionEvent?) {
                    RevealFileAction.openFile(bundleFile)
                }
            })
        }
        if (uploadResult?.isSuccess == false && onRetry != null) {
            actions.add(object : DialogWrapperAction("Retry upload") {
                override fun doAction(e: java.awt.event.ActionEvent?) {
                    close(CANCEL_EXIT_CODE)
                    onRetry.invoke()
                }
            })
        }
        actions.add(okAction)
        return actions.toTypedArray()
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
        Toolkit.getDefaultToolkit().systemClipboard.setContents(
            StringSelection(reportIssueClipboardText(uploadResult, serverUrl)),
            null,
        )
        super.doOKAction()
    }
}

internal fun reportIssueClipboardText(uploadResult: IssueReportUploadResult?, serverUrl: String?): String {
    val result = uploadResult?.reportId ?: uploadResult?.errorMessage ?: "Diagnostics bundle saved locally"
    return "Jugg report: $result" + (serverUrl?.let { "\nServer Url: $it" } ?: "")
}
