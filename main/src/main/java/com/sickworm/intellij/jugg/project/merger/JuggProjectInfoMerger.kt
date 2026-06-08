package com.sickworm.intellij.jugg.project.merger

import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.jugg.compiler.BuildTarget
import com.sickworm.intellij.jugg.ide.bean.JuggSettings
import com.sickworm.intellij.jugg.logger.TimeLogger
import com.sickworm.intellij.jugg.logger.getInstance
import com.sickworm.intellij.jugg.project.ModulePathMergePolicy
import com.sickworm.intellij.jugg.project.ProjectInfoSerializer
import com.sickworm.intellij.jugg.project.data.JuggProjectInfo
import com.sickworm.intellij.jugg.project.data.ModuleDependency
import com.sickworm.intellij.jugg.project.data.ModuleInfo
import java.text.SimpleDateFormat
import java.util.*

/**
 * Why we need merge project infos from several sources instead of use one of it？
 *
 * 1. project info from IDE is not complete, need to filled by project info from gradle
 *  1.1. IDE cannot read complex expression of attribute, e.g. "minSdkVersion version.my_version".
 *  1.2. IDE sometimes miss some dependencies, especially huge library.
 *
 * 2. project info from gradle may not reliable, it needs time to proof.
 *
 * 3. gradle may delete libraries cache to clear space.
 *
 */
interface IJuggProjectInfoMerger {

    val juggProjectInfo: JuggProjectInfo?

    /** read from project info */
    fun afterSync(projectInfoSerialize: ProjectInfoSerializer, buildTarget: BuildTarget): JuggProjectInfoMergeResult

    /**
     * e.g. ./gradlew --dry-run -I readProjectInfo.gradle.kts
     */
    fun afterLocalFetch(projectInfoSerialize: List<ProjectInfoSerializer>, buildTarget: BuildTarget): JuggProjectInfoMergeResult
}

/**
 * JuggProjectInfoMerger merges IDE and Gradle snapshots into one project model,
 * reconciles module-name mismatches, and decides whether dependency data should be refreshed.
 */
class JuggProjectInfoMerger(
    loggerArg: Logger,
) : IJuggProjectInfoMerger {

    private val logger = loggerArg.getInstance("JuggProjectInfoMerger")

    private var ide: ProjectInfoSerializer? = null
    private var localFetch: List<ProjectInfoSerializer> = emptyList()

    override var juggProjectInfo: JuggProjectInfo? = null

    override fun afterSync(
        projectInfoSerialize: ProjectInfoSerializer,
        buildTarget: BuildTarget,
    ): JuggProjectInfoMergeResult {
        ide = projectInfoSerialize
        val result = merge(buildTarget)
        juggProjectInfo = result.mergedInfo
        return result
    }

    override fun afterLocalFetch(
        projectInfoSerialize: List<ProjectInfoSerializer>,
        buildTarget: BuildTarget,
    ): JuggProjectInfoMergeResult {
        localFetch = projectInfoSerialize
        val result = merge(buildTarget)
        juggProjectInfo = result.mergedInfo
        return result
    }

    private fun merge(buildTarget: BuildTarget): JuggProjectInfoMergeResult {
        TimeLogger.start("merge")

        // use ideProjectInfo as base project info
        val ideProjectInfo = ide?.load()
        if (ideProjectInfo == null) {
            logger.debug("IDE project info is null, exit merge")
            return JuggProjectInfoMergeResult.createEmpty()
        }

        if (!JuggSettings.finalIsEnableReadProjectInfoFromGradle) {
            logger.debug("isEnableReadProjectInfoFromGradle = false, use ideProjectInfo directly")
            return JuggProjectInfoMergeResult.createSingle(ideProjectInfo)
        }

        val localFetchUpdateTime = localFetch.firstOrNull()?.dataFile?.lastModified() ?: -1L
        val ideUpdateTime = ide?.dataFile?.lastModified() ?: -1L
        logger.debug("localFetchUpdateTime ${localFetchUpdateTime.timeStampToTime()}, " +
                "ideUpdateTime ${ideUpdateTime.timeStampToTime()}")

        val gradleProjectInfoModules = mutableMapOf<String, ModuleInfo>()
        localFetch.forEach { projectInfoSerializer ->
            val gradleProjectInfo = projectInfoSerializer.load()
            logger.debug("localFetch file: ${projectInfoSerializer.dataFile.path }, " +
                    "modules: ${gradleProjectInfo?.modules?.map { it.key }}")
            gradleProjectInfo?.modules?.forEach { moduleInfo ->
                if (!gradleProjectInfoModules.containsKey(moduleInfo.key)) {
                    gradleProjectInfoModules[moduleInfo.key] = moduleInfo.value
                }
            }
        }
        val gradleProjectInfo = JuggProjectInfo(gradleProjectInfoModules)

        if (gradleProjectInfo.modules.isEmpty()) {
            logger.debug("gradleProjectInfo is null, exit merge and use ideProjectInfo directly")
            return JuggProjectInfoMergeResult.createSingle(ideProjectInfo)
        }

        var isNeedUpdateLibraryDependency = true
        val gradleUpdateTime = localFetch.firstOrNull()?.dataFile?.lastModified() ?: 0L
        if (ideUpdateTime > gradleUpdateTime) {
            logger.debug("ide project info is newer than gradle project info, isNeedUpdateLibraryDependency=false")
            isNeedUpdateLibraryDependency = false
        } else {
            logger.debug("ide project info is older than gradle project info, isNeedUpdateLibraryDependency=true")
        }

        val result = doMerge(ideProjectInfo, gradleProjectInfo, isNeedUpdateLibraryDependency, buildTarget)
        logger.debug("merge result: $result")

        TimeLogger.end("merge", logger)
        return result
    }

    private fun doMerge(
        ideProjectInfo: JuggProjectInfo,
        gradleProjectInfo: JuggProjectInfo,
        isNeedUpdateDependency: Boolean,
        buildTarget: BuildTarget,
    ): JuggProjectInfoMergeResult {
        val mergedModules = mutableMapOf<String, ModuleInfo>()
        val mergeResult = JuggProjectInfoMergeResult.createEmpty().copy(
            isNeedUpdateDependency = isNeedUpdateDependency
        )

        // sometimes, module in ide will have a wired name e.g. library1.MyApplication.library1.main
        // then Jugg will wrongly parse the module name as MyApplication.library1 (activity it's library1)
        // here we update name in gradle project info to match ide
        val nameUpdateMap = mutableMapOf<String, String>()
        val updateModules = gradleProjectInfo.modules.toMutableMap()
        gradleProjectInfo.modules.values.forEach { module ->
            val ideModuleName = ModulePathMergePolicy.resolveIdeModuleName(module, ideProjectInfo.modules.values)
            val gradleModuleName = module.name
            if (ideModuleName != null &&
                ModulePathMergePolicy.shouldAlignGradleModuleName(module, gradleModuleName, ideModuleName)
            ) {
                logger.debug("gradle module $gradleModuleName will update name to $ideModuleName")
                nameUpdateMap[gradleModuleName] = ideModuleName
            }
        }
        nameUpdateMap.forEach { (oldName, newName) ->
            updateModules[newName] = updateModules[oldName]!!
            updateModules.remove(oldName)
        }
        val finalUpdateModules = updateModules.mapValues { (_, moduleInfo) ->
            moduleInfo.copy(moduleDependencies = moduleInfo.moduleDependencies.map {
                ModuleDependency(nameUpdateMap[it.moduleName] ?: it.moduleName)
            })
        }
        val finalGradleProjectInfo = JuggProjectInfo(finalUpdateModules)

        val libraryMerger = JuggProjectInfoLibraryMerger(logger)
        val noMergeModules = mutableListOf<String>()
        ideProjectInfo.modules.forEach { (name, moduleInfo) ->
            val gradleModuleInfo = finalGradleProjectInfo.modules[name]
            if (gradleModuleInfo == null) {
                if (!ModulePathMergePolicy.shouldIncludeIdeOnlyModule(moduleInfo, buildTarget)) {
                    logger.debug(
                        "module ${moduleInfo.name} is IDE-only ${ModulePathMergePolicy.classify(moduleInfo)} " +
                                "snapshot, skip for buildTarget=$buildTarget"
                    )
                    return@forEach
                }
                noMergeModules.add(name)
                mergedModules[name] = moduleInfo
                return@forEach
            }
            if (gradleModuleInfo.moduleType == ModuleInfo.Type.Unknown) {
                // when org.gradle.configureondemand=true, and after ./gradlew :app:assembleDebug -I readProjectInfos.gradle.kts,
                // project that won't be compiled will be empty and moduleType will be Unknown
                logger.debug("module $name type is unknown, won't merge")
                mergedModules[name] = moduleInfo
                return@forEach
            }

            fun <T> chooseValue(gradle: T?, ide: T?): T? {
                if (isNeedUpdateDependency) {
                    if (gradle != null) {
                        return gradle
                    }
                }
                if (ide != null) {
                    return ide
                }
                return gradle
            }

            // merge with different strategy
            val buildVariant = ModulePathMergePolicy.selectMergedBuildVariant(moduleInfo, gradleModuleInfo)
            if (buildVariant != gradleModuleInfo.buildVariant && buildVariant == moduleInfo.buildVariant) {
                logger.debug(
                    "module ${moduleInfo.name} build variant not match, " +
                            "ide:${moduleInfo.buildVariant} vs gradle:${gradleModuleInfo.buildVariant}, " +
                            "use ${moduleInfo.buildVariant}"
                )
            } else if (buildVariant != moduleInfo.buildVariant) {
                logger.debug(
                    "module ${moduleInfo.name} build variant not match, " +
                            "ide:${moduleInfo.buildVariant} vs gradle:${gradleModuleInfo.buildVariant}, " +
                            "use ${gradleModuleInfo.buildVariant}"
                )
            }
            val mergedModuleInfo = ModuleInfo(
                name = moduleInfo.name,
                moduleType = gradleModuleInfo.moduleType,
                moduleRootDir = moduleInfo.moduleRootDir,
                projectRootDir = moduleInfo.projectRootDir,
                sourceDirs = mergeWithBase(name, "sourceDirs", moduleInfo.sourceDirs, gradleModuleInfo.sourceDirs, mergeResult) { it.absolutePath },
                resourceDirs = mergeWithBase(name, "resourceDirs", moduleInfo.resourceDirs, gradleModuleInfo.resourceDirs, mergeResult) { it.absolutePath },
                assetsDirs = mergeWithBase(name, "assetsDirs", moduleInfo.assetsDirs, gradleModuleInfo.assetsDirs, mergeResult) { it.absolutePath },
                manifestFile = moduleInfo.manifestFile, // gradleModuleInfo.manifestFile may not exist, it will always return debug/AndroidManifestFest.xml
                buildVariant = buildVariant,
                compileVersion = chooseValue(gradleModuleInfo.compileVersion, moduleInfo.compileVersion),
                minSdkVersion = chooseValue(gradleModuleInfo.minSdkVersion, moduleInfo.minSdkVersion),
                buildToolsVersion = chooseValue(gradleModuleInfo.buildToolsVersion, moduleInfo.buildToolsVersion),
                kotlinJvmTarget = chooseValue(gradleModuleInfo.kotlinJvmTarget, moduleInfo.kotlinJvmTarget),
                kotlinFreeCompilerArgs = mergeWithBase(name, "kotlinFreeCompilerArgs", gradleModuleInfo.kotlinFreeCompilerArgs, moduleInfo.kotlinFreeCompilerArgs, mergeResult) { it },
                javaSourceCompatibility = chooseValue(gradleModuleInfo.javaSourceCompatibility, moduleInfo.javaSourceCompatibility),
                javaTargetCompatibility = chooseValue(gradleModuleInfo.javaTargetCompatibility, moduleInfo.javaTargetCompatibility),
                buildPathInfo = moduleInfo.buildPathInfo.copy(buildVariant = buildVariant), // ide project info has real buildPathInfo in jugg/classpath
                moduleDependencies = setIfEmpty(name, "moduleDependencies", moduleInfo.moduleDependencies, gradleModuleInfo.moduleDependencies, mergeResult) { it.moduleName }, // merge may cause circular dependencies, just pick the latest one
                libraryDependencies = libraryMerger.mergeLibrariesWithBase(name, moduleInfo.libraryDependencies, gradleModuleInfo.libraryDependencies, mergeResult, isNeedUpdateDependency),
                runtimeLibraryDependencies = gradleModuleInfo.runtimeLibraryDependencies,
                // below fields is only gradle has
                manifestPlaceHolders = gradleModuleInfo.manifestPlaceHolders ?: emptyMap(),
                annotationProcessorDependencies = gradleModuleInfo.annotationProcessorDependencies,
                kaptDependencies = gradleModuleInfo.kaptDependencies,
                javaAnnotationProcessorOptions = gradleModuleInfo.javaAnnotationProcessorOptions,
                kaptArguments = gradleModuleInfo.kaptArguments,
                applicationId = gradleModuleInfo.applicationId ?: moduleInfo.applicationId,
                namespace = gradleModuleInfo.namespace,
                variants = gradleModuleInfo.variants,
                signingConfigs = gradleModuleInfo.signingConfigs,
                kotlinExtensions = gradleModuleInfo.kotlinExtensions,
                kotlinPlugins = gradleModuleInfo.kotlinPlugins,
                coreLibraryDesugaring = gradleModuleInfo.coreLibraryDesugaring,
                kspDependencies = gradleModuleInfo.kspDependencies,
                instrumentationTargetPackage = gradleModuleInfo.instrumentationTargetPackage ?: moduleInfo.instrumentationTargetPackage,
                gradleModuleName = gradleModuleInfo.gradleModuleName,
            )
            mergedModules[name] = mergedModuleInfo
        }

        val missingModules = finalGradleProjectInfo.modules.keys - ideProjectInfo.modules.keys
        if (missingModules.isNotEmpty()) {
            missingModules.forEach {
                val gradleModuleInfo = finalGradleProjectInfo.modules[it] ?: return@forEach
                if (!ModulePathMergePolicy.shouldIncludeGradleOnlyModule(gradleModuleInfo, buildTarget)) {
                    logger.debug(
                        "module ${gradleModuleInfo.name} is gradle-only ${ModulePathMergePolicy.classify(gradleModuleInfo)} " +
                                "snapshot, skip for buildTarget=$buildTarget"
                    )
                    return@forEach
                }
                logger.debug("module ${gradleModuleInfo.name} not found in ide project info, add it directly")
                mergedModules[gradleModuleInfo.name] = gradleModuleInfo
            }
        }

        logger.debug("modules not found in gradleModuleInfo, won't merge: $noMergeModules")

        return mergeResult.copy(mergedInfo = JuggProjectInfo(mergedModules))
    }

    private fun <T, K> mergeWithBase(moduleName: String, type: String,
                                     base: Iterable<T>, new: Iterable<T>,
                                     mergeResult: JuggProjectInfoMergeResult,
                                     selector: (T) -> K): List<T> {
        val set = HashSet<K>()
        val list = ArrayList<T>()
        for (e in base) {
            val key = selector(e)
            set.add(key)
            list.add(e)
        }
        for (e in new) {
            val key = selector(e)
            if (set.add(key)) {
                mergeResult.addMergedItem(moduleName, type, key.toString())
                list.add(e)
            }
        }
        return list
    }

    private fun Long.timeStampToTime(): String {
        return SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(Date(this))
    }

    @Suppress("SameParameterValue")
    private fun <T, K> setIfEmpty(moduleName: String, type: String,
                                  base: List<T>, new: List<T>,
                                  mergeResult: JuggProjectInfoMergeResult,
                                  selector: (T) -> K,
                                  ): List<T> {
        if (mergeResult.isNeedUpdateDependency) {
            if (base.isEmpty() && new.isNotEmpty()) {
                new.forEach {
                    mergeResult.addMergedItem(moduleName, type, "+$it")
                }
                return new
            }

            val newKeys = new.map { selector(it) }.toSet()
            val baseKeys = base.map { selector(it) }.toSet()
            val addList = newKeys.filter { !baseKeys.contains(it) }
            addList.forEach {
                mergeResult.addMergedItem("$moduleName(just log)", type, "+$it")
            }
            val removeList = baseKeys.filter { !newKeys.contains(it) }
            removeList.forEach {
                mergeResult.addMergedItem("$moduleName(just log)", type, "-$it")
            }
            // module may read wrong module name, just use IDE.
            // e.g. "com.sickworm.LiveGroup" which the real name is "LiveGroup"
            return base
        } else {
            return base
        }
    }
}

/**
 * JuggProjectInfoMergeResult carries mergedInfo, isNeedUpdateDependency, _mergeItems, and _mergeLibraryItems.
 */
data class JuggProjectInfoMergeResult(
    val mergedInfo: JuggProjectInfo?,
    val isNeedUpdateDependency: Boolean,
    private val _mergeItems: MutableMap<String, MutableMap<String, MutableSet<String>>>,
    private val _mergeLibraryItems: MutableMap<String, MutableSet<Pair<String?, String>>>
) {

    val mergedItems: Map<String, Map<String, Set<String>>> = _mergeItems
    val mergeLibraryItems: Map<String, Set<Pair<String?, String>>> = _mergeLibraryItems
    var isFixMissingOrDelete: Boolean = false
        private set

    fun addMergedItem(moduleName: String, type: String, value: String) {
       _mergeItems.getOrPut(moduleName) { mutableMapOf() }.getOrPut(type) { mutableSetOf() }.add(value)
    }

    fun addMergeLibraryItem(moduleName: String, old: String?, new: String, isFixMissingOrDelete: Boolean = false) {
        this.isFixMissingOrDelete = this.isFixMissingOrDelete || isFixMissingOrDelete
        _mergeLibraryItems.getOrPut(moduleName) { mutableSetOf() }.add(Pair(old, new))
    }

    private fun getMergeDesc(excludeSourceDir: Boolean = true): Collection<String> {
        val result = mutableSetOf<String>()
        _mergeItems.forEach outer@{ (moduleName, items) ->
            items.forEach inner@{ (type, values) ->
                if (excludeSourceDir && type.endsWith("Dirs")) {
                    return@inner
                } else {
                    result.add("$moduleName: $type add: ${values.joinToString()}")
                }
            }
        }
        val prefix = if (isNeedUpdateDependency) "" else "(won't update)"

        val joinedLibraryResult = mutableMapOf<String, MutableList<String>>()
        _mergeLibraryItems.forEach { (moduleName, items) ->
            items.forEach { (old, new) ->
                if (old == null) {
                    joinedLibraryResult.getOrPut("add new dependency: $new") { mutableListOf() }.add(moduleName)
                } else {
                    joinedLibraryResult.getOrPut("$prefix update dependency: $old -> $new") { mutableListOf() }.add(moduleName)
                }
            }
        }
        joinedLibraryResult.forEach { (desc, modules) ->
            result.add("$desc, modules: ${modules.joinToString()}")
        }
        return result
    }

    override fun toString(): String {
        return """JuggProjectInfoMergeResult(
            |isNeedUpdateLibraryDependency=$isNeedUpdateDependency,
            |mergedItems=
            |${getMergeDesc().joinToString("\n")})
            """.trimMargin()
    }

    companion object {

        fun createEmpty(): JuggProjectInfoMergeResult {
            return JuggProjectInfoMergeResult(null, false, mutableMapOf(), mutableMapOf())
        }

        fun createSingle(juggProjectInfo: JuggProjectInfo): JuggProjectInfoMergeResult {
            return JuggProjectInfoMergeResult(juggProjectInfo, false, mutableMapOf(), mutableMapOf())
        }
    }
}
