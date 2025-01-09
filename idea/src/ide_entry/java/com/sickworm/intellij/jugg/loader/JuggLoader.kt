package com.sickworm.intellij.jugg.loader

import com.intellij.openapi.project.Project
import com.sickworm.intellij.jugg.ide.*
import java.io.File
import java.lang.reflect.Proxy

/**
 * Init JuggManager by loading Jugg classes from JAR files.
 * Supports hot update.
 */
class JuggLoader(private val project: Project, private val projectDir: File) {

    var juggManager: IJuggManagerCaller? = null
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
            juggManagerCreator.printCreateError(e)
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
            juggCreatorObj::class.java.getMethod(method.name, *method.parameterTypes)
                .invoke(juggCreatorObj, *(args ?: emptyArray()))
        } as IJuggManagerCreator

        juggManager = Proxy.newProxyInstance(
            IJuggManagerCaller::class.java.classLoader,
            arrayOf<Class<*>>(IJuggManagerCaller::class.java)
        ) { _, method, args ->
            juggManagerObj::class.java.getMethod(method.name, *method.parameterTypes)
                .invoke(juggManagerObj, *(args ?: emptyArray()))
        } as IJuggManagerCaller
    }

    private fun getHotUpdateClassLoader(): ClassLoader {
        if (cacheHotUpdateClassLoader != null) {
            if (cacheKey == getCacheKey()) {
                return cacheHotUpdateClassLoader!!
            }
        }

        cacheHotUpdateClassLoader = createHotUpdateClassLoader()
        cacheKey = getCacheKey()
        return cacheHotUpdateClassLoader!!
    }

    private fun createHotUpdateClassLoader(): ClassLoader {
        val jarFileNames = JuggHotUpdateManager.loadListFile.readLines().filter { it.isNotEmpty() }.toSet()
        clearOutdatedJarBeforeFirstTimeLoad(jarFileNames)
        val jarFiles = jarFileNames.map { jarFileName ->
            val jarFile = JuggHotUpdateManager.storageDir.resolve(jarFileName)
            if (!jarFile.exists()) {
                throw IllegalStateException("Jugg hot update jar file not found: $jarFile")
            }
            return@map jarFile
        }

        return JuggPriorityURLClassLoader(
            jarFiles.map { it.toURI().toURL() }.toTypedArray(),
            getOriginClassLoader(),
        ) isUseOriginClassLoader@{ className ->
            // using origin class loader to load classes in canNotHotUpdatePackage except JuggManagerCreator
            // package in loader and ide is unable to hot update because these classes will initialized by Idea.
            if (className == JuggManagerCreator::class.java.name) {
                return@isUseOriginClassLoader false
            }
            if (canNotHotUpdateClass.contains(className)) {
                return@isUseOriginClassLoader true
            }
            val packageName = className.substring(0, className.lastIndexOf('.'))
            return@isUseOriginClassLoader canNotHotUpdatePackage.contains(packageName)
        }
    }

    private fun getOriginClassLoader(): ClassLoader {
        return JuggLoader::class.java.classLoader
    }

    fun release() {
        juggManagerCreator?.release()
    }

    companion object {
        val canNotHotUpdatePackage = setOf(
            "com.sickworm.intellij.jugg.loader",
            "com.sickworm.intellij.jugg.ide",
            "com.intellij",
            )
        val canNotHotUpdateClass = setOf(
            // com.intellij.ui.components.DropDownLink.<init>(java.lang.Object, java.util.List, java.util.function.Consumer, int, kotlin.jvm.internal.DefaultConstructorMarker)
            // com.intellij.ui.components.DropDownLink.<init>(java.lang.Object, kotlin.jvm.functions.Function1)
            "kotlin.jvm.internal.DefaultConstructorMarker",
            "kotlin.jvm.functions.Function1",
        )

        private var isFirstTimeLoad = true

        @Synchronized
        private fun clearOutdatedJarBeforeFirstTimeLoad(jarFileNames: Set<String>) {
            if (!isFirstTimeLoad) {
                return
            }
            isFirstTimeLoad = false
            JuggHotUpdateManager.storageDir.listFiles()?.forEach {
                if (!jarFileNames.contains(it.name)) {
                    it.delete()
                }
            }
        }

        private var cacheKey: String? = null
        private var cacheHotUpdateClassLoader: ClassLoader? = null

        private fun getCacheKey(): String {
            return JuggHotUpdateManager.loadListFile.lastModified().toString()
        }
    }
}