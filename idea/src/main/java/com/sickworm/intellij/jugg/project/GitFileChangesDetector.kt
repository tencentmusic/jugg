package com.sickworm.intellij.jugg.project

import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.jugg.logger.getInstance
import com.sickworm.intellij.jugg.deploy.IDeployHistoryManager
import com.sickworm.intellij.jugg.git.GitManager
import com.sickworm.intellij.jugg.git.IGitManager
import com.sickworm.intellij.jugg.platform.PlatformApi
import com.sickworm.intellij.jugg.project.data.ModuleInfo
import kotlinx.coroutines.*
import java.io.File

/**
 * File changes callbacks in IDE may miss some files if large amount of files changes outside IDE
 * e.g. git pull / git checkout {branch}
 *
 * Here we listen file events to run a delay update task to get changed files by git
 */
class GitFileChangesDetector(
    private val deployHistoryManager: IDeployHistoryManager,
    private val taskRunnerManager: TaskRunnerManager,
    loggerArg: Logger,
): IFileChangesDetector {

    private val logger = loggerArg.getInstance("GitFileChangesDetector")

    /** Map<git root dir, git manager> */
    private var gitManagers = mapOf<String, IGitManager>()
    /** Map<git root dir, git head commit id> */
    private var gitHeads = mapOf<String, String?>()
    private var isAvailable: Boolean = false

    private var listener: FileChangesListener? = null

    private var checkDelayJob: Job? = null
    private var isWaitingFileChangesEnd = false

    @Synchronized
    fun init(projectRooDir: File, modules: Map<String, ModuleInfo>) {
        val allDirectories = modules.map { it.value.moduleRootDir } + listOf(projectRooDir)
        gitManagers = getAllGits(allDirectories)
        gitHeads = gitManagers.mapValues { it.value.getLastCommitHash() }
        isAvailable = gitManagers.any { it.value.hasInitGit }
        logger.debug("init isAvailable: $isAvailable, gitHeads: $gitHeads")
    }

    @Synchronized
    fun onSourceFileChanged(files: List<ChangedFile>) {
        if (!isAvailable) {
            return
        }

        if (isNeedGetChangedFilesByGit(files)) {
            if (isGitHeadsUpdate()) {
                isWaitingFileChangesEnd = true
            }
        }

        // files may keep changing util git checkout finished, so we wait a while to delay update changed files
        if (isWaitingFileChangesEnd) {
            checkDelayJob?.cancel()
            checkDelayJob = taskRunnerManager.runBackgroundSafe("checkGitFileChanges", waitingFileChangesEndDuration) {
                isWaitingFileChangesEnd = false
                taskRunnerManager.runTaskSafe("Checking changed files", ::updateChangedFiles)
            }
        }
    }

    private val detectDuration = 1_000L
    private val waitingFileChangesEndDuration = 1_000L
    private val triggerFileSize = 2
    private var fileChangesRecord = mutableMapOf<Long, List<ChangedFile>>()

    private fun isNeedGetChangedFilesByGit(files: List<ChangedFile>): Boolean {
        val currentTime = System.currentTimeMillis()
        fileChangesRecord[currentTime] = files
        fileChangesRecord = fileChangesRecord
            .filterKeys { it > currentTime - detectDuration }
            .toMutableMap()

        val totalChangedSize = fileChangesRecord.values.sumOf { it.size }
        if (totalChangedSize >= triggerFileSize) {
            logger.debug("isNeedGetChangedFilesByGit=true, totalChangedSize: $totalChangedSize")
            return true
        }
        return false
    }

    private fun isGitHeadsUpdate(): Boolean {
        val newGitHeads = gitManagers.mapValues { it.value.getLastCommitHash() }
        if (newGitHeads != gitHeads) {
            logger.debug("isGitHeadsUpdate=true, oldGitHeads: $gitHeads, newGitHeads: $newGitHeads")
            gitHeads = newGitHeads
            return true
        }
        return false
    }

    fun updateChangedFiles() {
        logger.debug("updateChangedFiles")
        val recoverData = deployHistoryManager.tryGetContextRecoverInfoFromDb(isOnInit = false)
        val allChangedFiles = recoverData?.changedFiles ?: emptyList()
        logger.debug("updateChangedFiles, allChangedFiles size: ${allChangedFiles.size}, names: ${allChangedFiles.map { it.name }}")

        listener?.onFileChanges(allChangedFiles, emptyList())
    }

    override fun startListen(listener: FileChangesListener) {
        this.listener = listener
    }

    private fun getAllGits(dirs: List<File>): Map<String, IGitManager> {
        val gitManagerMap = mutableMapOf<String, IGitManager>()
        dirs.forEach {
            val subModuleGitManager = GitManager.createGitManagerAndTrySearchParent(it)
            if (!subModuleGitManager.hasInitGit) {
                return@forEach
            }
            if (subModuleGitManager.rootDir.path !in gitManagerMap.keys) {
                gitManagerMap[subModuleGitManager.rootDir.path] = subModuleGitManager
            }
        }
        return gitManagerMap
    }
}
