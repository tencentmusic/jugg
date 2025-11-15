package com.sickworm.intellij.jugg.compile

import com.sickworm.intellij.jugg.mock.AssembleAndroidProjectOnce

class LibraryParser {

    fun loadInTest(): List<String> {
        return AssembleAndroidProjectOnce.getProjectInfo().modules.values.flatMap { module ->
            module.libraryDependencies
                .filter { it.isJar }
                .map { it.file.path }
        }
    }
}