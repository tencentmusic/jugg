package com.sickworm.intellij.jugg.deploy

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.jugg.git.GitManager
import com.sickworm.intellij.jugg.git.IGitManager
import com.sickworm.intellij.jugg.project.ChangedFile
import java.io.File
import java.util.zip.CRC32

/**
 * Manage compile context build files, e.g. apk, classpath, etc.
 */
class DeployHistoryDb(
    private val projectDir: File,
    dbDir: File,
    private val logger: Logger,
    private val gitManager: IGitManager = GitManager(projectDir),
) {

    /** Used to generate hash of a file */
    private val crc32 = CRC32()

    /** File to store deploy history */
    private val deployHistoryFile = File(dbDir, "deploy_history.json")

    /** Directory to store deploy items */
    private val deployItemsDir = File(dbDir, "deploys")

    /** Directory to store deployment changes record */
    private val deployLogsDir = File(dbDir, "logs")

    val isAvailable: Boolean
        get() = gitManager.hasInitGit && (gitManager.getLastCommitHash() != null)

    fun getChangedFilesSinceLastFullCompiled(): List<File>? {
        if (!isAvailable) {
            logger.info("Git not init in this project")
            return null
        }

        val deployHistoryData = DeployHistoryData.load(deployHistoryFile)
        if (deployHistoryData == null) {
            logger.info("Project has not deployed yet")
            return null
        }

        val lastDeployCommitHash = deployHistoryData.fullCompileGitCommitHash
        val lastProjectCommitHash = gitManager.getLastCommitHash()
        if (lastDeployCommitHash == null) {
            logger.info("Project has no full compile on specific commit, this should not happened")
            return null
        }
        if (lastProjectCommitHash == null) {
            logger.info("Project has no git commit, this should not happened")
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

    fun deleteHistory() {
        deployHistoryFile.delete()
        deployLogsDir.deleteRecursively()
        deployItemsDir.deleteRecursively()
    }

    fun resetHistoryAfterFullCompiled() {
        val newCommitHash = gitManager.getLastCommitHash()
        val newDeployHistoryData = DeployHistoryData(newCommitHash, 0, emptyMap())
        newDeployHistoryData.save(deployHistoryFile)
        deployLogsDir.deleteRecursively()
        deployItemsDir.deleteRecursively()
    }

    fun updateHistory(sourceFiles: List<ChangedFile>) {
        val newDeployedFiles = sourceFiles.associate {
            val relativePath = it.file.relativeTo(projectDir).path
            val crc = crc32.run {
                reset()
                update(it.file.readBytes())
                value
            }
            relativePath to crc
        }

        val deployHistoryData = DeployHistoryData.load(deployHistoryFile)
        if (deployHistoryData == null) {
            logger.info("Project has no deployment history")
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
        logFile.writeText(sourceFiles.joinToString("\n") {
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
        target.parentFile?.mkdirs()
        target.writeText(string)
    }

    companion object {

        private const val LATEST_VERSION = 1

        private val cache = mutableMapOf<String, DeployHistoryData>()

        fun load(target: File, isUseCache: Boolean = true): DeployHistoryData? {
            if (!target.exists()) {
                return null
            }

            val cacheKey = target.absolutePath + "_" + target.lastModified()
            if (isUseCache && cache.containsKey(cacheKey)) {
                return cache[cacheKey]
            }

            try {
                val json = target.readText()
                val data = Gson().fromJson(json, DeployHistoryData::class.java)
                if (data.version != LATEST_VERSION) {
                    return null
                }
                cache[cacheKey] = data
                return data
            } catch (e: Exception) {
                return null
            }
        }
    }
}