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
import com.sickworm.intellij.jugg.gradle.compile.LocalGradleCompileClient
import com.sickworm.intellij.jugg.gradle.compile.isChild
import com.sickworm.intellij.jugg.project.data.LibraryDependency
import com.sickworm.intellij.jugg.project.data.ModuleBuildPathInfo
import com.sickworm.intellij.jugg.project.data.SigningConfig
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
        projectRootDir = projectDir,
        moduleRootDir = tempModuleDir,
        buildPathInfo = ModuleBuildPathInfo(projectDir, tempModuleDir, ModuleInfo.DEFAULT_BUILD_VARIANT),
        libraryDependencies = loadTempLibraries(),
    )
        private set

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

    override var dynamicFeatureModules: List<ModuleInfo> = findDynamicFeatureModules() // must run before findApplicationModule()

    override var applicationModule: ModuleInfo? = findApplicationModule()

    private fun findApplicationModule(): ModuleInfo? {
        var applicationModules = modules.values.filter { module ->
            module.moduleType == ModuleInfo.Type.Application
        }

        if (applicationModules.isEmpty()) {
            logger.debug("get application module failed, no module type is Application")
            applicationModules = modules.values.filter { module ->
                // dynamic feature module also has this rFilePath!
                val rFile = module.buildPathInfo.rFilePath
                return@filter rFile.exists()
            }
        }

        if (applicationModules.isEmpty()) {
            logger.debug("get application module failed, no module has R.jar")

            // maybe low AGP version, try to find library R file
            val isLowAgp = modules.values.any { it.buildPathInfo.libraryRFilePathInLowAgp.exists() }
            logger.debug("it's there any library R file exists? $isLowAgp")
            if (isLowAgp) {
                applicationModules = modules.values.filter { !it.buildPathInfo.libraryRFilePathInLowAgp.exists() }
            }
            logger.debug("filter applicationModules by library R file: $applicationModules")
            if (applicationModules.isEmpty()) {
                return null
            }
        }
        if (applicationModules.size == 1) {
            logger.debug("get application module returns ${applicationModules.first().name}, with only one has R.jar")
            return applicationModules.first()
        }

        logger.debug("get application module package name in APK: $packageName")
        logger.debug("get application module has multiple modules has R.jar, ${applicationModules.joinToString { it.name }}")

        applicationModules.forEach {
            if (it in dynamicFeatureModules) {
                logger.debug("get application module failed, ${it.name} is a dynamic feature module, ignore")
                return@forEach
            }

            val mergedManifest = it.buildPathInfo.mergedManifest
            if (!mergedManifest.exists()) {
                logger.debug("get application module failed, ${it.name}'s merged manifest not found, ignore")
                return@forEach
            }
            val mergedManifestXmlNode = XmlParser().parse(mergedManifest)
            val packageNameInManifest = mergedManifestXmlNode.node["package"]
            if (packageNameInManifest == packageName) {
                logger.debug("get application module auto match success, ${it.name} has same package name $packageNameInManifest")
                return it
            } else {
                logger.debug("get application module, ${it.name} has different package name $packageNameInManifest, continue.")
                return@forEach
            }
        }

        logger.debug("get application module auto match failed, use first module as application module.")
        return applicationModules.first()
    }

    private fun findDynamicFeatureModules(): List<ModuleInfo> {
        return modules.values.filter { module ->
            if (module.moduleType == ModuleInfo.Type.DynamicFeature) {
                return@filter true
            }
            if (module.moduleType == ModuleInfo.Type.Unknown) {
                val mergedManifest = module.buildPathInfo.mergedManifest
                if (!mergedManifest.exists()) {
                    // many modules have no merged manifest in kmm project like app.jsMain
//                    logger.debug("get dynamic feature module failed, ${module.name}'s merged manifest not found, ignore")
                    return@filter false
                }
                val mergedManifestXmlNode = XmlParser().parse(mergedManifest)
                val featureSplit = mergedManifestXmlNode.node["featureSplit"]
                return@filter featureSplit != null
            }
            return@filter false
        }
    }

    override val signingConfig: SigningConfig? get() {
        val applicationModule = applicationModule
        if (applicationModule == null) {
            logger.debug("get signing config failed, no application module found.")
            return null
        }

        logger.debug("available signing variants: ${applicationModule.variants}, " +
                "target buildVariant: ${applicationModule.buildVariant}")
        logger.debug("available signingConfigList: ${applicationModule.signingConfigs?.map { it.configName }}")

        val variant = applicationModule.variants.find {
            it.name == applicationModule.buildVariant
        }
        if (variant == null) {
            logger.debug("get signing config failed, no variant found.")
            return null
        }
        val relativeSigningConfig = applicationModule.signingConfigs?.find {
            it.configName == variant.signingConfigName
        }
        if (relativeSigningConfig == null) {
            logger.debug("get signing config failed, no relativeSigningConfig found.")
            return null
        }

        logger.debug("get signing config success, use " +
                "${relativeSigningConfig.configName} -> ${relativeSigningConfig.keystore?.path} " +
                "(don't print all for security)")
        return relativeSigningConfig
    }

    override val isEnableDesugared: Boolean by lazy {
        deployFileManager.isEnableDesugared()
    }

    override var modulesWithOrder: List<ModuleInfo> = ModuleCompileOrderUtils.getModuleCompileOrders(modules, tempModule, logger)

    override val cmdCompileEnv: List<String>
        get() = LocalGradleCompileClient.buildCompileEnv(project, logger)

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
        val libraryDependency = moduleInfo.getLibraryDependencyPaths()

        // handles library1.commonMain only has .klib dependencies, read it in library1
        val parentLibraryModuleDependency = mutableListOf<String>()
        getParentModules(moduleInfo, isAddSelfToResult = false).forEach {
            parentLibraryModuleDependency.addAll(it.getLibraryDependencyPaths())
        }

        if (finalRFiles.isEmpty()) {
            finalRFiles = getRFiles()
            if (finalRFiles.isEmpty()) {
                logger.debug("No R.jar found in project, maybe is a low AGP version that will stored in " +
                        "build/intermediates/javac/debug/compileDebugJavaWithJavac/classes.")
            }
        }

        val dependencies = mutableListOf(androidJar)
        dependencies.addAll(tempDependencies)
        dependencies.addAll(classpathDependencies)
        dependencies.addAll(moduleDependencies)
        dependencies.addAll(libraryDependency)
        dependencies.addAll(parentLibraryModuleDependency)
        dependencies.addAll(finalRFiles) // place to the last, to let R file compiled into classpathDependencies go first

        task.files.forEach {
            dependencies.addAll(it.dependencyPaths)
        }

        return dependencies
    }

    private fun ModuleInfo.getLibraryDependencyPaths(): List<String> {
        return libraryDependencies
            .filter {
                // filter unnecessary LibraryDependency for source file compilation
                val isInBuildDir = it.file.isChild(this.buildPathInfo.buildDir)
                if (isInBuildDir || it.isAndroidManifest || it.isRes || it.isKlib) {
                    return@filter false
                }
                if (!it.isValid) {
                    logger.debug("library dependency file ${it.file} not found")
                    logger.warn("library dependency [${it.name}] not found, maybe sync again helps.")
                    return@filter false
                }
                return@filter true
            }.map {
                it.file.absolutePath
            }
    }

    override fun printClasspathCheck(moduleInfo: ModuleInfo) {
        logger.debug("printClasspathCheck: ${moduleInfo.name}")

        printParentTree(moduleInfo.buildPathInfo.javaClassPath, moduleInfo.buildPathInfo.moduleRootDir)
        printParentTree(moduleInfo.buildPathInfo.kotlinClassPath, moduleInfo.buildPathInfo.moduleRootDir)
        moduleInfo.moduleDependencies.forEach {
            val dependencyModuleInfo = modules[it.moduleName] ?: run {
                logger.debug("module ${it.moduleName} not found in ${moduleInfo.name}'s dependencies, maybe sync gradle again helps.")
                return@forEach
            }
            printParentTree(dependencyModuleInfo.buildPathInfo.javaClassPath, dependencyModuleInfo.buildPathInfo.moduleRootDir)
            printParentTree(dependencyModuleInfo.buildPathInfo.kotlinClassPath, dependencyModuleInfo.buildPathInfo.moduleRootDir)
        }
    }

    private fun printParentTree(file: File, rootDir: File, level: Int = 3) {
        try {
            if (file.exists()) {
                logger.debug("printParentTree: $file exists")
                 return
            }

            logger.debug("printParentTree: for $file not exists")

            var currentFile: File? = file.parentFile
            var remainLevel: Int = level
            while (remainLevel > 0 && currentFile != null) {
                val isExists = currentFile.exists()
                val subFiles: Any? = if (isExists) currentFile.listFiles()?.map { it.name } else emptyList()
                logger.debug("printParentTree: ${currentFile.relativeTo(rootDir)} (exists: $isExists), subFiles: $subFiles")
                currentFile = currentFile.parentFile
                remainLevel--
                if (isExists) {
                    break
                }
            }
        } catch (e: Exception) {
            logger.debug("printParentTree failed: $e")
        }
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

    override fun getParentModules(moduleInfo: ModuleInfo, isAddSelfToResult: Boolean): List<ModuleInfo> {
        val result = mutableListOf<ModuleInfo>()
        if (isAddSelfToResult) {
            result.add(moduleInfo)
        }

        var parentModuleName = moduleInfo.name
        while (parentModuleName.isNotEmpty()) {
            if (!parentModuleName.contains('.')) {
                break
            }
            parentModuleName = parentModuleName.substringBeforeLast('.')
            val parentModuleInfo = modules[parentModuleName] ?: break
            result.add(parentModuleInfo)
            logger.debug("${moduleInfo.name} found parent module $parentModuleName")
        }
        return result
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
            finalRFiles = getRFiles()
            dynamicFeatureModules = findDynamicFeatureModules() // must run before findApplicationModule
            applicationModule = findApplicationModule()
            modulesWithOrder = ModuleCompileOrderUtils.getModuleCompileOrders(this.modules, tempModule, logger)
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
            modulesWithOrder = ModuleCompileOrderUtils.getModuleCompileOrders(this.modules, tempModule, logger)
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
            Gson().fromJson(tempLibraryRecordFile.readText(), Array<LibraryDependency>::class.java)
                .map {
                    // covert to absolute path file
                    it.copy(file = File(it.file.path))
                }
                .toList()
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
        var version = getLatestVersion(versionsInGradle.map { it.second?.substringAfter("android-") } )
        if (version != null && version.isLargerThan("34")) {
            // aapt2 not supports android-35.jar for now.
            // error: 'match_parent' is incompatible with attribute layout_height (attr) dimension|enum
            // Need some time to fix it.
            version = "34"
        }

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
                // aapt2 not supports android-35.jar for now.
                // error: 'match_parent' is incompatible with attribute layout_height (attr) dimension|enum
                // Need some time to fix it.
                if (it.name.substring("android-".length).isLargerThan("34")) return@mapNotNull null

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