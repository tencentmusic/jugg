package com.sickworm.intellij.jugg.project.dependency

import com.sickworm.intellij.jugg.compiler.manifest.XmlAndroidManifestInfo
import com.sickworm.intellij.jugg.gradle.script.Utils
import com.sickworm.intellij.jugg.project.info.JuggProjectInfo
import com.sickworm.intellij.jugg.project.info.LibraryDependency


/**
 * DependencyDiffResultSet carries diffResult and diffResultWithFull.
 */
data class DependencyDiffResultSet(
    /** use to display diff */
    val diffResult: DependencyDiffResult,
    /** use to compile diff */
    val diffResultWithFull: DependencyDiffResult,
) {

    val hasChanges get() = diffResult.hasChanges

    companion object {
        fun createEmpty() = DependencyDiffResultSet(
            diffResult = DependencyDiffResult.createEmpty(),
            diffResultWithFull = DependencyDiffResult.createEmpty(),
        )
    }
}

/**
 * DependencyDiffResult carries currentBuildDependencies, lastBuildDependencies, addedLibraries, and removedLibraries.
 */
data class DependencyDiffResult(
    val currentBuildDependencies: JuggProjectInfo,
    val lastBuildDependencies: JuggProjectInfo,
    val addedLibraries: List<UpdatedLibraryDependency>,
    val removedLibraries: List<UpdatedLibraryDependency>,
    val updatedLibraries: List<UpdatedLibraryDependency>,
) {

    val newLibraryDependencies get() = (addedLibraries + updatedLibraries).flatMap {
        it.dependency!!.libraries
    }

    val oldLibraryDependencies get() = (updatedLibraries + removedLibraries).flatMap {
        it.oldDependency!!.libraries
    }

    val removedLibraryDependencies get() = removedLibraries.flatMap {
        it.oldDependency!!.libraries
    }

    val changedLibraries get() = addedLibraries + updatedLibraries + removedLibraries
    val hasChanges get() = changedLibraries.isNotEmpty()

    private fun getChangedDesc(): List<String> {
        val result = mutableListOf<String>()
        result.add("addedLibraries:")
        if (addedLibraries.isNotEmpty()) {
            addedLibraries.forEach {
                result.add(it.dependency!!.declaration)
            }
        }
        result.add("removedLibraries:")
        if (removedLibraries.isNotEmpty()) {
            removedLibraries.forEach {
                result.add(it.oldDependency!!.declaration)
            }
        }
        result.add("updatedLibraries:")
        if (updatedLibraries.isNotEmpty()) {
            updatedLibraries.forEach {
                val updateTag = if (it.isContentUpdate) {
                    "(content update)"
                } else {
                    "-> ${it.dependency!!.version}"
                }
                result.add("${it.oldDependency!!.declaration}$updateTag")
            }
        }
        
        return result
    }

    override fun toString(): String {
        return "DependencyDiffResult: " +
                "added: ${addedLibraries.size}, removed: ${removedLibraries.size}, updated: ${updatedLibraries.size}" +
                ", HtmlChangeList:\n" + getChangedDesc().joinToString("\n")
    }

    companion object {

        fun createEmpty(): DependencyDiffResult {
            return create(
                JuggProjectInfo(emptyMap(), agpR8Classpath = null),
                JuggProjectInfo(emptyMap(), agpR8Classpath = null),
            )
        }

        fun create(
            currentBuildDependencies: JuggProjectInfo,
            lastBuildDependencies: JuggProjectInfo,
            ignoreModulePaths: Set<String> = emptySet(),
        ): DependencyDiffResult {
            val lastBuildDependenciesSet: Map<String, LibraryDependencySet> = lastBuildDependencies.modules
                .filter { it.value.moduleRootDir.path !in ignoreModulePaths }
                .flatMap { it.value.libraryDependencies }
                .distinctBy { it.file.absolutePath }
                .groupBy { it.name }
                .mapValues { LibraryDependencySet(it.key, it.value) }

            val currentBuildDependenciesSet: Map<String, LibraryDependencySet> = currentBuildDependencies.modules
                .filter { it.value.moduleRootDir.path !in ignoreModulePaths }
                .flatMap { it.value.libraryDependencies }
                .distinctBy { it.file.absolutePath }
                .groupBy { it.name }
                .mapValues { LibraryDependencySet(it.key, it.value) }

            // find out the libraries that have been added
            val addedLibraries = (currentBuildDependenciesSet.keys - lastBuildDependenciesSet.keys).map {
                UpdatedLibraryDependency(currentBuildDependenciesSet[it], null)
            }.toMutableList()

            // find out the libraries that have been removed
            val removedLibraries = (lastBuildDependenciesSet.keys - currentBuildDependenciesSet.keys).map {
                UpdatedLibraryDependency(null, lastBuildDependenciesSet[it])
            }.toMutableList()

            // find out the libraries that contents have been updated by file name equals
            val contentChangedLibraries = lastBuildDependenciesSet.keys.intersect(currentBuildDependenciesSet.keys)
                .filter { lastBuildDependenciesSet[it] != currentBuildDependenciesSet[it] }
                .map { UpdatedLibraryDependency(currentBuildDependenciesSet[it]!!, lastBuildDependenciesSet[it]) }

            // find out the libraries that version have been updated
            val versionChangedLibraries = mutableListOf<UpdatedLibraryDependency>()
            addedLibraries.iterator().let { iterator ->
                while (iterator.hasNext()) {
                    val addedLibrary = iterator.next()
                    val groupAndArtifact = addedLibrary.dependency!!.groupAndArtifact
                    if (groupAndArtifact != null) {
                        val removedDependencies = lastBuildDependenciesSet.values.filter { lastDependency ->
                            lastDependency.groupAndArtifact == groupAndArtifact
                        }
                        if (removedDependencies.isNotEmpty()) {
                            // must use Utils instead of extension or will get compile error
                            val finalDependencies: LibraryDependencySet? = Utils.maxByOrNullForKt14(removedDependencies) { it.version ?: "" }
                            versionChangedLibraries.add(UpdatedLibraryDependency(
                                addedLibrary.dependency, finalDependencies
                            ))
                            iterator.remove()
                            removedLibraries.removeIf {
                                removedDependencies.contains(it.oldDependency)
                            }
                        }
                    }
                }
            }

            // find out the libraries that contents have been updated by package name in AndroidManifest.xml
            val contentAndFileNameChangedLibraries = mutableListOf<UpdatedLibraryDependency>()
            val removedLibrariesWithPackageName = removedLibraries.associateBy {
                val manifestDependency = it.oldDependency!!.libraries.find { it.isAndroidManifest }
                if (manifestDependency == null) {
                    return@associateBy ""
                }
                if (manifestDependency.file.exists()) {
                    val packageName = XmlAndroidManifestInfo.parse(manifestDependency.file).packageName
                    return@associateBy packageName
                }
                return@associateBy ""
            }
            addedLibraries.iterator().let { iterator ->
                while (iterator.hasNext()) {
                    val addedLibrary = iterator.next()
                    val manifestDependency = addedLibrary.dependency!!.libraries.find { it.isAndroidManifest }
                    if (manifestDependency != null) {
                        val packageName = if (manifestDependency.file.exists()) {
                            XmlAndroidManifestInfo.parse(manifestDependency.file).packageName
                        } else {
                            null
                        }
                        if (packageName != null) {
                            val relativeRemovedDependency = removedLibrariesWithPackageName[packageName]
                            if (relativeRemovedDependency != null) {
                                iterator.remove()
                                removedLibraries.remove(relativeRemovedDependency)
                                contentAndFileNameChangedLibraries.add(
                                    UpdatedLibraryDependency(
                                        addedLibrary.dependency,
                                        relativeRemovedDependency.oldDependency!!
                                    )
                                )
                            }
                        }
                    }
                }
            }

            val updatedLibraries = contentChangedLibraries + versionChangedLibraries + contentAndFileNameChangedLibraries

            return DependencyDiffResult(
                lastBuildDependencies = lastBuildDependencies,
                currentBuildDependencies = currentBuildDependencies,
                addedLibraries = addedLibraries,
                removedLibraries = removedLibraries,
                updatedLibraries = updatedLibraries,
            )
        }
    }
}

/**
 * UpdatedLibraryDependency carries dependency and oldDependency.
 */
data class UpdatedLibraryDependency(
    val dependency: LibraryDependencySet?,
    val oldDependency: LibraryDependencySet?,
) {

    val isContentUpdate: Boolean get() = dependency?.version == oldDependency?.version && oldDependency != null
}

/**
 * LibraryDependencySet carries declaration and libraries.
 */
data class LibraryDependencySet(
    val declaration: String,
    val libraries: List<LibraryDependency>,
) {

    val version: String? get() {
        val splits = declaration.split(':')
        if (splits.size != 3) {
            return null
        }
        val version = splits[2]
        if (version.endsWith("@aar") || version.endsWith("@jar")) {
            return version.substring(0, version.length - 4)
        }
        return version
    }

    val groupAndArtifact: String? get() {
        val splits = declaration.split(':')
        if (splits.size != 3) {
            return null
        }
        return "${splits[0]}:${splits[1]}"
    }

    override fun hashCode(): Int {
        var result = declaration.hashCode()
        result = 31 * result + libraries.hashCode()
        return result
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || javaClass != other.javaClass) return false

        val otherObj = other as LibraryDependencySet
        if (declaration != otherObj.declaration) return false
        if (libraries.size != otherObj.libraries.size) return false

        libraries.forEach { library ->
            val otherLibrary = otherObj.libraries.find { it.file.absolutePath == library.file.absolutePath }
            if (otherLibrary == null) {
                return false
            }
            if (otherLibrary.crc32 != library.crc32) {
                return false
            }
        }

        return true
    }
}
