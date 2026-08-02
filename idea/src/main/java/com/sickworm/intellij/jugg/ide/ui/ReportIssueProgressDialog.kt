package com.sickworm.intellij.jugg.ide.ui

import com.intellij.openapi.ui.DialogWrapper
import com.intellij.util.ui.JBUI
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import javax.swing.Action
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JProgressBar

/**
 * Shows modal progress while issue diagnostics are prepared or uploaded.
 */
class ReportIssueProgressDialog(message: String) : DialogWrapper(true) {
    private val panel = JPanel(GridBagLayout()).apply {
        val labelConstraints = GridBagConstraints().apply {
            gridx = 0
            gridy = 0
            fill = GridBagConstraints.HORIZONTAL
            insets = JBUI.insets(12, 12, 12, 6)
        }
        add(JLabel(message), labelConstraints)

        val progressConstraints = GridBagConstraints().apply {
            gridx = 1
            gridy = 0
            fill = GridBagConstraints.HORIZONTAL
            insets = JBUI.insets(12, 6, 12, 12)
            weightx = 1.0
        }
        add(JProgressBar().apply { isIndeterminate = true }, progressConstraints)
    }

    init {
        title = "Report Jugg Issue"
        isResizable = false
        init()
    }

    override fun createActions(): Array<Action> = emptyArray()

    override fun createCenterPanel(): JComponent = panel
}
