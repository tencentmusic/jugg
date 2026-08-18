package com.sickworm.intellij.jugg.ide.ui

import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import java.awt.Dimension
import java.awt.Font
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import javax.swing.JComponent
import javax.swing.JComboBox
import javax.swing.JPanel

/** Collects one non-interactive command for a clearly displayed remote target. */
class RemoteCommandDialog(
    project: Project,
    private val configurationName: String,
    private val target: String,
    private val workingDirectory: String,
    recentCommands: List<String>,
) : DialogWrapper(project, true) {

    private val commandTextArea = JBTextArea(8, 72).apply {
        font = Font(Font.MONOSPACED, Font.PLAIN, font.size)
        lineWrap = false
    }
    private val recentCommandComboBox = JComboBox(recentCommands.map(::RecentCommand).toTypedArray()).apply {
        selectedIndex = -1
        isEnabled = recentCommands.isNotEmpty()
        addActionListener {
            (selectedItem as? RecentCommand)?.let { commandTextArea.text = it.command }
        }
    }

    init {
        title = "Run Remote Command"
        setOKButtonText("Run")
        init()
        initValidation()
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(GridBagLayout())
        addRow(panel, 0, "Configuration:", readOnlyField(configurationName))
        addRow(panel, 1, "Target:", readOnlyField(target))
        addRow(panel, 2, "Working directory:", readOnlyField(workingDirectory))
        var commandRow = 3
        if (recentCommandComboBox.itemCount > 0) {
            addRow(panel, commandRow++, "Recent commands:", recentCommandComboBox)
        }
        addRow(panel, commandRow, "Command:", JBScrollPane(commandTextArea).apply {
            preferredSize = Dimension(JBUI.scale(640), JBUI.scale(180))
        }, weightY = 1.0, labelTarget = commandTextArea)
        return panel
    }

    override fun getPreferredFocusedComponent(): JComponent = commandTextArea

    override fun doValidate(): ValidationInfo? {
        return if (commandTextArea.text.isBlank()) {
            ValidationInfo("Command must not be empty.", commandTextArea)
        } else {
            null
        }
    }

    fun command(): String = commandTextArea.text.trim()

    private fun readOnlyField(value: String): JBTextField {
        return JBTextField(value).apply { isEditable = false }
    }

    private fun addRow(
        panel: JPanel,
        row: Int,
        label: String,
        component: JComponent,
        weightY: Double = 0.0,
        labelTarget: JComponent = component,
    ) {
        panel.add(JBLabel(label).apply { labelFor = labelTarget }, GridBagConstraints().apply {
            gridx = 0
            gridy = row
            anchor = GridBagConstraints.NORTHWEST
            insets = JBUI.insets(4, 0, 4, 12)
        })
        panel.add(component, GridBagConstraints().apply {
            gridx = 1
            gridy = row
            weightx = 1.0
            this.weighty = weightY
            fill = if (weightY > 0) GridBagConstraints.BOTH else GridBagConstraints.HORIZONTAL
            insets = JBUI.insets(4, 0)
        })
    }

    private class RecentCommand(val command: String) {
        override fun toString(): String = command.replace(Regex("\\s+"), " ").take(120)
    }
}
