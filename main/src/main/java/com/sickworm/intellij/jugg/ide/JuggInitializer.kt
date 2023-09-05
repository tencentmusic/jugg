package com.sickworm.intellij.jugg.ide

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.sickworm.intellij.jugg.JuggManager
import com.sickworm.intellij.jugg.logger.JuggLogger
import com.sickworm.intellij.jugg.project.JuggPathManager
import java.io.File

object JuggInitializer {

    private val instanceSet = mutableMapOf<String, JuggManager>()

    private val logger = Logger.getInstance("JuggInitializer")

    private fun tryGetProjectLogger(project: Project) = try {
        JuggLogger.getInstance(project, "JuggInitializer")
    } catch (e: Exception) {
        null
    }


    @Synchronized
    fun initOrRefresh(project: Project, isNeedReloadProjectInfo: Boolean = true) {
        val juggManager = instanceSet[project.basePath]
        if (juggManager != null) {
            juggManager.initProjectInfo(isNeedReloadProjectInfo)
            return
        }
        init(project)
    }

    @Synchronized
    fun init(project: Project) {
        val projectDir: String? = project.basePath
        if (projectDir == null || !File(projectDir).exists()) {
            logger.error("Can not get Jugg project dir, exit")
            return
        }

        val pathManager = JuggPathManager(project, File(projectDir))
        JuggLogger.register(project, pathManager.logDir)

        val juggManager = JuggManager(project, pathManager)
        tryGetProjectLogger(project)?.info("init $juggManager")
        juggManager.init()
        instanceSet[projectDir] = juggManager
    }

    @Synchronized
    fun release(project: Project) {
        val juggManager = instanceSet.remove(project.bashPathOrDefault)
        tryGetProjectLogger(project)?.info("release $juggManager")

        juggManager ?: return
        Disposer.dispose(juggManager)
        JuggLogger.unregister(project)
    }

    @Synchronized
    fun releaseAll() {
        instanceSet.values.forEach {
            release(it.project)
        }
    }

    fun getManager(project: Project): JuggManager? {
        return instanceSet[project.bashPathOrDefault]
    }
}

val Project.bashPathOrDefault get() = basePath ?: "null"