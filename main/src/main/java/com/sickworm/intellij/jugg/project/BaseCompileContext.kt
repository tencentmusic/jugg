package com.sickworm.intellij.jugg.project

import com.android.tools.idea.run.ApkInfo
import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.jugg.compiler.*
import com.sickworm.intellij.jugg.deploy.DeployFileManager
import com.sickworm.intellij.jugg.gradle.compile.isChild
import java.io.File

data class BaseCompileContext(
    override val logger: Logger,
    override var tempCompileDir: File,
    override var tempModuleDir: File,
    override val androidHome: File,
    override var modules: Map<String, ModuleInfo> = emptyMap(),
    override var apkInfos: List<ApkInfo> = emptyList(),
    override val projectDir: File,
    private val deployFileManager: DeployFileManager,
): ICompileContext {

    private val androidJarApi: String = getSuggestedPlatformApi(modules)
    override val androidJar: File = File(androidHome, "platforms/android-$androidJarApi/android.jar")

    override val deployedFiles: List<CompileOutput> get() = deployFileManager.getDeployedFiles()

    private val listeners = mutableListOf<OnContextUpdate>()

    // currently Jugg only keep the final R.jar which is in the application module, for better copying speed in remote compile mode
    private val finalRFiles: List<String> by lazy {
        return@lazy modules.mapNotNull { module ->
            val rFile = module.value.buildPathInfo.rFilePath
            if (rFile.exists()) {
                rFile.absolutePath
            } else {
                null
            }
        }.sortedBy {
            -File(it).length() // sort by file size, to let the biggest R.jar go first
        }
    }

    override fun getModuleDependencies(moduleInfo: ModuleInfo, task: CompileTask): List<String> {
        val androidJar = getAndroidJarPath(moduleInfo)

        val tempDependencies: List<String> = tempModule.buildPathInfo.allClassPath.filter {
            it.exists()
        }.map {
            it.absolutePath
        }

        val classpathDependencies = moduleInfo.buildPathInfo.allClassPath.filter { file ->
            file.exists()
        }.map { file ->
            file.absolutePath
        }

        val moduleDependencies: List<String> = moduleInfo.moduleDependencies.flatMap {
            val dependencyModuleInfo = modules[it.moduleName] ?: run {
                logger.warn("module ${it.moduleName} not found in ${moduleInfo.name}'s dependencies, maybe sync gradle again helps.")
                return@flatMap emptyList()
            }
            dependencyModuleInfo.buildPathInfo.allClassPath.filter { file ->
                file.exists()
            }.map { file ->
                file.absolutePath
            }
        }
        val libraryDependency = moduleInfo.libraryDependencies
            .filter {
                // filter unnecessary LibraryDependency for source file compilation
                val isInBuildDir = it.file.isChild(moduleInfo.buildPathInfo.buildDir)
                !isInBuildDir && it.isValid && !it.isAndroidManifest && !it.isRes
            }
            .map {
                it.file.absolutePath
            }

        if (finalRFiles.isEmpty()) {
            logger.warn("No R.jar found in project, compile may fail.")
        }

        val dependencies = mutableListOf(androidJar)
        dependencies.addAll(tempDependencies)
        dependencies.addAll(classpathDependencies)
        dependencies.addAll(moduleDependencies)
        dependencies.addAll(libraryDependency)
        dependencies.addAll(finalRFiles) // place to the last, to let R file compiled into classpathDependencies go first

        task.files.forEach {
            dependencies.addAll(it.dependencyPaths)
        }

        return dependencies
    }

    private fun getAndroidJarPath(moduleInfo: ModuleInfo): String {
        if (moduleInfo.compileVersion != null) {
            val androidJar = File(androidHome, "platforms/android-${moduleInfo.compileVersion}/android.jar")
            if (androidJar.exists()) {
                return androidJar.absolutePath
            }
        }

        logger.debug("android.jar not found in ${moduleInfo.name}, use ${androidJar.absolutePath} for fallback.")
        return androidJar.absolutePath
    }

    override fun getGeneratedSourcePaths(moduleInfo: ModuleInfo): List<File> {
        // e.g. ap_generated_sources, data_binding_base_class_source_out
        val dirs = mutableListOf<File>()
        moduleInfo.buildPathInfo.generatedSourcePath.listFiles()?.forEach {
            val baseDir = File(it, "${moduleInfo.buildVariant}/out")
            if (baseDir.exists()) {
                dirs.add(baseDir)
            }
        }

        // e.g. source/buildConfig source/kapt
        val sourceSubDir = File(moduleInfo.buildPathInfo.generatedSourcePath, "source")
        if (sourceSubDir.exists()) {
            sourceSubDir.listFiles()?.forEach {
                val baseDir = File(it, moduleInfo.buildVariant)
                if (baseDir.exists()) {
                    dirs.add(baseDir)
                }
            }
        }

        return dirs
    }

    override fun getAllDesugarClasspath(compileFiles: List<CompileFile>, moduleInfo: ModuleInfo, toDir: File) {
        // moduleInfo is used for searching classpath, but deployFileManager search globally for now
        deployFileManager.getAllDesugarClasspath(compileFiles, moduleInfo, toDir)
    }

    override fun listenUpdate(listener: OnContextUpdate) {
        synchronized(listeners) {
            if (!listeners.contains(listener)) {
                listeners.add(listener)
            }
        }
    }

    fun update(apkInfos: List<ApkInfo>? = null, modules: Map<String, ModuleInfo>? = null) {
        apkInfos?.let {
            this.apkInfos = it
        }
        modules?.let {
            this.modules = HashMap(it)
        }
        dispatch()
    }

    private fun dispatch() {
        synchronized(listeners) {
            listeners.forEach {
                it.invoke()
            }
        }
    }

    private fun getSuggestedPlatformApi(modules: Map<String, ModuleInfo>): String {
        val versionsInGradle = modules.values.map { it.name to it.compileVersion }
        var version = getLatestVersion(versionsInGradle.map { it.second} )
        val rootDir = File(androidHome, "platforms")
        logger.debug("getSuggestedPlatformApi version: $version, versions in gradle: $versionsInGradle")

        if (version != null) {
            val targetDir = File(rootDir, "android-$version")
            if (!targetDir.exists()) {
                logger.warn("Can't not read compile sdk version from gradle, path($targetDir) not exist. " +
                        "Try to find in android home")
                version = null
            }
        }

        if (version == null) {
            val versionsInSdk = rootDir.listFiles()?.mapNotNull {
                if (!it.name.startsWith("android-")) return@mapNotNull null
                it.name.substring("android-".length)
            }
            version = getLatestVersion(versionsInSdk)
            logger.debug("getSuggestedPlatformApi version: $version, versions in sdk: $versionsInSdk")

            if (version == null) {
                throw JuggException.androidJarNotFound("")
            }
        }

        return version
    }

    private fun getLatestVersion(versions: List<String?>?): String? {
        versions?: return null
        var latestVersion: String? = null
        versions.forEach {
            if (it == null) return@forEach
            if (!it.matches("[.0-9]+".toRegex())) return@forEach
            if (it.isLargerThan(latestVersion)) {
                latestVersion = it
            }
        }
        return latestVersion
    }

    private fun String.isLargerThan(version: String?): Boolean {
        if (version == null) return true
        val myVersions = this.split(".")
        val otherVersions = version.split(".")
        val size = kotlin.math.min(myVersions.size, otherVersions.size)
        for (i in 0 until size) {
            val compareResult = myVersions[i].compareTo(otherVersions[i])
            if (compareResult == 0) continue
            return compareResult > 0
        }
        return myVersions.size > otherVersions.size
    }
}