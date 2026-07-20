package com.sickworm.intellij.jugg.ide

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager
import com.sickworm.intellij.jugg.loader.JuggInitializer
import java.awt.BorderLayout
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.SwingConstants

/**
 * Stable control panel host that only retains a base JComponent from the active Jugg class loader.
 */
class JuggControlPanelHost : JPanel(BorderLayout()) {

    init {
        showInitializing()
    }

    fun setImpl(component: JComponent) {
        removeAll()
        add(component, BorderLayout.CENTER)
        revalidate()
        repaint()
    }

    fun clearImpl() {
        showInitializing()
    }

    private fun showInitializing() {
        removeAll()
        add(JLabel("Jugg is initializing", SwingConstants.CENTER), BorderLayout.CENTER)
        revalidate()
        repaint()
    }

    companion object {
        const val TOOL_WINDOW_ID = "Jugg Running Pannel"

        fun open(project: Project, page: String = "overview") {
            val toolWindow = ToolWindowManager.getInstance(project).getToolWindow(TOOL_WINDOW_ID) ?: return
            refresh(project, page)
            toolWindow.activate(Runnable { refresh(project, page) })
        }

        fun clear(project: Project) {
            val toolWindow = ToolWindowManager.getInstance(project).getToolWindow(TOOL_WINDOW_ID) ?: return
            toolWindow.contentManager.contents
                .asSequence()
                .map { it.component }
                .filterIsInstance<JuggControlPanelHost>()
                .forEach(JuggControlPanelHost::clearImpl)
        }

        private fun refresh(project: Project, page: String) {
            val toolWindow = ToolWindowManager.getInstance(project).getToolWindow(TOOL_WINDOW_ID) ?: return
            val host = toolWindow.contentManager.contents
                .asSequence()
                .map { it.component }
                .filterIsInstance<JuggControlPanelHost>()
                .firstOrNull() ?: return
            JuggInitializer.getManager(project)?.getJuggControlPanel(page)?.let(host::setImpl)
        }
    }
}
