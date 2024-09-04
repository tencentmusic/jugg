package com.sickworm.intellij.jugg.project

import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.jugg.ide.JuggSettings
import com.sickworm.intellij.jugg.logger.TimeLogger
import com.sickworm.intellij.jugg.logger.getInstance
import com.sickworm.intellij.jugg.project.data.JuggProjectInfo
import com.sickworm.intellij.jugg.project.data.LibraryDependency
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
 */
interface IJuggProjectInfoMerger {

    val juggProjectInfo: JuggProjectInfo?

    /** read from project info */
    fun afterSync(projectInfoSerialize: ProjectInfoSerializer): JuggProjectInfoMergeResult

    /**
     * e.g. ./gradlew :app:assembleDebug -I readProjectInfo.gradle.kts
     * currently all goes by [afterLocalFetch]
     */
    fun afterBuild(projectInfoSerialize: ProjectInfoSerializer, isRemote: Boolean): JuggProjectInfoMergeResult

    /**
     * e.g. ./gradlew --dry-run -I readProjectInfo.gradle.kts
     */
    fun afterLocalFetch(projectInfoSerialize: ProjectInfoSerializer): JuggProjectInfoMergeResult
}

class JuggProjectInfoMerger(loggerArg: Logger): IJuggProjectInfoMerger {

    private val logger = loggerArg.getInstance("JuggProjectInfoMerger")

    private var ide: ProjectInfoSerializer? = null
    private var build: ProjectInfoSerializer? = null
    private var localFetch: ProjectInfoSerializer? = null

    override var juggProjectInfo: JuggProjectInfo? = null

    override fun afterSync(projectInfoSerialize: ProjectInfoSerializer): JuggProjectInfoMergeResult {
        ide = projectInfoSerialize
        val result = merge()
        juggProjectInfo = result.mergedInfo
        return result
    }

    override fun afterBuild(projectInfoSerialize: ProjectInfoSerializer, isRemote: Boolean): JuggProjectInfoMergeResult {
        // currently we don't use ProjectInfoSerializer from remote build,
        // it has different file path, so we can't handle it for now
        throw IllegalStateException("no need to implement afterBuild")
    }

    override fun afterLocalFetch(projectInfoSerialize: ProjectInfoSerializer): JuggProjectInfoMergeResult {
        localFetch = projectInfoSerialize
        val result = merge()
        juggProjectInfo = result.mergedInfo
        return result
    }

    private fun merge(): JuggProjectInfoMergeResult {
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

        val buildUpdateTime = build?.dataFile?.lastModified() ?: -1L
        val localFetchUpdateTime = localFetch?.dataFile?.lastModified() ?: -1L
        logger.debug("buildUpdateTime ${buildUpdateTime.timeStampToTime()}, " +
                "localFetchUpdateTime ${localFetchUpdateTime.timeStampToTime()}")
        val gradle = if (buildUpdateTime > localFetchUpdateTime) {
            logger.debug("use build as gradle project info")
            build
        } else {
            logger.debug("use local fetch as gradle project info")
            localFetch
        }
        val gradleProjectInfo = gradle?.load()
        if (gradleProjectInfo == null) {
            logger.debug("gradleProjectInfo is null, exit merge and use ideProjectInfo directly")
            return JuggProjectInfoMergeResult.createSingle(ideProjectInfo)
        }

        var isNeedUpdateLibraryDependency = true
        val ideUpdateTime = ide?.dataFile?.lastModified() ?: -1L
        val gradleUpdateTime = gradle.dataFile.lastModified()
        if (ideUpdateTime > gradleUpdateTime) {
            logger.debug("ide project info is newer than gradle project info, isNeedUpdateLibraryDependency=false")
            isNeedUpdateLibraryDependency = false
        } else {
            logger.debug("ide project info is older than gradle project info, isNeedUpdateLibraryDependency=true")
        }

        val result = doMerge(ideProjectInfo, gradleProjectInfo, isNeedUpdateLibraryDependency)
        logger.debug("merge result: $result")

        TimeLogger.end("merge", logger)
        return result
    }

    private fun doMerge(ideProjectInfo: JuggProjectInfo, gradleProjectInfo: JuggProjectInfo, isNeedUpdateDependency: Boolean): JuggProjectInfoMergeResult {
        val mergedModules = mutableMapOf<String, ModuleInfo>()
        val mergeResult = JuggProjectInfoMergeResult.createEmpty().copy(
            isNeedUpdateDependency = isNeedUpdateDependency
        )

        // sometimes, module in ide will have a wired name e.g. library1.MyApplication.library1.main
        // then Jugg will wrongly parse the module name as MyApplication.library1 (activity it's library1)
        // here we update name in gradle project info to match ide
        val idePathNameMap = ideProjectInfo.modules.map { it.value.moduleRootDir.absolutePath to it.value.name }.toMap()
        val nameUpdateMap = mutableMapOf<String, String>()
        val updateModules = gradleProjectInfo.modules.toMutableMap()
        gradleProjectInfo.modules.values.forEach {
            val path = it.moduleRootDir.absolutePath
            val ideModuleName = idePathNameMap[path]
            val gradleModuleName = it.name
            if (ideModuleName != null && ideModuleName != gradleModuleName) {
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

        ideProjectInfo.modules.forEach { (name, moduleInfo) ->
            val gradleModuleInfo = finalGradleProjectInfo.modules[name]
            if (gradleModuleInfo == null) {
                logger.debug("module $name not found in gradleModuleInfo, won't merge")
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
            val mergedModuleInfo = ModuleInfo(
                name = moduleInfo.name,
                moduleType = gradleModuleInfo.moduleType,
                moduleRootDir = moduleInfo.moduleRootDir,
                projectRootDir = moduleInfo.projectRootDir,
                sourceDirs = mergeWithBase(name, "sourceDirs", moduleInfo.sourceDirs, gradleModuleInfo.sourceDirs, mergeResult) { it.absolutePath },
                resourceDirs = mergeWithBase(name, "resourceDirs", moduleInfo.resourceDirs, gradleModuleInfo.resourceDirs, mergeResult) { it.absolutePath },
                assetsDirs = mergeWithBase(name, "assetsDirs", moduleInfo.assetsDirs, gradleModuleInfo.assetsDirs, mergeResult) { it.absolutePath },
                manifestFile = chooseValue(gradleModuleInfo.manifestFile, moduleInfo.manifestFile),
                buildVariant = moduleInfo.buildVariant, // only ide can get, gradle is also read from ide project info
                compileVersion = chooseValue(gradleModuleInfo.compileVersion, moduleInfo.compileVersion),
                minSdkVersion = chooseValue(gradleModuleInfo.minSdkVersion, moduleInfo.minSdkVersion),
                buildToolsVersion = chooseValue(gradleModuleInfo.buildToolsVersion, moduleInfo.buildToolsVersion),
                kotlinJvmTarget = chooseValue(gradleModuleInfo.kotlinJvmTarget, moduleInfo.kotlinJvmTarget),
                kotlinFreeCompilerArgs = mergeWithBase(name, "kotlinFreeCompilerArgs", gradleModuleInfo.kotlinFreeCompilerArgs, moduleInfo.kotlinFreeCompilerArgs, mergeResult) { it },
                javaSourceCompatibility = chooseValue(gradleModuleInfo.javaSourceCompatibility, moduleInfo.javaSourceCompatibility),
                javaTargetCompatibility = chooseValue(gradleModuleInfo.javaTargetCompatibility, moduleInfo.javaTargetCompatibility),
                buildPathInfo = moduleInfo.buildPathInfo, // ide project info has real buildPathInfo in jugg/classpath
                moduleDependencies = pickLatest(name, "moduleDependencies", moduleInfo.moduleDependencies, gradleModuleInfo.moduleDependencies, mergeResult) { it.moduleName }, // merge may cause circular dependencies, just pick the latest one
                libraryDependencies = mergeLibrariesWithBase(name, moduleInfo.libraryDependencies, gradleModuleInfo.libraryDependencies, mergeResult, isNeedUpdateDependency),
                runtimeLibraryDependencies = gradleModuleInfo.runtimeLibraryDependencies,
                // below fields is only gradle has
                manifestPlaceHolders = gradleModuleInfo.manifestPlaceHolders ?: emptyMap(),
                annotationProcessorDependencies = gradleModuleInfo.annotationProcessorDependencies,
                kaptDependencies = gradleModuleInfo.kaptDependencies,
                javaAnnotationProcessorOptions = gradleModuleInfo.javaAnnotationProcessorOptions,
                kaptArguments = gradleModuleInfo.kaptArguments,
                applicationId = gradleModuleInfo.applicationId,
                namespace = gradleModuleInfo.namespace,
                variants = gradleModuleInfo.variants,
                signingConfigs = gradleModuleInfo.signingConfigs,
            )
            mergedModules[name] = mergedModuleInfo
        }

        val missingModules = finalGradleProjectInfo.modules.keys - ideProjectInfo.modules.keys
        if (missingModules.isNotEmpty()) {
            missingModules.forEach {
                val gradleModuleInfo = finalGradleProjectInfo.modules[it] ?: return@forEach
                logger.debug("module ${gradleModuleInfo.name} not found in ide project info, add it directly")
                mergedModules[gradleModuleInfo.name] = gradleModuleInfo
            }
        }

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

    @Suppress("SameParameterValue")
    private fun <T, K> pickLatest(moduleName: String, type: String,
                                  base: List<T>, new: List<T>,
                                  mergeResult: JuggProjectInfoMergeResult,
                                  selector: (T) -> K,
                                  ): List<T> {
        if (mergeResult.isNeedUpdateDependency) {
            val newKeys = new.map { selector(it) }.toSet()
            val baseKeys = base.map { selector(it) }.toSet()
            val addList = newKeys.filter { !baseKeys.contains(it) }
            addList.forEach {
                mergeResult.addMergedItem(moduleName, type, "+$it")
            }
            val removeList = baseKeys.filter { !newKeys.contains(it) }
            removeList.forEach {
                mergeResult.addMergedItem(moduleName, type, "-$it")
            }
            return new
        } else {
            return base
        }
    }

    private fun mergeLibrariesWithBase(moduleName: String,
                                       base: List<LibraryDependency>, new: List<LibraryDependency>,
                                       mergeResult: JuggProjectInfoMergeResult,
                                       isNeedUpdateLibraryDependency: Boolean): List<LibraryDependency> {
        // map<name without version, name>
        val nameMap = mutableMapOf<String, String>()
        base.forEach {
            nameMap[getNameWithoutVersion(it.name)] = it.name
        }
        val result: MutableList<LibraryDependency> by lazy { base.toMutableList() }

        var hasUpdate = false
        new.forEach { newDep ->
            val newNameWithoutVersion = getNameWithoutVersion(newDep.name)
            val baseDepName = nameMap[newNameWithoutVersion]
            if (baseDepName == null) {
                // not exists, add directly
                hasUpdate = true
                result.add(newDep)
                mergeResult.addMergeLibraryItem(moduleName, null, newDep.name)
            } else {
                if (newDep.name != baseDepName) {
                    // sometimes gradle project info will get a different name but same file as ide project info
                    val relativeLibraryDependencies = base.find {
                        it.name == baseDepName && it.type == newDep.type
                    }
                    if (relativeLibraryDependencies?.crc32 == newDep.crc32) {
                        // same file, ignore it
                        return@forEach
                    }

                    // version changed, update if needed
                    if (isNeedUpdateLibraryDependency) {
                        // remove all old version files
                        result.iterator().also { iterator ->
                            while (iterator.hasNext()) {
                                val baseDep = iterator.next()
                                if (baseDep.name == baseDepName) {
                                    iterator.remove()
                                }
                            }
                        }
                        hasUpdate = true
                        result.add(newDep)
                    }
                    // always record it
                    mergeResult.addMergeLibraryItem(moduleName, baseDepName, newDep.name)
                }
            }
        }

        return if (!hasUpdate) {
            base
        } else {
            result
        }
    }

    private var nameWithoutVersionCache = mutableMapOf<String, String>()

    private fun getNameWithoutVersion(name: String): String {
        return nameWithoutVersionCache.getOrPut(name) {
            val colonCount = name.count { it == ':' }
            if (colonCount == 2) {
                name.substringBeforeLast(':')
            } else {
                name
            }
        }
    }

    private fun Long.timeStampToTime(): String {
        return SimpleDateFormat("MM-dd HH:mm:ss.SSS").format(Date(this))
    }
}

data class JuggProjectInfoMergeResult(
    val mergedInfo: JuggProjectInfo?,
    val isNeedUpdateDependency: Boolean,
    private val _mergeItems: MutableMap<String, MutableMap<String, MutableSet<String>>>,
    private val _mergeLibraryItems: MutableMap<String, MutableSet<Pair<String?, String>>>
) {

    val mergedItems: Map<String, Map<String, Set<String>>> = _mergeItems
    val mergeLibraryItems: Map<String, Set<Pair<String?, String>>> = _mergeLibraryItems

    fun addMergedItem(moduleName: String, type: String, value: String) {
       _mergeItems.getOrPut(moduleName) { mutableMapOf() }.getOrPut(type) { mutableSetOf() }.add(value)
    }

    fun addMergeLibraryItem(moduleName: String, old: String?, new: String) {
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