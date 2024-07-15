package com.sickworm.intellij.jugg.ide

import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManagerListener

class JuggProjectManagerListener : ProjectManagerListener {

    override fun projectOpened(project: Project) {
        JuggInitializer.init(project)
    }

    override fun projectClosed(project: Project) {
        // no callback in runIde
    }

    override fun projectClosing(project: Project) {
        JuggInitializer.release(project)
    }
}