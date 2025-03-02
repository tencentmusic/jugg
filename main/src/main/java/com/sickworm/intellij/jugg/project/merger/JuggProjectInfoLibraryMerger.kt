package com.sickworm.intellij.jugg.project.merger

import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.jugg.project.data.LibraryDependency
import java.util.*

class JuggProjectInfoLibraryMerger(private val logger: Logger) {

    init {
        LibraryMergeWrapper.clearCache()
    }

    fun mergeLibrariesWithBase(
        moduleName: String,
        base: List<LibraryDependency>, new: List<LibraryDependency>,
        mergeResult: JuggProjectInfoMergeResult,
        isNeedUpdateLibraryDependency: Boolean
    ): List<LibraryDependency> {
        val baseMap: Map<String, List<LibraryMergeWrapper>> = base
            .map { LibraryMergeWrapper.get(it) }
            .groupBy { it.nameWithoutVersion }
        val newMap: Map<String, List<LibraryMergeWrapper>> = new
            .map { LibraryMergeWrapper.get(it) }
            .groupBy { it.nameWithoutVersion }
        val result: MutableList<LibraryDependency> by lazy { base.toMutableList() }

        var hasUpdate = false
        newMap.forEach root@{ (newNameWithoutVersion, newDepItems) ->
            val baseDepItems = baseMap[newNameWithoutVersion]
            if (baseDepItems.isNullOrEmpty()) {
                // not exists, add directly
                newDepItems.forEach {
                    result.add(it.library)
                    mergeResult.addMergeLibraryItem(moduleName, null, it.name)
                }
                hasUpdate = true
                return@root
            }

            // update if needed:
            val baseName = baseDepItems.first().name
            val newName = newDepItems.first().name
            val isVersionChanged = baseName != newName
            val isSingleJar = newDepItems.count { it.type == "jar" } <= 1
            newDepItems.forEach { newDep ->
                val baseDep = baseDepItems.find {
                    it.getMergeName(isSingleJar) == newDep.getMergeName(isSingleJar)
                }

                if (!newDep.isExists) {
                    logger.debug("new dep $newDep not exist, won't update, base dep: $baseDep")
                    return@forEach
                }

                // condition 1. version changed + new dep exist + need update
                if (isVersionChanged) {
                    if (baseDep?.isExists == true) {
                        // sometimes gradle project info will get a different name but same file as ide project info
                        if ((baseDep.file.path == newDep.file.path) || (baseDep.crc32 == newDep.crc32)) {
                            // same file, ignore it
                            return@forEach
                        }
                    }
                    // remove all old version files and add news
                    if (isNeedUpdateLibraryDependency) {
                        result.removeIf { it.name == baseName }
                        result.add(newDep.library)
                        hasUpdate = true
                    }
                    val suffix = if (!isNeedUpdateLibraryDependency) " (not update)" else ""
                    mergeResult.addMergeLibraryItem(moduleName, baseDep?.name, (newDep.name + suffix))
                } else if (baseDep != null) {
                    // condition 2. version not changed
                    // but: base dep not exist + new dep exist
                    if (!baseDep.isExists) {
                        // replace old deleted file and add new one
                        result.removeIf { it == baseDep.library }
                        result.add(newDep.library)
                        hasUpdate = true

                        val suffix = " (fix deleted with ${newDep.file.path})"
                        mergeResult.addMergeLibraryItem(moduleName, baseDep.name, (newDep.name + suffix))
                    }
                } else {
                    // condition 3. version not changed + base dep missing
                    result.add(newDep.library)
                    hasUpdate = true

                    val suffix = " (fix missing with ${newDep.file.path})"
                    mergeResult.addMergeLibraryItem(moduleName, null, (newDep.name + suffix))
                }
            }
        }

        return if (!hasUpdate) {
            base
        } else {
            result
        }
    }
}

private data class LibraryMergeWrapper(
    /**
     * Library dependency info
     */
    val library: LibraryDependency,
    /**
     * Name to identify the same dependency
     */
    val nameWithoutVersion: String,
    /**
     * Whether the library file exists in disk
     */
    val isExists: Boolean,
) {

    val name get() = library.name
    val crc32 get() = library.crc32
    val file get() = library.file
    val type get() = library.type

    /**
     * Name to identify the same file
     */
    fun getMergeName(isSingleJar: Boolean): String {
        if (isSingleJar || type != "jar") {
            return type
        }
        // type is jar and its multiple jars
        return if (file.parentFile.name == "jars") {
            // e.g. classes.jar in aar
            file.name
        } else {
            // e.g. libs/micro_annotation.jar in aar
            "${file.parentFile.name}/${file.name}"
        }
    }

    companion object {

        private val libraryMergeCache = mutableMapOf<String, LibraryMergeWrapper>()

        fun clearCache() {
            libraryMergeCache.clear()
        }

        fun get(libraryDependency: LibraryDependency): LibraryMergeWrapper {
            return libraryMergeCache.getOrPut(libraryDependency.file.path) {
                LibraryMergeWrapper(
                    libraryDependency,
                    getNameWithoutVersion(libraryDependency.name),
                    libraryDependency.file.exists()
                )
            }
        }

        private fun getNameWithoutVersion(name: String): String {
            val colonCount = name.count { it == ':' }
            return if (colonCount == 2) {
                name.substringBeforeLast(':')
            } else {
                name
            }
        }
    }
}