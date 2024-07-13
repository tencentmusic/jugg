package com.sickworm.intellij.jugg.project.dependency

import java.io.File

fun DependencyDiffResult.convertToAbsolutePath(rootDir: File): DependencyDiffResult {
    return copy(
        addedLibraries = addedLibraries.map {
            it.convertToAbsolutePath(rootDir)
        },
        removedLibraries = removedLibraries.map {
            it.convertToAbsolutePath(rootDir)
        },
        updatedLibraries = updatedLibraries.map {
            it.convertToAbsolutePath(rootDir)
        }
    )
}

private fun UpdatedLibraryDependency.convertToAbsolutePath(rootDir: File): UpdatedLibraryDependency {
    return copy(
        dependency = dependency?.convertToAbsolutePath(rootDir),
        oldDependency = oldDependency?.convertToAbsolutePath(rootDir),
    )
}

private fun LibraryDependencySet.convertToAbsolutePath(rootDir: File): LibraryDependencySet {
    return copy(libraries = libraries.map {
        it.copy(file = File(rootDir, it.file.path))
    })
}

val String.htmlModified: String get() = "<font color=\"#75A4E9\">$this</font>"
val String.htmlNew: String get() = "<font color=\"#2ECC71\">$this</font>"
val String.htmlRemoved: String get() = "<font color=\"#6F7278\">$this</font>"
val String.htmlWarning: String get() = "<font color=\"#EB984E\">$this</font>"


fun DependencyDiffResult.toHtmlChangeList(): List<String> {
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
            val updateTag = updateTag(it.isContentUpdate, it.dependency!!.version)
            result.add("${it.oldDependency!!.declaration}$updateTag")
        }
    }

    return result
}

private val NEW_TAG = "(new)".htmlNew

private val REMOVE_TAG = "(removed)".htmlRemoved

private fun updateTag(isContentUpdate: Boolean, toVersion: String?): String {
    return if (!isContentUpdate) {
        "-> $toVersion".htmlNew
    } else {
        "(content update)".htmlModified
    }
}