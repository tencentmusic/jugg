package com.sickworm.intellij.jugg.ide.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.JBColor
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import com.sickworm.intellij.jugg.ide.controlpanel.JuggEvent
import com.sickworm.intellij.jugg.project.ChangedFile
import java.awt.BorderLayout
import java.awt.GridLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.ListSelectionModel
import javax.swing.SwingUtilities
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

/** Manages an editable selection without mutating deploy state until confirmation. */
class FileChangeManagerDialog(
    private val fileProject: Project,
    private val controller: JuggControlPanelController,
) : DialogWrapper(fileProject) {
    private var candidates = controller.fileChangeCandidates()
    private var modifiedTimes = modificationTimes()
    private val selectedPaths = controller.selectedFilePaths().toMutableSet()
    private val editedPaths = mutableSetOf<String>()
    private val search = JBTextField()
    private val listModel = DefaultListModel<JuggEvent.ChangedFileSnapshot>()
    private val files = JBList(listModel)
    private val status = JBLabel()
    private val refreshButton = JButton("Refresh")
    private val content = JPanel(BorderLayout(JBUI.scale(8), JBUI.scale(8)))

    init {
        title = "File Change Manager"
        isModal = false
        setOKButtonText("Confirm")
        content.preferredSize = JBUI.size(600, 420)
        search.emptyText.text = "Search files or modules"
        content.add(JPanel(BorderLayout(JBUI.scale(8), JBUI.scale(8))).apply {
            add(JPanel(GridLayout(0, 1, 0, JBUI.scale(4))).apply {
                add(JBLabel("Select the files to compile in this run."))
                add(JBLabel("Warning: This may lead to incorrect compilation results. Proceed with caution.").apply {
                    foreground = JBColor(0xA67C00, 0xE5C07B)
                })
            }, BorderLayout.NORTH)
            add(search, BorderLayout.CENTER)
            add(refreshButton, BorderLayout.EAST)
        }, BorderLayout.NORTH)
        content.add(JBScrollPane(files), BorderLayout.CENTER)
        content.add(status, BorderLayout.SOUTH)
        configureList()
        search.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = filterFiles()
            override fun removeUpdate(e: DocumentEvent) = filterFiles()
            override fun changedUpdate(e: DocumentEvent) = filterFiles()
        })
        refreshButton.addActionListener { refreshCandidates() }
        init()
        filterFiles()
    }

    override fun createCenterPanel(): JComponent = content

    override fun getPreferredFocusedComponent(): JComponent = search

    override fun doOKAction() {
        if (controller.applyFileSelection(candidates, selectedPaths)) {
            super.doOKAction()
        } else {
            setErrorText("Wait for the current Jugg task to finish before confirming.")
        }
    }

    private fun configureList() {
        val renderer = changedFileRenderer()
        val checkbox = JBCheckBox()
        val row = JPanel(BorderLayout()).apply {
            add(checkbox, BorderLayout.WEST)
            add(renderer, BorderLayout.CENTER)
        }
        files.selectionMode = ListSelectionModel.SINGLE_SELECTION
        files.cellRenderer = javax.swing.ListCellRenderer { list, value, index, selected, focus ->
            renderer.getListCellRendererComponent(list, value, index, selected, focus)
            renderer.append("   ${modifiedTimes[value.absolutePath].orEmpty()}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
            checkbox.isSelected = value.absolutePath in selectedPaths
            val background = if (selected) list.selectionBackground else list.background
            checkbox.background = background
            row.background = background
            row.toolTipText = value.absolutePath
            row
        }
        files.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (!SwingUtilities.isLeftMouseButton(e)) return
                val index = files.locationToIndex(e.point)
                if (index < 0) return
                val cell = files.getCellBounds(index, index)
                if (!cell.contains(e.point)) return
                if (e.x < cell.x + checkbox.preferredSize.width) {
                    if (e.clickCount == 1) toggle(index)
                } else if (e.clickCount == 2) {
                    openFile(listModel.getElementAt(index))
                }
            }
        })
    }

    private fun openFile(file: JuggEvent.ChangedFileSnapshot) {
        val virtualFile = LocalFileSystem.getInstance().findFileByPath(file.absolutePath) ?: return
        OpenFileDescriptor(fileProject, virtualFile).navigate(true)
    }

    private fun modificationTimes(): Map<String, String> {
        val now = System.currentTimeMillis()
        return candidates.associate {
            val minutes = (now - it.file.lastModified()).coerceAtLeast(0) / 60_000
            it.file.absolutePath to if (minutes < 60) "$minutes min" else "${minutes / 60}h"
        }
    }

    private fun toggle(index: Int) {
        if (index < 0) return
        val path = listModel.getElementAt(index).absolutePath
        if (!selectedPaths.remove(path)) selectedPaths.add(path)
        editedPaths.add(path)
        files.repaint()
    }

    private fun filterFiles() {
        val query = search.text.trim()
        listModel.clear()
        candidates.map { it.snapshot() }.filter {
            "${it.moduleName} / ${it.path}".contains(query, ignoreCase = true)
        }.forEach { listModel.addElement(it) }
        files.emptyText.text = "No matching files"
    }

    private fun refreshCandidates() {
        refreshButton.isEnabled = false
        setOKActionEnabled(false)
        status.text = "Refreshing..."
        controller.refreshFileChanges { success ->
            if (isDisposed) return@refreshFileChanges
            candidates = controller.fileChangeCandidates()
            modifiedTimes = modificationTimes()
            val currentSelection = controller.selectedFilePaths()
            candidates.map { it.file.absolutePath }.filter { it !in editedPaths }.forEach {
                if (it in currentSelection) selectedPaths.add(it) else selectedPaths.remove(it)
            }
            filterFiles()
            refreshButton.isEnabled = true
            setOKActionEnabled(true)
            status.text = if (success) " " else "Refresh unavailable; existing files are still available."
        }
    }

    private fun ChangedFile.snapshot() = JuggEvent.ChangedFileSnapshot(
        category = JuggEvent.ChangedFileCategory.OTHER,
        path = file.name,
        absolutePath = file.absolutePath,
        moduleName = module.gradleModuleName ?: module.name,
    )
}
