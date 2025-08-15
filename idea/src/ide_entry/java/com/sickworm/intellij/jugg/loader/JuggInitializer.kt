package com.sickworm.intellij.jugg.loader

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.sickworm.intellij.jugg.ide.SyncEvent
import com.sickworm.intellij.jugg.ide.IJuggManagerCaller
import com.sickworm.intellij.jugg.rpc.RpcLocalServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
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

        RpcLocalServer.start()
    }

    @Synchronized
    fun release(project: Project) {
        val instance = instanceSet.remove(project.bashPathOrDefault)
        instance?.release()

        if (instanceSet.isEmpty()) {
            RpcLocalServer.stop()
        }
    }

    @Synchronized
    fun reopenAllProjectsAsync() {
        CoroutineScope(Dispatchers.IO).launch {
            ApplicationManager.getApplication().invokeAndWait {
                try {
                    val projects = instanceSet.values.map { it.project }
                    projects.forEach {
                        ProjectManager.getInstance().reloadProject(it)
                    }
                } catch (e: Exception) {
                    logger.error("Failed to release Jugg project dirs: ", e)
                }
            }
        }
    }

    fun getManager(project: Project?): IJuggManagerCaller? {
        if (project == null) {
            return null
        }
        return instanceSet[project.bashPathOrDefault]?.juggManager
    }

    fun getManager(projectDir: String): IJuggManagerCaller? {
        return instanceSet[projectDir]?.juggManager
    }

    private val Project.bashPathOrDefault get() = basePath ?: "null"
}
