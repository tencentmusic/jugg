package com.sickworm.intellij.jugg.project.change

import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.jugg.compiler.CompileFile
import com.sickworm.intellij.jugg.deploy.DeployFileManager
import com.sickworm.intellij.jugg.deploy.IDeployStateManager
import com.sickworm.intellij.jugg.project.dependency.IDependencyChangeManager
import com.sickworm.intellij.jugg.project.info.ModuleInfo
import com.sickworm.intellij.jugg.project.runtime.TaskRunnerManager
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/** Coordinates monitor events, file filtering, deploy state, dependency state, and Git reconciliation. */
class FileChangeManager(
    private val fileChangesHandler: IFileChangesHandler,
    private val deployFileManager: DeployFileManager,
    private val dependencyChangeManager: IDependencyChangeManager,
    private val gitFileChangesDetector: GitFileChangesDetector,
    private val deployStateManager: IDeployStateManager,
    private val taskRunnerManager: TaskRunnerManager,
    private val logger: Logger,
) {
    private val fileProcessingLock = Any()

    fun init(projectRootDir: File, modules: Map<String, ModuleInfo>) {
        gitFileChangesDetector.init(projectRootDir, modules)
    }

    fun start(monitor: IFileChangeMonitor, onProcessed: (FileChangeResult) -> Unit) {
        var monitorFailed = false
        try {
            monitor.startListen(object : FileChangesListener {
                override fun onFileChanges(changedFiles: List<File>, deletedFiles: List<File>) {
                    processMonitorChanges(changedFiles, deletedFiles, onProcessed)
                }

                override fun onOverflow() {
                    reconcileAfterOverflow()
                }
            })
        } catch (e: Exception) {
            monitorFailed = true
            logger.warn("WatchService monitor startup failed, use Git file checker instead", e)
            runCatching { monitor.close() }
                .onFailure { logger.warn("Close failed WatchService monitor", it) }
        }
        gitFileChangesDetector.startListen(object : FileChangesListener {
            override fun onFileChanges(changedFiles: List<File>, deletedFiles: List<File>) {
                onProcessed(processFileChanges(changedFiles, deletedFiles, FileChangeSource.GIT))
            }
        })
        if (monitorFailed) {
            runCatching { gitFileChangesDetector.updateChangedFiles() }
                .onFailure { logger.warn("Initial Git file check after WatchService failure failed", it) }
        }
    }

    private fun processMonitorChanges(
        changedFiles: List<File>,
        deletedFiles: List<File>,
        onProcessed: (FileChangeResult) -> Unit,
    ) {
        runPendingFileProcessing("Process file changed") {
            onProcessed(processFileChanges(changedFiles, deletedFiles, FileChangeSource.MONITOR))
        }
    }

    private fun reconcileAfterOverflow() {
        runPendingFileProcessing("Reconcile file changes", gitFileChangesDetector::updateChangedFiles)
    }

    private fun runPendingFileProcessing(jobName: String, action: () -> Unit) {
        deployStateManager.beginFileProcessing()
        val hasEnded = AtomicBoolean()
        val endProcessing = {
            if (hasEnded.compareAndSet(false, true)) deployStateManager.endFileProcessing()
        }
        val job = try {
            taskRunnerManager.runBackgroundSafe(jobName, isNeedLog = false) {
                try {
                    synchronized(fileProcessingLock) {
                        action()
                    }
                } finally {
                    endProcessing()
                }
            }
        } catch (e: Throwable) {
            endProcessing()
            throw e
        }
        job.invokeOnCompletion {
            try {
                endProcessing()
            } catch (e: Throwable) {
                logger.warn("Finish pending file processing failed, jobName=$jobName", e)
            }
        }
    }

    @Synchronized
    fun processFileChanges(
        changedFiles: List<File>,
        deletedFiles: List<File>,
        source: FileChangeSource,
    ): FileChangeResult {
        logger.trace(
            "[PERF] FileChangeManager.processFileChanges source=$source, " +
                "changedSize=${changedFiles.size}, deletedSize=${deletedFiles.size}"
        )
        if (deletedFiles.isNotEmpty()) {
            deployFileManager.removeChangedFile(deletedFiles)
        }
        val filteredChanges = fileChangesHandler.filter(changedFiles)
        if (filteredChanges.isEmpty()) return FileChangeResult.EMPTY
        logger.debug("Detect file changed (size=${filteredChanges.size}): ${filteredChanges.map { it.file.name }}")
        deployFileManager.addChangedFile(filteredChanges)
        updateChangedBuildFilesIfNeeded(source, filteredChanges)
        if (source == FileChangeSource.MONITOR) {
            gitFileChangesDetector.onSourceFileChanged(filteredChanges)
        }
        return FileChangeResult(filteredChanges)
    }

    private fun updateChangedBuildFilesIfNeeded(source: FileChangeSource, changedFiles: List<ChangedFile>) {
        val isBuildFileChanged = changedFiles.any { it.type == CompileFile.Type.BuildFile }
        if (!isBuildFileChanged && source != FileChangeSource.RECOVER) return
        val buildFiles = deployFileManager.getUndeployedFiles()
            .filter { it.type == CompileFile.Type.BuildFile }
            .map { it.file }
        dependencyChangeManager.onUpdateChangedBuildFiles(buildFiles)
    }
}

enum class FileChangeSource {
    RECOVER,
    MONITOR,
    GIT,
}

data class FileChangeResult(val changedFiles: List<ChangedFile>) {
    val hasChanges: Boolean get() = changedFiles.isNotEmpty()

    companion object {
        val EMPTY = FileChangeResult(emptyList())
    }
}
