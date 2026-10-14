package com.sickworm.intellij.jugg.ide

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager
import com.sickworm.intellij.jugg.loader.JuggInitializer
import java.awt.BorderLayout
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.SwingConstants

import javax.swing.SwingUtilities

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
        const val TOOL_WINDOW_ID = "Jugg Running Panel"

        fun open(project: Project, page: String = "overview") {
            val toolWindow = try {
                ToolWindowManager.getInstance(project).getToolWindow(TOOL_WINDOW_ID)
            } catch (e: Throwable) {
                null
            } ?: return
            toolWindow.setAvailable(true)
            refresh(project, page)
            toolWindow.activate(Runnable { refresh(project, page) })
        }

        fun clear(project: Project) {
            val toolWindow = try {
                ToolWindowManager.getInstance(project).getToolWindow(TOOL_WINDOW_ID)
            } catch (e: Throwable) {
                null
            } ?: return
            toolWindow.contentManager.contents
                .asSequence()
                .map { it.component }
                .filterIsInstance<JuggControlPanelHost>()
                .forEach(JuggControlPanelHost::clearImpl)
        }

        /**
         * Refreshes the control panel host with the active JuggManager implementation if available.
         */
        fun refresh(project: Project, page: String = "overview") {
            val action = Runnable {
                if (project.isDisposed) return@Runnable
                val toolWindow = try {
                    ToolWindowManager.getInstance(project).getToolWindow(TOOL_WINDOW_ID)
                } catch (e: Throwable) {
                    null
                } ?: return@Runnable
                val host = toolWindow.contentManager.contents
                    .asSequence()
                    .map { it.component }
                    .filterIsInstance<JuggControlPanelHost>()
                    .firstOrNull() ?: return@Runnable
                JuggInitializer.getManager(project)?.getJuggControlPanel(page)?.let(host::setImpl)
            }
            if (SwingUtilities.isEventDispatchThread()) {
                action.run()
            } else {
                SwingUtilities.invokeLater(action)
            }
        }
    }
}
