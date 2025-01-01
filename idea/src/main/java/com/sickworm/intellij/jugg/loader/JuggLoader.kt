package com.sickworm.intellij.jugg.loader

import com.intellij.openapi.application.PathManager
import com.intellij.openapi.project.Project
import com.sickworm.intellij.jugg.IJuggManager
import com.sickworm.intellij.jugg.logger.JuggLogger
import java.io.File
import java.lang.reflect.Proxy

/**
 * Init JuggManager by loading Jugg classes from JAR files.
 * Supports hot update.
 */
class JuggLoader(private val project: Project, private val projectDir: File) {

    var juggManager: IJuggManager? = null
        private set
    private var juggManagerCreator: IJuggManagerCreator? = null

    fun init() {
        loadManager(project, projectDir)
    }


    private fun loadManager(project: Project, projectDir: File) {
        try {
            createInstance(project, projectDir)
        } catch (e: Exception) {
            // oops, use embedded jars directly
            val juggManagerCreator = JuggManagerCreator(project, projectDir, "embedded_directly")
            this.juggManagerCreator = juggManagerCreator
            juggManager = juggManagerCreator.create()

            val logger = JuggLogger.getInstance(project, "JuggLoader")
            logger.warn("Jugg loading error", e)
            logger.warn("Jugg loading error, use embedded jars.")
            if (isTestEnv) {
                JuggLogger.unregister(project)
                throw e
            }
        }
    }

    private fun createInstance(project: Project, projectDir: File) {
        val classLoader: ClassLoader
        val creatorName: String
        if (JuggHotUpdateManager.isHotUpdateAvailable) {
            classLoader = getHotUpdateClassLoader()
            creatorName = "hot_update"
        } else {
            classLoader = getOriginClassLoader()
            creatorName = "embedded"
        }

        val juggCreatorObj = classLoader
            .loadClass(JuggManagerCreator::class.java.name)
            .getConstructor(Project::class.java, File::class.java, String::class.java)
            .newInstance(project, projectDir, creatorName)
        val juggManagerObj = juggCreatorObj::class.java.getMethod("create").invoke(juggCreatorObj)

        // use delegate invoke cross classloader
        juggManagerCreator = Proxy.newProxyInstance(
            IJuggManagerCreator::class.java.classLoader,
            arrayOf<Class<*>>(IJuggManagerCreator::class.java)
        ) { _, method, args ->
            method.invoke(juggCreatorObj, *(args ?: emptyArray()))
        } as IJuggManagerCreator

        juggManager = Proxy.newProxyInstance(
            IJuggManager::class.java.classLoader,
            arrayOf<Class<*>>(IJuggManager::class.java)
        ) { _, method, args ->
            method.invoke(juggManagerObj, *(args ?: emptyArray()))
        } as IJuggManager
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

    fun release() {
        juggManagerCreator?.release()
    }

    private val isTestEnv: Boolean
        get() = PathManager.getSystemPath().replace("\\", "/").contains("idea/build/idea-sandbox/system")
}