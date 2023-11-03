package com.sickworm.intellij.jugg.project

import com.android.tools.idea.run.ApkInfo
import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.jugg.compiler.*
import com.sickworm.intellij.jugg.deploy.DeployFileManager
import java.io.File

data class BaseCompileContext(
    override val logger: Logger,
    override var tempCompileDir: File,
    override var tempModuleDir: File,
    override val androidHome: File,
    override var modules: Map<String, ModuleInfo> = emptyMap(),
    override var apkInfos: List<ApkInfo> = emptyList(),
    override val minApi: Int,
    override val projectDir: File,
    private val deployFileManager: DeployFileManager,
): ICompileContext {

    private val androidJarApi: String = getSuggestedPlatformApi(modules)
    override val androidJar: File = File(androidHome, "platforms/android-$androidJarApi/android.jar")
    override val androidBuildTools: File = getSuggestedBuildTools(modules, androidJarApi)

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

    init {
        tempModule.buildPathInfo.moduleRootDir.clearDir()
    }

    override fun getModuleDependencies(moduleInfo: ModuleInfo, task: CompileTask): List<String> {
        val androidJar = androidJar.path

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
        val libraryDependency = moduleInfo.libraryDependencies.map {
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
        val versionsInGradle = modules.values.map { it.compileVersion }
        var version = getLatestVersion(versionsInGradle)
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
            logger.debug("getSuggestedPlatformApi version: $version, versions in gradle: $versionsInSdk")

            if (version == null) {
                throw JuggException.androidJarNotFound("")
            }
        }

        return version
    }

    private fun getSuggestedBuildTools(modules: Map<String, ModuleInfo>, androidJarApi: String): File {
        val versionsInGradle = modules.values.map { it.buildToolsVersion }
        var version = getLatestVersion(versionsInGradle)
        logger.debug("getSuggestedBuildTools version: $version, androidJarApi: $androidJarApi, versions in gradle: $versionsInGradle")

        if (version != null) {
            val targetDir = File(androidHome, "build-tools/$version")
            if (!targetDir.exists()) {
                logger.warn(
                    "Can't not read build-tools version from gradle, path($targetDir) not exist" +
                            "Try to find in android home."
                )
                version = null
            }
        }

        if (version == null) {
            val rootDir = File(androidHome, "build-tools")
            var versionsInSdk = rootDir.listFiles()?.mapNotNull {
                it.name
            }
            val versionsInSdkMatchesApi = versionsInSdk?.filter {
                it.startsWith(androidJarApi)
            }
            if (!versionsInSdkMatchesApi.isNullOrEmpty()) {
                versionsInSdk = versionsInSdkMatchesApi
            }
            version = getLatestVersion(versionsInSdk)
            logger.debug("getSuggestedBuildTools version: $version, androidJarApi: $androidJarApi, " +
                        "versions in gradle: $versionsInGradle, " +
                        "versions in sdk: $versionsInSdk"
            )

            if (version == null) {
                throw JuggException.buildToolsNotFound("")
            }
        }

        return File(androidHome, "build-tools/$version")
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