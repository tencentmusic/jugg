package com.sickworm.intellij.jugg.compiler

import com.sickworm.intellij.jugg.compiler.manifest.XmlAndroidManifestInfo
import com.sickworm.intellij.jugg.project.data.LibraryDependency
import com.sickworm.intellij.jugg.project.data.JuggProjectInfo

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

    val removedLibraryDependencies get() = removedLibraries.flatMap {
        it.oldDependency!!.libraries
    }

    fun toHtmlChangeList(): List<String> {
        val result = mutableListOf<String>()
        if (addedLibraries.isNotEmpty()) {
            addedLibraries.forEach {
                result.add("${it.dependency!!.declaration}$NEW_TAG")
            }
        }
        if (removedLibraries.isNotEmpty()) {
            removedLibraries.forEach {
                result.add("${it.oldDependency!!.declaration}$REMOVE_TAG")
            }
        }
        if (updatedLibraries.isNotEmpty()) {
            updatedLibraries.forEach {
                val updateTag = updateTag(it.oldDependency!!.version, it.dependency!!.version)
                result.add("${it.dependency.declaration}$updateTag")
            }
        }
        
        return result
    }

    override fun toString(): String {
        return "DependencyDiffResult: " +
                "added: ${addedLibraries.size}, removed: ${removedLibraries.size}, updated: ${updatedLibraries.size}" +
                ", HtmlChangeList:\n" + toHtmlChangeList().joinToString("\n")
    }

    companion object {

        private const val NEW_TAG = "<font color=\"#2ECC71\">(new)</font>"

        private const val REMOVE_TAG = "<font color=\"#EB984E\">(removed)</font>"

        private fun updateTag(fromVersion: String?, toVersion: String?): String {
            val desc = if (fromVersion != null && toVersion != null) {
                "$fromVersion -> $toVersion"
            } else {
                "content update"
            }
            return "<font color=\"#2ECC71\">($desc)</font>"
        }

        fun createEmpty(): DependencyDiffResult {
            return create(JuggProjectInfo(emptyMap()), JuggProjectInfo(emptyMap()))
        }

        fun create(
            currentBuildDependencies: JuggProjectInfo,
            lastBuildDependencies: JuggProjectInfo,
        ): DependencyDiffResult {
            val lastBuildDependenciesSet: Map<String, LibraryDependencySet> = lastBuildDependencies.modules
                .flatMap { it.value.libraryDependencies }
                .distinctBy { it.file.absolutePath }
                .groupBy { it.name }
                .mapValues { LibraryDependencySet(it.key, it.value) }

            val currentBuildDependenciesSet: Map<String, LibraryDependencySet> = currentBuildDependencies.modules
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
                        val removedDependencies = removedLibraries.filter { removedLibrary ->
                            removedLibrary.oldDependency!!.groupAndArtifact == groupAndArtifact
                        }
                        if (removedDependencies.isNotEmpty()) {
                            removedDependencies.forEach { removedDependency ->
                                versionChangedLibraries.add(UpdatedLibraryDependency(
                                    addedLibrary.dependency,
                                    removedDependency.oldDependency!!
                                ))
                            }
                            iterator.remove()
                            removedLibraries.removeAll(removedDependencies)
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
                val packageName = XmlAndroidManifestInfo.parse(manifestDependency.file).packageName
                return@associateBy packageName
            }
            addedLibraries.iterator().let { iterator ->
                while (iterator.hasNext()) {
                    val addedLibrary = iterator.next()
                    val manifestDependency = addedLibrary.dependency!!.libraries.find { it.isAndroidManifest }
                    if (manifestDependency != null) {
                        val packageName = XmlAndroidManifestInfo.parse(manifestDependency.file).packageName
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

data class UpdatedLibraryDependency(
    val dependency: LibraryDependencySet?,
    val oldDependency: LibraryDependencySet?,
)

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

