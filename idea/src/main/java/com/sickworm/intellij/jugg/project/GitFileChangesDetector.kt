package com.sickworm.intellij.jugg.project

import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.jugg.logger.getInstance
import com.sickworm.intellij.jugg.deploy.DeployFileManager
import com.sickworm.intellij.jugg.deploy.IDeployHistoryManager
import com.sickworm.intellij.jugg.git.GitManager
import com.sickworm.intellij.jugg.git.IGitManager
import com.sickworm.intellij.jugg.project.data.ModuleInfo
import kotlinx.coroutines.*
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/**
 * File changes callbacks in IDE may miss some files if large amount of files changes outside IDE
 * e.g. git pull / git checkout {branch}
 *
 * Here we listen file events to run a delay update task to get changed files by git
 */
class GitFileChangesDetector(
    private val deployHistoryManager: IDeployHistoryManager,
    private val deployFileManager: DeployFileManager,
    private val taskRunnerManager: TaskRunnerManager,
    loggerArg: Logger,
): IFileChangesDetector {

    enum class GitRefreshResult {
        NO_HEAD_CHANGE,
        REFRESHED,
        FAILED,
    }

    private val logger = loggerArg.getInstance("GitFileChangesDetector")

    /** Map<git root dir, git manager> */
    @Volatile private var gitManagers = mapOf<String, IGitManager>()
    /** Map<git root dir, git head commit id> */
    private val gitHeads = AtomicReference<Map<String, String?>>(emptyMap())
    @Volatile private var isAvailable: Boolean = false

    private var listener: FileChangesListener? = null

    @Volatile private var checkDelayJob: Job? = null
    @Volatile private var isWaitingFileChangesEnd = false

    @Synchronized
    fun init(projectRooDir: File, modules: Map<String, ModuleInfo>) {
        val allDirectories = modules.map { it.value.moduleRootDir } + listOf(projectRooDir)
        gitManagers = getAllGits(allDirectories)
        gitHeads.set(gitManagers.mapValues { it.value.getLastCommitHash() })
        isAvailable = gitManagers.any { it.value.hasInitGit }
        logger.debug("init isAvailable: $isAvailable, gitHeads: ${gitHeads.get()}")
    }

    @Synchronized
    fun onSourceFileChanged(files: List<ChangedFile>) {
        if (!isAvailable) {
            return
        }

        if (isNeedGetChangedFilesByGit(files)) {
            if (hasGitHeadChanged()) {
                isWaitingFileChangesEnd = true
            }
        }

        // files may keep changing util git checkout finished, so we wait a while to delay update changed files
        if (isWaitingFileChangesEnd) {
            checkDelayJob?.cancel()
            checkDelayJob = taskRunnerManager.runBackgroundSafe("checkGitFileChanges", waitingFileChangesEndDuration) {
                isWaitingFileChangesEnd = false
                checkDelayJob = null
                taskRunnerManager.runTaskSafe("Checking changed files", Runnable {
                    refreshChangedFilesIfHeadChanged()
                })
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

    private fun hasGitHeadChanged(): Boolean {
        val newGitHeads = gitManagers.mapValues { it.value.getLastCommitHash() }
        return newGitHeads != gitHeads.get()
    }

    /**
     * Checks every Git root HEAD and synchronously publishes missed file changes when any HEAD moved.
     * The stored HEAD baseline advances only after the file refresh succeeds.
     */
    fun refreshChangedFilesIfHeadChanged(): GitRefreshResult {
        val checkStart = System.currentTimeMillis()
        val newGitHeads = try {
            gitManagers.mapValues { it.value.getLastCommitHash() }
        } catch (e: Exception) {
            logger.debug("Git HEAD check result=FAILED, cost=${System.currentTimeMillis() - checkStart}ms", e)
            return GitRefreshResult.NO_HEAD_CHANGE
        }
        val previousGitHeads = gitHeads.get()
        val isHeadChanged = newGitHeads != previousGitHeads
        val checkCost = System.currentTimeMillis() - checkStart
        logger.debug("Git HEAD check result=${if (isHeadChanged) "CHANGED" else "UNCHANGED"}, cost=${checkCost}ms")
        if (!isHeadChanged) {
            return GitRefreshResult.NO_HEAD_CHANGE
        }

        logger.info("Git HEAD changed, refreshing changed files before compile...")
        logger.debug("Git HEAD changed details: old=$previousGitHeads, new=$newGitHeads")
        checkDelayJob?.cancel()
        checkDelayJob = null
        isWaitingFileChangesEnd = false
        if (!updateChangedFiles(emptyList())) {
            return GitRefreshResult.FAILED
        }
        gitHeads.compareAndSet(previousGitHeads, newGitHeads)
        return GitRefreshResult.REFRESHED
    }

    fun updateChangedFiles(): Boolean {
        return updateChangedFiles(emptyList())
    }

    fun updateChangedFiles(filterFiles: List<File>): Boolean {
        logger.debug("updateChangedFiles")
        val changedFiles = deployHistoryManager.getChangedFilesSinceLastFullCompiled() ?: return false
        val filterFilesSet = filterFiles.map { it.path }.toSet()
        val allChangedFiles = changedFiles.filter { it.path !in filterFilesSet }
        val deletedFiles = collectMissingUndeployedFiles()
        logger.debug("updateChangedFiles, allChangedFiles size: ${allChangedFiles.size}, " +
                "names: ${allChangedFiles.map { it.name }}, " +
                "deletedFiles size: ${deletedFiles.size}, names: ${deletedFiles.map { it.name }}",
        )

        listener?.onFileChanges(allChangedFiles, deletedFiles)
        return true
    }

    private fun collectMissingUndeployedFiles(): List<File> {
        return deployFileManager.getUndeployedFiles()
            .map { it.file }
            .filter { !it.exists() }
            .distinctBy { it.path }
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
