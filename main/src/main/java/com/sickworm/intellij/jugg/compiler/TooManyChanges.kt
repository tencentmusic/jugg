package com.sickworm.intellij.jugg.compiler

import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.jugg.compiler.ui.TooManyChangesConfirmResult
import com.sickworm.intellij.jugg.ide.bean.JuggSettings
import com.sickworm.intellij.jugg.project.ChangedFile

/**
 * User-visible counts for a too-many-changes fallback decision.
 * [moduleCount] is unique modules; the internal threshold still uses module-and-language keys.
 */
data class TooManyChangesInfo(
    val javaFileCount: Int,
    val kotlinFileCount: Int,
    val moduleCount: Int,
    val exceededByModules: Boolean = false,
)

object TooManyChanges {

    fun evaluate(files: List<ChangedFile>): TooManyChangesInfo? {
        val sourceFiles = files.filter {
            it.type == CompileFile.Type.Java || it.type == CompileFile.Type.Kotlin
        }
        val javaFileCount = sourceFiles.count { it.type == CompileFile.Type.Java }
        val kotlinFileCount = sourceFiles.count { it.type == CompileFile.Type.Kotlin }
        val moduleCount = sourceFiles.map { it.module.name }.toSet().size
        val moduleLanguageCount = sourceFiles.map { it.module.name + "_" + it.type }.toSet().size
        val filePoints = javaFileCount * 2 + kotlinFileCount * 3
        val exceededByModules = moduleLanguageCount > JuggSettings.maxCompileSourceModules
        if (!exceededByModules && filePoints <= JuggSettings.maxCompileSourceFilePoints) {
            return null
        }
        return TooManyChangesInfo(
            javaFileCount = javaFileCount,
            kotlinFileCount = kotlinFileCount,
            moduleCount = moduleCount,
            exceededByModules = exceededByModules,
        )
    }

    fun applyUserChoice(
        info: TooManyChangesInfo,
        confirm: TooManyChangesConfirmResult,
        logger: Logger,
        logFallback: Boolean = true,
        onContinue: () -> Unit,
        onCancel: () -> Unit,
    ): CompileTaskResult? {
        return when (confirm) {
            TooManyChangesConfirmResult.CONTINUE -> {
                onContinue()
                null
            }
            TooManyChangesConfirmResult.CANCEL -> {
                onCancel()
                CompileTaskResult.incrementalFailed(false, "Compile canceled")
            }
            TooManyChangesConfirmResult.FALLBACK -> {
                logFallbackIfNeeded(info, logger, logFallback)
                CompileTaskResult.incrementalFailed(true, "Too many changes")
            }
        }
    }

    private fun logFallbackIfNeeded(info: TooManyChangesInfo, logger: Logger, logFallback: Boolean) {
        if (!logFallback) {
            return
        }
        if (info.exceededByModules) {
            logger.warn("Compile modules too much(${info.moduleCount} modules), " +
                    "will fallback to gradle compile for better performance.")
        } else {
            logger.warn("Compile files too much(${info.javaFileCount + info.kotlinFileCount} files), " +
                    "will fallback to gradle compile for better performance.")
        }
    }
}
