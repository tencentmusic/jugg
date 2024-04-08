package com.sickworm.intellij.jugg.compiler

import com.sickworm.intellij.jugg.project.JuggProjectInfo

data class DependencyDiffResult(
    val currentBuildDependencies: JuggProjectInfo,
    val fullBuildDependencies: JuggProjectInfo,
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
            fullBuildDependencies: JuggProjectInfo,
        ): DependencyDiffResult {
            val fullBuildDependenciesSet: Map<String, LibraryDependencySet> = fullBuildDependencies.modules
                .flatMap { it.value.libraryDependencies }
                .distinctBy { it.file.absolutePath }
                .groupBy { it.nameWithoutPrefix }
                .mapValues { LibraryDependencySet(it.key, it.value) }

            val currentBuildDependenciesSet: Map<String, LibraryDependencySet> = currentBuildDependencies.modules
                .flatMap { it.value.libraryDependencies }
                .distinctBy { it.file.absolutePath }
                .groupBy { it.nameWithoutPrefix }
                .mapValues { LibraryDependencySet(it.key, it.value) }

            // find out the libraries that have been added
            val addedLibraries = (currentBuildDependenciesSet.keys - fullBuildDependenciesSet.keys).map {
                UpdatedLibraryDependency(currentBuildDependenciesSet[it], null)
            }.toMutableList()

            // find out the libraries that have been removed
            val removedLibraries = (fullBuildDependenciesSet.keys - currentBuildDependenciesSet.keys).map {
                UpdatedLibraryDependency(null, fullBuildDependenciesSet[it])
            }.toMutableList()

            // find out the libraries that contents have been updated
            val contentChangedLibraries = fullBuildDependenciesSet.keys.intersect(currentBuildDependenciesSet.keys)
                .filter { fullBuildDependenciesSet[it] != currentBuildDependenciesSet[it] }
                .map { UpdatedLibraryDependency(currentBuildDependenciesSet[it]!!, fullBuildDependenciesSet[it]) }

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

            val updatedLibraries = contentChangedLibraries + versionChangedLibraries

            return DependencyDiffResult(
                fullBuildDependencies = fullBuildDependencies,
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

