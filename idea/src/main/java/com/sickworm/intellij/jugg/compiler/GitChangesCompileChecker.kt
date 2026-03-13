package com.sickworm.intellij.jugg.compiler

import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.jugg.deploy.DeployFileManager
import com.sickworm.intellij.jugg.project.ChangedFile
import com.sickworm.intellij.jugg.project.GitFileChangesDetector
import com.sickworm.intellij.jugg.project.IBackgroundTaskRunner
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

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
        val result = FoundResult(filesAfter - filesBefore.toSet())

        logger.debug("GitChangesRetryResolver: checkUndetectedFiles: $result")
        this.lastFoundResult = result
        return result
    }

    private var lastFoundResult: FoundResult? = null
    private var checkJob: Job? = null

    fun checkUndetectedFilesAsync(compilingFiles: List<ChangedFile>) {
        checkJob = backgroundTaskRunner.runBackgroundSafe("Check Undetected Files") {
            lastFoundResult = checkUndetectedFiles(compilingFiles)
        }
    }

    fun getAsyncResultWithTimeout(timeout: Long = 10_000): FoundResult? {
        runBlocking {
            withTimeout(timeout) {
                checkJob?.join()
            }
        }
        val result = lastFoundResult
        lastFoundResult = null
        return result
    }
    data class FoundResult(
        val files: List<ChangedFile>
    ) {
        val isFoundNewChangedFiles: Boolean get() = files.isNotEmpty()
        val foundFilesSize: Int get() = files.size
    }

}
