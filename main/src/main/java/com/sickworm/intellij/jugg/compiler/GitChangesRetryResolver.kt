package com.sickworm.intellij.jugg.compiler

import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.jugg.deploy.DeployFileManager
import com.sickworm.intellij.jugg.project.change.GitFileChangesDetector

/**
 * Incremental compile retry resolver that handles "cannot find symbol / unresolved reference" errors
 * caused by source files changed outside the IDE (e.g. git pull, branch switch).
 *
 * When such errors are detected, it triggers a git-based file-change refresh. If new files are
 * discovered after the refresh (i.e. deployFileManager gains more undeployed files), the compile
 * is retried once.
 */
class GitChangesRetryResolver(
    private val gitFileChangesDetector: GitFileChangesDetector,
    private val deployFileManager: DeployFileManager,
    private val logger: Logger,
) : IIncrementalCompileRetryResolver {

    override fun resolve(compileResult: CompileResult): Boolean {
        if (compileResult.isAllSuccess) {
            return false
        }
        if (!hasSymbolNotFoundError(compileResult)) {
            logger.debug("GitChangesRetryResolver: no symbol-not-found error, skip.")
            return false
        }
        logger.debug("GitChangesRetryResolver: symbol-not-found error detected, refreshing changed files via git.")

        val filesBefore = deployFileManager.getUndeployedFiles().size
        gitFileChangesDetector.updateChangedFiles()
        val filesAfter = deployFileManager.getUndeployedFiles().size

        val hasNewFiles = filesAfter > filesBefore
        logger.debug("GitChangesRetryResolver: filesBefore=$filesBefore, filesAfter=$filesAfter, hasNewFiles=$hasNewFiles")
        if (hasNewFiles) {
            logger.info("Compile failed with symbol-not-found, git refresh found ${filesAfter - filesBefore} new file(s), retry compile once.\n")
        }
        return hasNewFiles
    }

    private fun hasSymbolNotFoundError(compileResult: CompileResult): Boolean {
        return compileResult.details.any { detail ->
            detail.isFailed && detail.getFailureOrNull()?.errors?.any { error ->
                SYMBOL_NOT_FOUND_KEYWORDS.any { keyword -> error.second.contains(keyword) }
            } == true
        }
    }

    companion object {
        private val SYMBOL_NOT_FOUND_KEYWORDS = listOf(
            "unresolved reference",       // Kotlin compiler (always English)
            "compiler.err.cant.resolve",  // javac error code (locale-independent), covers:
                                          //   compiler.err.cant.resolve
                                          //   compiler.err.cant.resolve.location
            "cannot resolve symbol",      // IDE-based compiler
        )
    }
}
