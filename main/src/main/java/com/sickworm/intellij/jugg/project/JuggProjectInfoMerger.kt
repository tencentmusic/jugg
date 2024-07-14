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

        if (!JuggSettings.isEnableReadProjectInfoFromGradle) {
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

    private fun doMerge(ideProjectInfo: JuggProjectInfo, gradleProjectInfo: JuggProjectInfo, isNeedUpdateLibraryDependency: Boolean): JuggProjectInfoMergeResult {
        val mergedModules = mutableMapOf<String, ModuleInfo>()
        val mergeResult = JuggProjectInfoMergeResult.createEmpty().copy(
            isNeedUpdateLibraryDependency = isNeedUpdateLibraryDependency
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

            // merge with different strategy
            val mergedModuleInfo = ModuleInfo(
                name = moduleInfo.name,
                moduleType = gradleModuleInfo.moduleType,
                moduleRootDir = moduleInfo.moduleRootDir,
                projectRootDir = moduleInfo.projectRootDir,
                sourceDirs = mergeWithBase(name, "sourceDirs", moduleInfo.sourceDirs, gradleModuleInfo.sourceDirs, mergeResult) { it.absolutePath },
                resourceDirs = mergeWithBase(name, "resourceDirs", moduleInfo.resourceDirs, gradleModuleInfo.resourceDirs, mergeResult) { it.absolutePath },
                assetsDirs = mergeWithBase(name, "assetsDirs", moduleInfo.assetsDirs, gradleModuleInfo.assetsDirs, mergeResult) { it.absolutePath },
                manifestFile = moduleInfo.manifestFile ?: gradleModuleInfo.manifestFile,
                manifestPlaceHolders = (moduleInfo.manifestPlaceHolders ?: emptyMap()) + (gradleModuleInfo.manifestPlaceHolders ?: emptyMap()),
                buildVariant = moduleInfo.buildVariant, // only ide can get, gradle is also read from ide project info
                compileVersion = gradleModuleInfo.compileVersion ?: moduleInfo.compileVersion,
                minSdkVersion = gradleModuleInfo.minSdkVersion ?: moduleInfo.minSdkVersion,
                buildToolsVersion = gradleModuleInfo.buildToolsVersion ?: moduleInfo.buildToolsVersion,
                kotlinJvmTarget = gradleModuleInfo.kotlinJvmTarget ?: moduleInfo.kotlinJvmTarget,
                kotlinFreeCompilerArgs = mergeWithBase(name, "kotlinFreeCompilerArgs", gradleModuleInfo.kotlinFreeCompilerArgs, moduleInfo.kotlinFreeCompilerArgs, mergeResult) { it },
                javaSourceCompatibility = gradleModuleInfo.javaSourceCompatibility ?: moduleInfo.javaSourceCompatibility,
                javaTargetCompatibility = gradleModuleInfo.javaTargetCompatibility ?: moduleInfo.javaTargetCompatibility,
                buildPathInfo = moduleInfo.buildPathInfo, // ide project info has real buildPathInfo in jugg/classpath
                moduleDependencies = mergeWithBase(name, "moduleDependencies", moduleInfo.moduleDependencies, gradleModuleInfo.moduleDependencies, mergeResult) { it.moduleName },
                libraryDependencies = mergeLibrariesWithBase(name, moduleInfo.libraryDependencies, gradleModuleInfo.libraryDependencies, mergeResult, isNeedUpdateLibraryDependency),
                runtimeLibraryDependencies = gradleModuleInfo.runtimeLibraryDependencies, // only gradle has
                annotationProcessorDependencies = gradleModuleInfo.annotationProcessorDependencies, // only gradle has
                kaptDependencies = gradleModuleInfo.kaptDependencies, // only gradle has
                javaAnnotationProcessorOptions = gradleModuleInfo.javaAnnotationProcessorOptions, // only gradle has
                kaptArguments = gradleModuleInfo.kaptArguments, // only gradle has
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
                    // version changed, update if needed
                    if (isNeedUpdateLibraryDependency) {
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
    val isNeedUpdateLibraryDependency: Boolean,
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
        val prefix = if (isNeedUpdateLibraryDependency) "" else "(won't update)"

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
            |isNeedUpdateLibraryDependency=$isNeedUpdateLibraryDependency,
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