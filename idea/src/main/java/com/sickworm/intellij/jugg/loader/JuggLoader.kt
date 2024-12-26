package com.sickworm.intellij.jugg.loader

import com.intellij.openapi.application.PathManager
import com.intellij.openapi.project.Project
import com.sickworm.intellij.jugg.logger.JuggLogger
import java.io.File
import java.lang.reflect.Proxy

/**
 * Init JuggManager by loading Jugg classes from JAR files.
 * Supports hot update.
 */
object JuggLoader {

    fun loadManager(project: Project, projectDir: File): IJuggManager {

        try {
            val juggManager = getInstance(project, projectDir)
            juggManager.init()
            return juggManager
        } catch (e: Exception) {
            // oops, use embedded jars
            val juggManager = JuggManagerCreator(project, projectDir, "embedded").create()
            val logger = JuggLogger.getInstance(project, "JuggLoader")
            logger.warn("Jugg loading error", e)
            logger.warn("Jugg loading error, use embedded jars.")
            if (isTestEnv) {
                JuggLogger.unregister(project)
                throw e
            }
            return juggManager
        }
    }

    private fun getInstance(project: Project, projectDir: File): IJuggManager {
        val classLoader = getClassLoader()
        val juggCreatorObj = classLoader
            .loadClass(JuggManagerCreator::class.java.name)
            .getConstructor(Project::class.java, File::class.java, String::class.java)
            .newInstance(project, projectDir, "hot_update")
        val juggManagerObj = juggCreatorObj::class.java.getMethod("create").invoke(juggCreatorObj)

        // use delegate to implement IJuggManager to invoke cross classloader
        return Proxy.newProxyInstance(
            IJuggManager::class.java.classLoader,
            arrayOf<Class<*>>(IJuggManager::class.java)
        ) { _, method, args ->
            method.invoke(juggManagerObj, *(args ?: emptyArray()))
        } as IJuggManager
    }

    private fun getClassLoader(): ClassLoader {
        if (JuggHotUpdateManager.isHotUpdateAvailable) {
            return getHotUpdateClassLoader()
        }
        return getOriginClassLoader()
    }

    private fun getHotUpdateClassLoader(): ClassLoader {
        val jarFileNames = JuggHotUpdateManager.loadListFile.readLines()
        val jarFiles = jarFileNames.map { jarFileName ->
            val jarFile = JuggHotUpdateManager.storageDir.resolve(jarFileName)
            if (!jarFile.exists()) {
                throw IllegalStateException("Jugg hot update jar file not found: $jarFile")
            }
            return@map jarFile
        }

        return PriorityURLClassLoader(jarFiles.map { it.toURI().toURL() }.toTypedArray(), getOriginClassLoader())
    }

    private fun getOriginClassLoader(): ClassLoader {
        return JuggLoader::class.java.classLoader
    }

    private val isTestEnv: Boolean
        get() = PathManager.getSystemPath().replace("\\", "/").contains("idea/build/idea-sandbox/system")
}