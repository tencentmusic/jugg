package com.sickworm.intellij.jugg.deploy

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.TypeAdapter
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonWriter
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.vfs.VirtualFile
import com.sickworm.intellij.jugg.compiler.clearDir
import com.sickworm.intellij.jugg.git.IGitManager
import com.sickworm.intellij.jugg.project.ChangedFile
import java.io.File
import java.util.zip.CRC32

/**
 * Manage deployment history for a project.
 * Find files that haven't been deployed by using Git. So it's not available if project not using git.
 * All operation must be thread-safe.
 */
class DeployHistoryManager (
    private val gitManager: IGitManager,
    storageDir: File,
    private val logger: Logger,
): IDeployHistoryManager {

    /** Used to generate hash of a file */
    private val crc32 = CRC32()

    /** File to store deploy history */
    private val deployHistoryFile = File(storageDir, "deploy_history.json")

    /** Directory to store deploy items */
    private val deployItemsDir = File(storageDir, "deploys")

    /** Directory to store deployment changes record */
    private val deployLogsDir = File(storageDir, "logs")

    override val isAvailable: Boolean
        get() = gitManager.isGitAvailable()

    private val projectDir: File
        get() = gitManager.rootDir

    @Synchronized
    override fun getChangedFilesSinceLastDeployed(): List<File>? {
        if (!isAvailable) {
            logger.warn("$projectDir is not a git repository, deployment history is not available")
            return null
        }

        val deployHistoryData = DeployHistoryData.load(deployHistoryFile)
        if (deployHistoryData == null) {
            logger.warn("$projectDir has no deployment history")
            return null
        }

        val lastDeployCommitHash = deployHistoryData.fullCompileGitCommitHash
        val lastProjectCommitHash = gitManager.getLastCommitHash()
        if (lastDeployCommitHash == null) {
            logger.warn("$projectDir has no last deploy commit")
            return null
        }
        if (lastProjectCommitHash == null) {
            logger.warn("$projectDir has no last project commit")
            return null
        }

        val changedSinceLastDeployFiles = gitManager.getChangedFiles(lastDeployCommitHash, lastProjectCommitHash)
        val uncommittedFiles = gitManager.getUncommittedFiles()
        val undeployFiles = (changedSinceLastDeployFiles + uncommittedFiles).filter {
            isCrcChanged(deployHistoryData, it)
        }
        return undeployFiles
    }

    private fun isCrcChanged(deployHistoryData: DeployHistoryData, file: File): Boolean {
        val path = file.relativeTo(projectDir).path
        val deployedCrc = deployHistoryData.deployedFiles[path]
        @Suppress("FoldInitializerAndIfToElvis")
        if (deployedCrc == null) {
            // not in deployed file list
            return true
        }
        val newCrc = crc32.run {
            reset()
            update(file.readBytes())
            value
        }
        if (deployedCrc != newCrc) {
            // file changed
            return true
        }
        return false
    }

    @Synchronized
    override fun onAfterFullCompiled() {
        if (!isAvailable) {
            logger.debug("$projectDir is not a git repository, deployment history is not available")
            return
        }

        val newCommitHash = gitManager.getLastCommitHash()
        val newDeployHistoryData = DeployHistoryData(newCommitHash, 0, emptyMap())
        newDeployHistoryData.save(deployHistoryFile)
        deployLogsDir.clearDir()
        deployItemsDir.clearDir()
    }

    @Synchronized
    override fun onAfterDeployed(deployedFiles: List<ChangedFile>) {
        if (!isAvailable) {
            logger.debug("$projectDir is not a git repository, deployment history is not available")
            return
        }

        val newDeployedFiles = deployedFiles.associate {
            val ioFile = File(it.file.path)
            val relativePath = ioFile.relativeTo(projectDir).path
            val crc = crc32.run {
                reset()
                update(ioFile.readBytes())
                value
            }
            relativePath to crc
        }

        val deployHistoryData = DeployHistoryData.load(deployHistoryFile)
        if (deployHistoryData == null) {
            logger.warn("$projectDir has no deployment history")
            return
        }

        val newDeployHistoryData = deployHistoryData.copy(
            deployedFiles = deployHistoryData.deployedFiles + newDeployedFiles,
            incDeployTimes = deployHistoryData.incDeployTimes + 1,
        )
        newDeployHistoryData.save(deployHistoryFile)

        // print log file
        val logFile = File(deployLogsDir, "deploy_${newDeployHistoryData.incDeployTimes}.log")
        logFile.parentFile?.mkdirs()
        logFile.writeText((deployedFiles + deployedFiles).joinToString("\n") {
            it.toString()
        })
    }
}

/**
 * Persisted deploy history.
 */
data class DeployHistoryData(
    val fullCompileGitCommitHash: String?,
    val incDeployTimes: Int,
    /** Map<RelativeFilePath, Crc32Hash> */
    val deployedFiles: Map<String, Long>,
    val version: Int = LATEST_VERSION,
) {

    fun save(target: File) {
        val gson = GsonBuilder().setPrettyPrinting().create()
        val string = gson.toJson(this)
        target.writeText(string)
    }

    companion object {

        private const val LATEST_VERSION = 1

        fun load(target: File): DeployHistoryData? {
            if (!target.exists()) {
                return null
            }
            try {
                val json = target.readText()
                val data = Gson().fromJson(json, DeployHistoryData::class.java)
                if (data.version != LATEST_VERSION) {
                    return null
                }
                return data
            } catch (e: Exception) {
                return null
            }
        }
    }
}