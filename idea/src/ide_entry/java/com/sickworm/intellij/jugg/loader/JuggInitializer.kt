package com.sickworm.intellij.jugg.loader

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.testFramework.closeProjectAsync
import com.intellij.testFramework.openProjectAsync
import com.sickworm.intellij.jugg.ide.SyncEvent
import com.sickworm.intellij.jugg.ide.IJuggManagerCaller
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.jetbrains.kotlin.idea.core.util.toVirtualFile
import java.io.File

object JuggInitializer {

    private val instanceSet = mutableMapOf<String, JuggLoader>()

    private val logger = Logger.getInstance("JuggInitializer")


    @Synchronized
    fun onSyncEvent(project: Project, syncEvent: SyncEvent) {
        val instance = instanceSet[project.basePath]
        instance?.juggManager?.onSyncEvent(syncEvent)
    }

    @Synchronized
    fun init(project: Project) {
        val projectDir: String? = project.basePath
        if (projectDir == null || !File(projectDir).exists()) {
            logger.error("Can not get Jugg project dir, exit")
            return
        }

        if (instanceSet.containsKey(projectDir)) {
            logger.debug("Jugg already init on ${projectDir}, exit init")
            return
        }

        val instance = JuggLoader(project, File(projectDir))
        instanceSet[projectDir] = instance
        instance.init()
    }

    @Synchronized
    fun release(project: Project) {
        val instance = instanceSet.remove(project.bashPathOrDefault)
        instance?.release()
    }

    @Synchronized
    fun reopenAllProjectsAsync() {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val projects = instanceSet.values.map { it.project }
                val projectDirs = instanceSet.values.map { it.projectDir }
                projects.forEach {
                    it.closeProjectAsync()
                }
                projectDirs.forEach {
                    val virtualFile = it.toVirtualFile()
                    if (virtualFile != null) {
                        @Suppress("UnstableApiUsage")
                        openProjectAsync(virtualFile)
                    }
                }
            } catch (e: Exception) {
                logger.error("Failed to release Jugg project dirs: ", e)
            }
        }
    }

    fun getManager(project: Project?): IJuggManagerCaller? {
        if (project == null) {
            return null
        }
        return instanceSet[project.bashPathOrDefault]?.juggManager
    }

    private val Project.bashPathOrDefault get() = basePath ?: "null"
}
