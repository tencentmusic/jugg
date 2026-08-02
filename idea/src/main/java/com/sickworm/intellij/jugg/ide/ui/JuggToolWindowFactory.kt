package com.sickworm.intellij.jugg.ide.ui

import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory
import com.sickworm.intellij.jugg.ide.JuggControlPanelHost
import com.sickworm.intellij.jugg.loader.JuggInitializer

/** Creates the project-level Jugg control panel. */
class JuggToolWindowFactory : ToolWindowFactory, DumbAware {

    override fun shouldBeAvailable(project: Project): Boolean = hasRunnableJuggConfiguration(project)

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        if (toolWindow.contentManager.contents.any { it.component is JuggControlPanelHost }) return
        val host = JuggControlPanelHost()
        JuggInitializer.getManager(project)?.getJuggControlPanel("overview")?.let(host::setImpl)
        val content = ContentFactory.getInstance().createContent(host, "", false)
        toolWindow.contentManager.addContent(content)
    }
}
