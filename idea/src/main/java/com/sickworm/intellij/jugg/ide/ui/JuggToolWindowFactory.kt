package com.sickworm.intellij.jugg.ide.ui

import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory

/** Creates the project-level Jugg control panel. */
class JuggToolWindowFactory : ToolWindowFactory, DumbAware {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        if (toolWindow.contentManager.contents.any { it.component is JuggControlPanel }) return
        val content = ContentFactory.getInstance().createContent(JuggControlPanel(project), "", false)
        toolWindow.contentManager.addContent(content)
    }
}
