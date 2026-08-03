package com.sickworm.intellij.jugg.compiler

import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.jugg.deploy.DeployFileManager
import com.sickworm.intellij.jugg.project.ChangedFile
import com.sickworm.intellij.jugg.project.GitFileChangesDetector
import com.sickworm.intellij.jugg.project.IBackgroundTaskRunner
import kotlinx.coroutines.Job

/**
 * Check if there are any new changed files by Git.
 * IDE file changes callback is not reliable when coding agent modified directly.
 */
class GitChangesCompileChecker(
    private val gitFileChangesDetector: GitFileChangesDetector,
    private val deployFileManager: DeployFileManager,
    private val backgroundTaskRunner: IBackgroundTaskRunner,
    private val logger: Logger,
) {

    fun checkUndetectedFiles(compilingFiles: List<ChangedFile>): FoundResult {
        val filesBefore = deployFileManager.getUndeployedFiles()
        gitFileChangesDetector.updateChangedFiles(compilingFiles.map { it.file })
        val filesAfter = deployFileManager.getUndeployedFiles()
        val result = FoundResult(findNewlyUncompiledFiles(filesBefore, filesAfter))

        logger.debug("GitChangesRetryResolver: checkUndetectedFiles: $result")
        return result
    }

    /**
     * Only files that still need compilation count as "new". Matches [ChangedFile.hasCompiledOnce]
     * and stale-snapshot ignore in [com.sickworm.intellij.jugg.deploy.DeployFileStateTracker].
     */
    private fun findNewlyUncompiledFiles(
        filesBefore: List<ChangedFile>,
        filesAfter: List<ChangedFile>,
    ): List<ChangedFile> {
        val uncompiledPathsBefore = filesBefore
            .filter { !it.hasCompiledOnce }
            .map { it.file.absolutePath }
            .toSet()
        return filesAfter.filter { changedFile ->
            !changedFile.hasCompiledOnce && changedFile.file.absolutePath !in uncompiledPathsBefore
        }
    }

    private var asyncCheck: AsyncCheck? = null

    fun checkUndetectedFilesAsync(compilingFiles: List<ChangedFile>) {
        val check = AsyncCheck()
        check.job = backgroundTaskRunner.runBackgroundSafe("Check Undetected Files") {
            check.result = checkUndetectedFiles(compilingFiles)
        }
        asyncCheck = check
    }

    fun getAsyncResultIfCompleted(): FoundResult? {
        val check = asyncCheck ?: return null
        asyncCheck = null
        if (!check.job.isCompleted) {
            logger.debug("Git check after compile is still running, continue without waiting.")
            return null
        }
        return check.result?.let { reconcileWithCurrentState(it) }
    }

    private class AsyncCheck {
        lateinit var job: Job
        @Volatile var result: FoundResult? = null
    }

    /**
     * Git check may finish before in-flight compile marks APT outputs as compiled.
     * Re-resolve candidate paths against current [DeployFileManager] state instead of stale [ChangedFile] snapshots.
     */
    private fun reconcileWithCurrentState(found: FoundResult): FoundResult {
        if (found.files.isEmpty()) {
            return found
        }
        val currentByPath = deployFileManager.getUndeployedFiles().associateBy { it.file.absolutePath }
        val stillNeedCompile = found.files.mapNotNull { candidate ->
            val current = currentByPath[candidate.file.absolutePath] ?: return@mapNotNull null
            if (current.hasCompiledOnce) {
                return@mapNotNull null
            }
            current
        }
        if (stillNeedCompile.size != found.files.size) {
            logger.debug(
                "GitChangesCompileChecker reconcile: candidates=${found.files.size}, stillNeedCompile=${stillNeedCompile.size}"
            )
        }
        return FoundResult(stillNeedCompile)
    }
    data class FoundResult(
        val files: List<ChangedFile>
    ) {
        val isFoundNewChangedFiles: Boolean get() = files.isNotEmpty()
        val foundFilesSize: Int get() = files.size
    }

}
