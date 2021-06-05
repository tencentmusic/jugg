package com.sickworm.intellij.icap


import com.intellij.ide.ApplicationLoadListener
import com.intellij.openapi.application.Application
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.project.ProjectManagerListener

private val logger = Logger.getInstance("#CIAP-ApplicationLoadListener")

class ApplicationLoadListener: ApplicationLoadListener, ProjectManagerListener {

    override fun beforeApplicationLoaded(application: Application, configPath: String) {
        super.beforeApplicationLoaded(application, configPath)
        logger.info("beforeApplicationLoaded")
        // 注册 ProjectManagerListener
        application.messageBus.connect().subscribe(ProjectManager.TOPIC, this)
    }

    override fun projectOpened(project: Project) {
        val projectDir = project.basePath
        logger.info("projectOpened $project $projectDir")
        if (projectDir == null) return // Default Project 才会为空

        FileChangesManager(project, projectDir).listen()
    }

    override fun projectClosed(project: Project) {
        logger.info("projectClosed$project")
    }
}