package com.sickworm.intellij.jugg.compiler

import com.sickworm.intellij.jugg.ide.bean.JuggSettings
import com.sickworm.intellij.jugg.project.ChangedFile

/**
 * User-visible counts for a too-many-changes fallback decision.
 * Module count uses the same module-and-language key as the internal threshold.
 */
data class TooManyChangesInfo(
    val javaFileCount: Int,
    val kotlinFileCount: Int,
    val moduleCount: Int,
)

object TooManyChanges {

    fun evaluate(files: List<ChangedFile>): TooManyChangesInfo? {
        val sourceFiles = files.filter {
            it.type == CompileFile.Type.Java || it.type == CompileFile.Type.Kotlin
        }
        val javaFileCount = sourceFiles.count { it.type == CompileFile.Type.Java }
        val kotlinFileCount = sourceFiles.count { it.type == CompileFile.Type.Kotlin }
        val moduleCount = sourceFiles.map { it.module.name + "_" + it.type }.toSet().size
        val filePoints = javaFileCount * 2 + kotlinFileCount * 3
        if (moduleCount <= JuggSettings.maxCompileSourceModules &&
            filePoints <= JuggSettings.maxCompileSourceFilePoints
        ) {
            return null
        }
        return TooManyChangesInfo(
            javaFileCount = javaFileCount,
            kotlinFileCount = kotlinFileCount,
            moduleCount = moduleCount,
        )
    }
}
