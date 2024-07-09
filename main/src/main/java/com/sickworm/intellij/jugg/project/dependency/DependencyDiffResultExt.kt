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