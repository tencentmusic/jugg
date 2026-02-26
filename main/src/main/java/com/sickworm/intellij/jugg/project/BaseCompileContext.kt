package com.sickworm.intellij.jugg.project

import com.sickworm.intellij.jugg.apk.ApkInfo
import com.google.gson.Gson
import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.jugg.ModuleApkBelongsUtils
import com.sickworm.intellij.jugg.apk.ApkFileUnit
import com.sickworm.intellij.jugg.compiler.*
import com.sickworm.intellij.jugg.compiler.custom.CustomCompilerManager
import com.sickworm.intellij.jugg.compiler.manifest.XmlParser
import com.sickworm.intellij.jugg.compiler.manifest.get
import com.sickworm.intellij.jugg.compiler.obfuscation.MinifyInfo
import com.sickworm.intellij.jugg.project.data.ModuleInfo
import com.sickworm.intellij.jugg.deploy.DeployFileManager
import com.sickworm.intellij.jugg.deploy.IDeployHistoryManager
import com.sickworm.intellij.jugg.project.data.LibraryDependency
import com.sickworm.intellij.jugg.project.data.ModuleBuildPathInfo
import com.sickworm.intellij.jugg.project.data.SigningConfig
import java.io.File
import java.util.zip.ZipFile

/**
 * BaseCompileContext builds and serves compile-time project context (modules, APK metadata, temp dirs, generated outputs, and desugar info) for compiler stages.
 * Collaboration: Resolves module-to-apk ownership via [ModuleApkBelongsUtils], exposes deployed/custom compiler state from [DeployFileManager] and [CustomCompilerManager], and provides inputs consumed by compiler/deploy flows through [ICompileContext].
 * Data Contract: [androidJar] is derived from suggested platform api in [modules]; [tempModule] is a synthetic module rooted at [tempModuleDir]; [dynamicFeatureModules] is initialized before [applicationModule] to keep module selection consistent.
 */
class BaseCompileContext(
    override val logger: Logger,
    override var tempCompileDir: File,
    override var tempModuleDir: File,
    override val androidHome: File,
    override var modules: Map<String, ModuleInfo> = emptyMap(),
    override var apkInfos: List<ApkInfo> = emptyList(),
    override val projectDir: File,
    override val incrementalDataDir: File,
    override val cmdCompileEnv: List<String>,
    override val scene: ICompileContext.Scene,
    private val deployFileManager: DeployFileManager,
    private val deployHistoryManager: IDeployHistoryManager,
    private val customCompilerManager: CustomCompilerManager,
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

    override val customCompilers: List<ICompiler> get() = customCompilerManager.getCustomCompilers()

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
            logger.debug("get application module returns ${applicationModules.first().name}, with only one module")
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

    override var moduleBelongsApkMap: Map<ModuleInfo, ApkFileUnit> = ModuleApkBelongsUtils.getModuleApkBelongs(applicationModule, apkInfos, modules, tempModule, logger)

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
            logger.debug("${moduleInfo.name} found parent module ${it.name}")
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
                if (it.isAndroidManifest || it.isRes || it.isKlib) {
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

        val libraryDependencies = getParentModules(moduleInfo, true).flatMap { it.libraryDependencies }
        logger.debug("printClasspathCheck libraryDependencies size: ${libraryDependencies.size}")
        libraryDependencies.forEach {
            if (!it.file.exists()) {
                logger.debug("library dependency $it not exists")
            }
        }

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
        tempModule.buildPathInfo.generatedSourcePath.listFiles()?.forEach {
            val baseDir = File(it, "${moduleInfo.buildVariant}/out")
            if (baseDir.exists()) {
                dirs.add(baseDir)
            }
        }
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

    private var desugaredLibraryConfigurationCache: MutableMap<String, String?> = mutableMapOf()

    override fun getDesugarInfo(compileFiles: List<CompileFile>, moduleInfo: ModuleInfo, toDir: File): DesugarInfo {
        val apkFile = moduleBelongsApkMap[moduleInfo]!!.apkFile // should not be null
        val incompleteInfo = deployFileManager.getDesugarInfo(compileFiles, moduleInfo, toDir, apkFile)

        return if (incompleteInfo.isNeedRewriteCoreLibrary) {
            incompleteInfo.copy(desugaredLibraryConfiguration = findDesugaredLibraryConfigurationWithCache(moduleInfo))
        } else {
            incompleteInfo
        }
    }

    override fun getMinifyInfo(): MinifyInfo? {
        return deployFileManager.getMinifyInfo()
    }

    private fun findDesugaredLibraryConfigurationWithCache(moduleInfo: ModuleInfo): String? {
        val apkFile = moduleBelongsApkMap[moduleInfo]!!.apkFile // should not be null
        desugaredLibraryConfigurationCache[apkFile.path]?.let { return it }

        val targetModule = findRelativeApkModule(moduleInfo) ?: moduleInfo
        desugaredLibraryConfigurationCache[apkFile.path] = findDesugaredLibraryConfigurationTarget(targetModule)
        return desugaredLibraryConfigurationCache[apkFile.path]
    }

    private fun findRelativeApkModule(moduleInfo: ModuleInfo): ModuleInfo {
        val apkFile = moduleBelongsApkMap[moduleInfo]!!.apkFile // should not be null
        val applicationModule = applicationModule
        if (applicationModule != null && moduleBelongsApkMap[applicationModule]?.apkFile == apkFile) {
            return applicationModule
        }
        dynamicFeatureModules.forEach {
            if (moduleBelongsApkMap[it]?.apkFile == apkFile) {
                return it
            }
        }

        val fallback = applicationModule ?: moduleInfo
        logger.debug("findRelativeApkModule failed, cannot find ${moduleInfo.name} relative apk module, " +
                "use ${fallback.name} for fallback.")
        return fallback
    }

    private fun findDesugaredLibraryConfigurationTarget(targetModule: ModuleInfo): String? {
        var result = findDesugaredLibraryConfiguration(targetModule)
        if (result != null) {
            logger.debug("coreLibraryDesugaring found in targetModule: ${applicationModule?.name}")
            return result
        }
        modules.values.forEach { moduleInfo ->
            result = findDesugaredLibraryConfiguration(moduleInfo)
            if (result != null) {
                logger.debug("coreLibraryDesugaring found in module: ${moduleInfo.name}")
                return result
            }
        }

        logger.warn("coreLibraryDesugaring not found, desugaring may not work correctly, sync gradle again helps.")
        return null
    }

    private fun findDesugaredLibraryConfiguration(moduleInfo: ModuleInfo?): String? {
        moduleInfo?.coreLibraryDesugaring?.forEach { dependency ->
            val targetConfigJar = dependency.file
            ZipFile(targetConfigJar).use { zipFile ->
                val entry = zipFile.getEntry("META-INF/desugar/d8/desugar.json")
                if (entry == null) {
                    logger.debug("coreLibraryDesugaring desugar.json not found in $targetConfigJar")
                    return@use
                }
                logger.debug("coreLibraryDesugaring desugar.json found in $targetConfigJar")
                zipFile.getInputStream(entry).use { inputStream ->
                    val result = inputStream.reader().readText()
                    logger.debug("coreLibraryDesugaring get desugaredLibraryConfiguration from desugar.json done in $targetConfigJar, " +
                            "part of content: ${result.substring(0, 200)?.replace("\n", "")}")
                    return result
                }
            }
        }
        return null
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

    private val modulePackageNameCacheMap = mutableMapOf<String, String>()

    override fun getModulePackageName(moduleInfo: ModuleInfo): String? {
        modulePackageNameCacheMap[moduleInfo.name]?.let { return it }

        logger.debug("getModulePackageName: ${moduleInfo.name}")
        // namespace and package must exist one of them, and namespace has higher priority, or will get in gradle:
        // Package Name not found in xxx/AndroidManifest.xml, and namespace not specified.
        val namespaceInGradle = moduleInfo.namespace
        if (namespaceInGradle != null) {
            logger.debug("getModulePackageName select namespaceInGradle: $namespaceInGradle")
            modulePackageNameCacheMap[moduleInfo.name] = namespaceInGradle
            return namespaceInGradle
        }

        val manifestFile = moduleInfo.manifestFile
        if (manifestFile == null || !manifestFile.exists()) {
            logger.debug("manifest file not found in ${moduleInfo.name}")
            return null
        }

        val xmlNode = XmlParser().parse(moduleInfo.manifestFile)
        val packageNameInManifest = xmlNode.node["package"]
        logger.debug("getModulePackageName select packageNameInManifest: $packageNameInManifest")
        if (packageNameInManifest != null) {
            modulePackageNameCacheMap[moduleInfo.name] = packageNameInManifest
        }
        return packageNameInManifest
    }

    override fun backupGradleDir(sourceDir: File, overrideOnExists: Boolean, dryRun: Boolean): File {
        val projectRootDir = modules.values.first().projectRootDir
        val relativePath = sourceDir.relativeTo(projectRootDir).path.replace("..", "__")
        val targetDir = File(incrementalDataDir, relativePath)
        logger.debug("backupGradleDir from $sourceDir(exists: ${sourceDir.exists()}) to " +
                "$targetDir(exists: ${targetDir.exists()}), overrideOnExists: $overrideOnExists, dryRun: $dryRun")
        if (dryRun) {
            return targetDir
        }
        if (targetDir.exists() || overrideOnExists) {
            targetDir.deleteRecursively()
            if (sourceDir.exists()) {
                sourceDir.copyRecursively(targetDir, overwrite = true)
            } else {
                targetDir.mkdirs()
            }
        }
        return targetDir
    }

    fun update(
        apkInfos: List<ApkInfo>? = null,
        modules: Map<String, ModuleInfo>? = null,
        addedTempLibraries: List<LibraryDependency>? = null,
        removedTempLibraries: List<LibraryDependency>? = null,
    ) {
        apkInfos?.let {
            this.apkInfos = it
            moduleBelongsApkMap = ModuleApkBelongsUtils.getModuleApkBelongs(applicationModule, this.apkInfos, this.modules, tempModule, logger)
        }
        modules?.let {
            this.modules = HashMap(it)
            finalRFiles = getRFiles()
            dynamicFeatureModules = findDynamicFeatureModules() // must run before findApplicationModule
            applicationModule = findApplicationModule()
            modulesWithOrder = ModuleCompileOrderUtils.getModuleCompileOrders(this.modules, tempModule, logger)
            moduleBelongsApkMap = ModuleApkBelongsUtils.getModuleApkBelongs(applicationModule, this.apkInfos, this.modules, tempModule, logger)
            modulePackageNameCacheMap.clear()
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
            moduleBelongsApkMap = ModuleApkBelongsUtils.getModuleApkBelongs(applicationModule, this.apkInfos, this.modules, tempModule, logger)
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
