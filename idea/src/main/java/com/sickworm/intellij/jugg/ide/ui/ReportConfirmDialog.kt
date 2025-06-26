package com.sickworm.intellij.jugg.ide.ui

import com.intellij.openapi.ui.DialogWrapper
import com.intellij.util.ui.JBUI
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel

class ReportConfirmDialog : DialogWrapper(true) {

    private val mainPanel: JPanel = JPanel(GridBagLayout())
    private val contentLabel: JLabel =
        JLabel("Report will upload your logs(build/jugg/log) and project infos(build/jugg/database/project_infos.db) in this project, confirm report?")

    init {
        title = "Confirm Report"

        val constraints = GridBagConstraints()
        constraints.gridx = 0
        constraints.gridy = 0
        constraints.fill = GridBagConstraints.HORIZONTAL
        constraints.insets = JBUI.insets(12)
        mainPanel.add(contentLabel, constraints)

        init()
    }

    override fun createCenterPanel(): JComponent {
        return mainPanel
    }
}

