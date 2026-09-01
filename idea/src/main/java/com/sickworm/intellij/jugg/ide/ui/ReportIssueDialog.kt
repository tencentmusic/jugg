package com.sickworm.intellij.jugg.ide.ui

import com.intellij.openapi.ui.DialogWrapper
import com.intellij.util.ui.JBUI
import com.sickworm.intellij.jugg.diagnostics.IssueReportCandidate
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.BoxLayout
import javax.swing.JCheckBox
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JScrollPane
import java.util.Locale

/**
 * Confirms the whitelist entries and upload destination before creating a diagnostics bundle.
 */
class ReportIssueDialog(
    candidates: List<IssueReportCandidate>,
    private val uploadUrl: String,
) : DialogWrapper(true) {
    private val saveLocallyCheckBox = JCheckBox("Save locally without uploading")
    private val candidateCheckBoxes = candidates.sortedByDescending { it.isJuggLog() }.associateWith { candidate ->
        JCheckBox(
            "${candidate.path}  (${formatFileSize(candidate.entry.size)})",
            candidate.isJuggLog() || candidate.isSelectedByDefault,
        ).apply { isEnabled = !candidate.isJuggLog() }
    }

    val isSaveLocally: Boolean get() = saveLocallyCheckBox.isSelected
    val selectedPaths: Set<String>
        get() = candidateCheckBoxes.filterValues { it.isSelected }.keys.map { it.path }.toSet()

    init {
        title = "Report Jugg Issue"
        saveLocallyCheckBox.addActionListener { updateOkButtonText() }
        updateOkButtonText()
        init()
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(BorderLayout(0, 12)).apply {
            border = JBUI.Borders.empty(12)
            preferredSize = Dimension(760, 500)
        }
        val entries = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            candidateCheckBoxes.values.forEach(::add)
        }
        panel.add(
            JLabel("<html>The following runtime logs have been redacted and will be used to analyze your issue." +
                    "<br>Upload destination: <b>$uploadUrl</b>" +
                    "<br>Selected diagnostics will be uploaded only to this address.</html>"),
            BorderLayout.NORTH,
        )
        panel.add(JScrollPane(entries), BorderLayout.CENTER)
        panel.add(saveLocallyCheckBox, BorderLayout.SOUTH)
        return panel
    }

    private fun IssueReportCandidate.isJuggLog(): Boolean = path.startsWith("diagnostics/logs/")

    private fun formatFileSize(bytes: Long): String {
        val megabyte = 1024L * 1024L
        if (bytes < megabyte) {
            return "${maxOf(1L, (bytes + 1023L) / 1024L)} KB"
        }
        val value = bytes.toDouble() / megabyte
        val formatted = if (bytes % megabyte == 0L) {
            (bytes / megabyte).toString()
        } else {
            String.format(Locale.US, "%.1f", value)
        }
        return "$formatted MB"
    }

    private fun updateOkButtonText() {
        setOKButtonText(if (saveLocallyCheckBox.isSelected) "Create Diagnostics Bundle" else "Upload logs")
    }
}
