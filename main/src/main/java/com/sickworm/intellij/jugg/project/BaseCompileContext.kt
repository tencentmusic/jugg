package com.sickworm.intellij.jugg.project

import com.android.tools.idea.run.ApkInfo
import com.google.gson.Gson
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.sickworm.intellij.jugg.compiler.*
import com.sickworm.intellij.jugg.compiler.manifest.XmlParser
import com.sickworm.intellij.jugg.compiler.manifest.get
import com.sickworm.intellij.jugg.project.data.ModuleInfo
import com.sickworm.intellij.jugg.deploy.DeployFileManager
import com.sickworm.intellij.jugg.deploy.IDeployHistoryManager
import com.sickworm.intellij.jugg.deploy.run.SigningConfig
import com.sickworm.intellij.jugg.gradle.compile.isChild
import com.sickworm.intellij.jugg.platform.PlatformApi
import com.sickworm.intellij.jugg.project.data.LibraryDependency
import com.sickworm.intellij.jugg.project.data.ModuleBuildPathInfo
import java.io.File

class BaseCompileContext(
    private val project: Project,
    override val logger: Logger,
    override var tempCompileDir: File,
    override var tempModuleDir: File,
    override val androidHome: File,
    override var modules: Map<String, ModuleInfo> = emptyMap(),
    override var apkInfos: List<ApkInfo> = emptyList(),
    override val projectDir: File,
    private val deployFileManager: DeployFileManager,
    private val deployHistoryManager: IDeployHistoryManager,
): ICompileContext {

    private val androidJarApi: String = getSuggestedPlatformApi(modules)
    override val androidJar: File = File(androidHome, "platforms/android-$androidJarApi/android.jar")

    private val tempLibraryDir: File = File(tempModuleDir, "libs")
    private val tempLibraryRecordFile: File = File(tempLibraryDir, "infos.json")
    override var tempModule = ModuleInfo.virtualModule.copy(
        name = "temp_module",
        buildPathInfo = ModuleBuildPathInfo(projectDir, tempModuleDir, ModuleInfo.DEFAULT_BUILD_VARIANT),
        libraryDependencies = loadTempLibraries(),
    )
        private set

    private val signingConfigList: List<SigningConfig> get() =
        PlatformApi.getAndroidRunConfigList(project, logger).flatMap { it.signingConfigList }

    override val deployedFiles: List<CompileOutput> get() = deployFileManager.getDeployedFiles()

    private val listeners = mutableListOf<OnContextUpdate>()

    // currently Jugg only keep the final R.jar which is in the application module, for better copying speed in remote compile mode
    private var finalRFiles: List<String> = emptyList()

    private fun getRFiles(): List<String> {
        return modules.mapNotNull { module ->
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

    override val applicationModule: ModuleInfo? by lazy {
        val applicationModules = modules.values.filter { module ->
            val rFile = module.buildPathInfo.rFilePath
            return@filter rFile.exists()
        }
        if (applicationModules.isEmpty()) {
            logger.debug("get application module failed, no module has R.jar")
            return@lazy null
        }
        if (applicationModules.size == 1) {
            logger.debug("get application module returns ${applicationModules.first().name}, with only one has R.jar")
            return@lazy applicationModules.first()
        }

        logger.debug("get application module package name in APK: $packageName")
        logger.debug("get application module has multiple modules has R.jar, ${applicationModules.joinToString { it.name }}")

        applicationModules.forEach {
            val mergedManifest = it.buildPathInfo.mergedManifest
            if (!mergedManifest.exists()) {
                logger.debug("get application module failed, ${it.name}'s merged manifest not found, ignore")
                return@forEach
            }
            val mergedManifestXmlNode = XmlParser().parse(mergedManifest)
            val packageNameInManifest = mergedManifestXmlNode.node["package"]
            if (packageNameInManifest == packageName) {
                logger.debug("get application module auto match success, ${it.name} has same package name $packageNameInManifest")
                return@lazy it
            } else {
                logger.debug("get application module, ${it.name} has different package name $packageNameInManifest, continue.")
                return@forEach
            }
        }

        logger.debug("get application module auto match failed, use first module as application module.")
        return@lazy applicationModules.first()
    }

    override val signingConfig: SigningConfig? get() {
        val applicationModule = applicationModule
        if (applicationModule == null) {
            logger.debug("get signing config failed, no application module found.")
            return null
        }

        logger.debug("available signingConfigList: ${signingConfigList.map { "${it.moduleName}(${it.variantName})"}}")
        val applicationModuleName = applicationModule.name
        val findConfigLog = "${applicationModuleName}(${applicationModule.buildVariant})"
        logger.debug("trying to find config $findConfigLog")

        val relativeSigningConfig = signingConfigList.filter {
            it.moduleName == applicationModuleName
        }.let { list ->
            list.find {
                // first find full match, e.g. debug to debug
                it.variantName == applicationModule.buildVariant
            } ?: list.find {
                // then find partial match, e.g. developmentFreeDebug to debug
                applicationModule.buildVariant.contains(it.variantName, ignoreCase = true)
            }
        }
        if (relativeSigningConfig == null) {
            logger.debug("get signing config failed, no signing config found")
            return null
        }

        logger.debug("get signing config by $findConfigLog success (don't print it out for security)")
        return relativeSigningConfig
    }

    override val isEnableDesugared: Boolean by lazy {
        deployFileManager.isEnableDesugared()
    }

    override fun getModuleDependencies(moduleInfo: ModuleInfo, task: CompileTask): List<String> {
        val androidJar = getAndroidJarPath(moduleInfo)

        var tempDependencies: List<String> = tempModule.buildPathInfo.allClassPath.filter {
            it.exists()
        }.map {
            it.absolutePath
        }
        val tempLibraryDependency = tempModule.libraryDependencies
            .filter { it.isValid && !it.isAndroidManifest && !it.isRes }
            .map { it.file.absolutePath }
        tempDependencies = tempDependencies + tempLibraryDependency

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
            finalRFiles = getRFiles()
            if (finalRFiles.isEmpty()) {
                logger.warn("No R.jar found in project, compile may fail.")
            }
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

    override fun getLastBuildAndroidManifest(file: CompileFile): File? {
        val changedFile = ChangedFile(file.type, file.file, file.baseDir, file.module, file.extraInfo)
        return deployHistoryManager.getLastBuildFiles(listOf(changedFile)).firstOrNull()?.second
    }

    override fun listenUpdate(listener: OnContextUpdate) {
        synchronized(listeners) {
            if (!listeners.contains(listener)) {
                listeners.add(listener)
            }
        }
    }

    fun update(
        apkInfos: List<ApkInfo>? = null,
        modules: Map<String, ModuleInfo>? = null,
        addedTempLibraries: List<LibraryDependency>? = null,
        removedTempLibraries: List<LibraryDependency>? = null,
    ) {
        apkInfos?.let {
            this.apkInfos = it
        }
        modules?.let {
            this.modules = HashMap(it)
        }
        if (addedTempLibraries != null || removedTempLibraries != null) {
            val oldLibraries = loadTempLibraries().toMutableList()
            if (removedTempLibraries != null) {
                oldLibraries.removeIf { old ->
                    removedTempLibraries.any { remove ->
                        old.name == remove.name && old.type == remove.type
                    }
                }
            }
            tempLibraryDir.deleteRecursively()
            tempLibraryDir.mkdirs()
            val finalTempLibraries = saveTempLibraries(addedTempLibraries ?: emptyList(), oldLibraries)
            this.tempModule = tempModule.copy(libraryDependencies = finalTempLibraries)
        }
        dispatch()
    }

    private fun saveTempLibraries(newLibraries: List<LibraryDependency>, oldLibraries: List<LibraryDependency>): List<LibraryDependency> {
        val savedTempLibraries = newLibraries.map {
            val path = it.name.replace(':', File.separatorChar) + File.separator + it.type + File.separator + it.file.name
            val outputFile = File(tempLibraryDir, path)
            outputFile.parentFile.mkdirs()
            it.file.copyRecursively(outputFile, overwrite = true)
            LibraryDependency(it.name, outputFile, it.lastModifiedTime, it.crc32)
        }
        // the newer, the higher priority
        val finalTempLibraries = (savedTempLibraries + oldLibraries).distinctBy { it.file.path }

        tempLibraryRecordFile.delete()
        tempLibraryRecordFile.parentFile.mkdirs()
        tempLibraryRecordFile.writeText(Gson().toJson(finalTempLibraries))

        return finalTempLibraries
    }

    private fun loadTempLibraries(): List<LibraryDependency> {
        if (!tempLibraryRecordFile.exists()) {
            return emptyList()
        }
        return try {
            Gson().fromJson(tempLibraryRecordFile.readText(), Array<LibraryDependency>::class.java).toList()
        } catch (e: Exception) {
            emptyList()
        }
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