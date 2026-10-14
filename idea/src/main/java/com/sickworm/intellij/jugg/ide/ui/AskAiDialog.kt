package com.sickworm.intellij.jugg.ide.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import com.sickworm.intellij.jugg.diagnostics.InvestigationSnapshotBuilder
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.Font
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.ListSelectionModel
import javax.swing.event.DocumentEvent

/** Collects an investigation request or previews its preserved evidence and copyable Agent prompt. */
class AskAiDialog(
    project: Project,
    devices: List<Pair<String, String>>,
    private val snapshot: InvestigationSnapshotBuilder.Snapshot? = null,
) : DialogWrapper(project, true) {
    private val problemTypes = JComboBox(arrayOf("Compilation failed", "Runtime crash", "Changes not applied", "Other"))
    private val description = JBTextArea(7, 68).apply {
        lineWrap = true
        wrapStyleWord = true
        border = JBUI.Borders.empty(8)
    }
    private val deviceList = JBList(devices.map { DeviceChoice(it.first, it.second) }).apply {
        selectionMode = ListSelectionModel.MULTIPLE_INTERVAL_SELECTION
        if (devices.isNotEmpty()) setSelectionInterval(0, devices.lastIndex)
        visibleRowCount = 3
    }
    private val copyStatus = JBLabel()

    init {
        title = "ASK AI"
        setOKButtonText(if (snapshot == null) "Save Evidence and Generate Prompt" else "Copy prompt")
        setCancelButtonText("Close")
        isOKActionEnabled = snapshot != null
        description.document.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) {
                isOKActionEnabled = description.text.isNotBlank()
            }
        })
        init()
    }

    val problemType: String get() = problemTypes.selectedItem as String
    val problemDescription: String get() = description.text.trim()
    val deviceSerials: Set<String> get() = deviceList.selectedValuesList.map { it.serial }.toSet()

    override fun createCenterPanel(): JComponent {
        return snapshot?.let(::previewPanel) ?: requestPanel()
    }

    private fun requestPanel(): JComponent = JPanel(BorderLayout(JBUI.scale(8), JBUI.scale(8))).apply {
        add(JPanel(BorderLayout(8, 8)).apply {
            add(JBLabel("Problem type:"), BorderLayout.WEST)
            add(problemTypes, BorderLayout.CENTER)
        }, BorderLayout.NORTH)
        add(JPanel(BorderLayout(8, 8)).apply {
            add(JBLabel("Describe the problem, expected result, actual result, and changed files:"), BorderLayout.NORTH)
            add(JBScrollPane(description), BorderLayout.CENTER)
        }, BorderLayout.CENTER)
        add(JPanel(BorderLayout(8, 8)).apply {
            add(JBLabel("Devices to collect logs from (optional):"), BorderLayout.NORTH)
            add(JBScrollPane(deviceList), BorderLayout.CENTER)
            add(JBLabel("Evidence will be saved locally before you copy the prompt to your Agent."), BorderLayout.SOUTH)
        }, BorderLayout.SOUTH)
    }

    private fun previewPanel(saved: InvestigationSnapshotBuilder.Snapshot): JComponent {
        val prompt = JBTextArea(saved.prompt).apply {
            isEditable = false
            lineWrap = true
            wrapStyleWord = true
            font = Font(Font.MONOSPACED, Font.PLAIN, font.size)
            border = JBUI.Borders.empty(8)
            caretPosition = 0
        }
        return JPanel(BorderLayout(JBUI.scale(8), JBUI.scale(8))).apply {
            add(JPanel(BorderLayout(8, 8)).apply {
                add(JBLabel("Context captured. Directory (retained for 30 days):"), BorderLayout.NORTH)
                add(JBTextField(saved.directory.absolutePath).apply { isEditable = false }, BorderLayout.CENTER)
            }, BorderLayout.NORTH)
            add(JPanel(BorderLayout(JBUI.scale(8), JBUI.scale(8))).apply {
                add(JBLabel("Copy the following prompt and paste it into your AI agent for analysis:"), BorderLayout.NORTH)
                add(JBScrollPane(prompt).apply {
                    preferredSize = Dimension(JBUI.scale(720), JBUI.scale(420))
                }, BorderLayout.CENTER)
            }, BorderLayout.CENTER)
            add(copyStatus, BorderLayout.SOUTH)
        }
    }

    override fun doOKAction() {
        val saved = snapshot
        if (saved == null) {
            super.doOKAction()
            return
        }
        try {
            Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(saved.prompt), null)
            copyStatus.text = "Copied. Paste the prompt into your Agent in this Android project."
        } catch (e: IllegalStateException) {
            Messages.showWarningDialog("Cannot copy the prompt: ${e.message}", "ASK AI")
        }
    }

    override fun getPreferredFocusedComponent(): JComponent? = if (snapshot == null) description else null

    private class DeviceChoice(val serial: String, private val label: String) {
        override fun toString(): String = "$label ($serial)"
    }
}
