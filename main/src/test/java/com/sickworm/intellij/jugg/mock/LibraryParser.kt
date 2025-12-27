package com.sickworm.intellij.jugg.mock

class LibraryParser {

    fun loadInTest(): List<String> {
        return AssembleAndroidProjectOnce.getProjectInfo().modules.values.flatMap { module ->
            module.libraryDependencies
                .filter { it.isJar }
                .map { it.file.path }
        }
    }
}