package com.sickworm.intellij.jugg.deploy

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.jugg.compiler.CompileFile
import com.sickworm.intellij.jugg.project.data.ModuleInfo
import com.sickworm.intellij.jugg.git.IGitManager
import com.sickworm.intellij.jugg.gradle.compile.crc32
import com.sickworm.intellij.jugg.gradle.compile.isChild
import com.sickworm.intellij.jugg.platform.PlatformApi
import com.sickworm.intellij.jugg.project.ChangedFile
import com.sickworm.intellij.jugg.project.IFileChangesHandler
import java.io.File
import java.security.MessageDigest

/**
 * Record deploy history, persist records for recovery the next time the project is opened.
 */
class DeployHistoryDb(
    private val projectDir: File,
    dbDir: File,
    private val fileChangesHandler: IFileChangesHandler,
    private val logger: Logger,
) {

    /** File to store deploy history */
    private val deployHistoryFile = File(dbDir, "deploy_history.json")

    /** File to store deploy history */
    private val overlayIdsFile = File(dbDir, "overlay_ids.json")

    /** Directory to store deploy items */
    private val deployItemsDir = File(dbDir, "deploys")

    /** Directory to store deployment changes record */
    private val deployLogsDir = File(dbDir, "logs")

    /** Directory to store build files, use to show diff in the future */
    private val buildFilesDir = File(dbDir, "build_files")

    private val gitManager: IGitManager = PlatformApi.createGitManagerAndTrySearchParent(projectDir)

    val isAvailable: Boolean
        get() = gitManager.hasInitGit && (gitManager.getLastCommitHash() != null)

    @Suppress("UNCHECKED_CAST")
    var overlayIds: Map<String, String>
        get() {
            if (!overlayIdsFile.exists()) {
                return emptyMap()
            }
            val json = overlayIdsFile.readText()
            val map = Gson().fromJson(json, Map::class.java)
            return try {
                map as Map<String, String>
            } catch (e: Exception) {
                logger.warn("Failed to parse overlay ids from file: $json")
                emptyMap()
            }
        }
        set(value) {
            val gson = GsonBuilder().setPrettyPrinting().create()
            val string = gson.toJson(value)
            overlayIdsFile.parentFile?.mkdirs()
            overlayIdsFile.writeText(string)
        }

    fun getChangedFilesSinceLastFullCompiled(): List<File>? {
        if (!isAvailable) {
            logger.info("Git not init in this project.")
            deleteHistory()
            return null
        }

        val deployHistoryData = DeployHistoryData.load(deployHistoryFile)
        if (deployHistoryData == null) {
            logger.info("Project has not been deployed yet, need full compile first.")
            return null
        }
        val dirAndCommitMap = mutableMapOf<String, String?>()
        dirAndCommitMap[projectDir.absolutePath] = deployHistoryData.fullCompileGitCommitHash
        deployHistoryData.subModulesFullCompileGitCommitHash?.forEach { (rootDir, commit) ->
            dirAndCommitMap[rootDir] = commit
        }

        val changedFiles = mutableSetOf<File>()
        deployHistoryData.changedFiles?.keys?.forEach { path ->
            changedFiles.add(File(projectDir, path))
        }
        dirAndCommitMap.forEach { (rootDir, commit) ->
            val subChangedFiles = getGitChangedFiles(File(rootDir), commit)
            logger.debug("getChangedFilesSinceLastFullCompiled, dir: ${rootDir}, files: ${subChangedFiles?.map { it.name }}")
            if (subChangedFiles == null) {
                logger.warn("getChangedFilesSinceLastFullCompiled failed")
                return null
            }
            changedFiles.addAll(subChangedFiles)
        }
        val undeployFiles = changedFiles.filter {
            if (!it.exists()) {
                return@filter false
            }
            isCrcChanged(deployHistoryData, it)
        }
        logger.debug("getChangedFilesSinceLastFullCompiled, final files: ${undeployFiles.map { it.name }}")
        return undeployFiles
    }

    private fun getGitChangedFiles(rootDir: File, lastDeployCommitHash: String?): List<File>? {
        if (lastDeployCommitHash == null) {
            logger.warn("${rootDir.absolutePath} has no full compile on specific commit, maybe Git is init after full compilation.")
            return null
        }

        val lastProjectCommitHash = PlatformApi.createGitManagerAndTrySearchParent(rootDir).getLastCommitHash()
        if (lastProjectCommitHash == null) {
            logger.warn("${rootDir.absolutePath} has no git commit, maybe Git is delete after full compilation.")
            return null
        }

        val gitManager = PlatformApi.createGitManagerAndTrySearchParent(rootDir)
        val changedSinceLastDeployFiles = gitManager.getChangedFiles(lastDeployCommitHash, lastProjectCommitHash)
        val uncommittedFiles = gitManager.getUncommittedFiles()
        return changedSinceLastDeployFiles + uncommittedFiles
    }

    private fun isCrcChanged(deployHistoryData: DeployHistoryData, file: File): Boolean {
        val path = file.relativeTo(projectDir).path
        val fileCrc = deployHistoryData.changedFiles?.get(path)

        val isOnUncommittedFileList = fileCrc != null
        if (!isOnUncommittedFileList) {
            // not in uncommitted file list
            return true
        }

        val newCrc = file.crc32
        if (fileCrc != newCrc) {
            // file changed
            return true
        }
        return false
    }

    private fun deleteHistory() {
        deployHistoryFile.delete()
        deployLogsDir.deleteRecursively()
        deployItemsDir.deleteRecursively()
        buildFilesDir.deleteRecursively()
    }

    fun resetHistoryAfterFullCompiled(modules: Map<String, ModuleInfo>, startCompileTime: Long) {
        deleteHistory()

        val newDeployHistoryData: DeployHistoryData = if (isAvailable) {
            val newCommitHash = gitManager.getLastCommitHash()
            val changedFiles = mutableMapOf<String, Long>()

            // add changed files in project root
            val mainChangedFiles = gitManager.getUncommittedFiles()
                .filter { it.exists() } // ignore deleted files
                .associate { it.toChangedFilePair(startCompileTime) }
            changedFiles.putAll(mainChangedFiles)

            val submoduleGitManagers = getSubmoduleGitManagers(modules.values)
            // add changed files in submodules
            val sumModuleChangedFile = submoduleGitManagers.values.map { submoduleGitManager ->
                submoduleGitManager.getUncommittedFiles()
                    .filter { it.exists() } // ignore deleted files
                    .associate { it.toChangedFilePair(startCompileTime) }
            }
            sumModuleChangedFile.forEach {
                changedFiles.putAll(it)
            }
            // get map of <submodule, commit>
            val subModulesFullCompileGitCommitHash = submoduleGitManagers.mapValues { (_, submoduleGitManager) ->
                submoduleGitManager.getLastCommitHash()
            }

            DeployHistoryData(newCommitHash, subModulesFullCompileGitCommitHash, 0, changedFiles)
        } else {
            DeployHistoryData(null, null,0, emptyMap())
        }
        newDeployHistoryData.save(deployHistoryFile)

        // save build files
        if (!newDeployHistoryData.changedFiles.isNullOrEmpty()) {
            buildFilesDir.mkdirs()
            val buildFiles = fileChangesHandler
                .filter(newDeployHistoryData.changedFiles.map { File(projectDir, it.key) })
                .filter { it.type == CompileFile.Type.Gradle }
            buildFiles.forEach {
                // path may not in the projectDir, so we cannot store it by relative path
                // store the path by file name
                val destFile = File(buildFilesDir, it.file.pathKey)
                it.file.copyTo(destFile, overwrite = true)
            }
        }
    }

    private fun getSubmoduleGitManagers(modules: Collection<ModuleInfo>): Map<String, IGitManager> {
        val subModulesGitManager = mutableMapOf<String, IGitManager>()
        val existGitRoots = mutableSetOf(gitManager.rootDir.absolutePath)
        modules.forEach {
            // git submodule is the child of project dir
//            if (it.moduleRootDir.isChild(projectDir)) {
//                return@forEach
//            }
            val subModuleGitManager = PlatformApi.createGitManagerAndTrySearchParent(it.moduleRootDir)
            if (subModuleGitManager.rootDir.absolutePath !in existGitRoots) {
                existGitRoots.add(subModuleGitManager.rootDir.absolutePath)
                subModulesGitManager[subModuleGitManager.rootDir.absolutePath] = subModuleGitManager
            }
        }
        return subModulesGitManager
    }

    fun updateHistory(sourceFiles: List<ChangedFile>) {
        val newDeployedFiles = sourceFiles.associate {
           it.file.toChangedFilePair()
        }

        val deployHistoryData = DeployHistoryData.load(deployHistoryFile)
        if (deployHistoryData == null) {
            logger.info("Project has no deployment history.")
            return
        }

        val newDeployHistoryData = deployHistoryData.copy(
            changedFiles = (deployHistoryData.changedFiles ?: emptyMap()) + newDeployedFiles,
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

    fun filterUnchangedFiles(files: List<File>): List<File> {
        if (!isAvailable) {
            logger.debug("filterUnchangedFiles failed, Git not init in this project.")
            return files
        }

        val deployHistoryData = DeployHistoryData.load(deployHistoryFile) ?: run {
            logger.debug("filterUnchangedFiles failed, Project has no deployment history.")
            return files
        }

        val gitFileMap = mutableMapOf<File, MutableList<File>>()
        val gitCommitMap = mutableMapOf<File, String>()

        if (deployHistoryData.fullCompileGitCommitHash == null) {
            logger.debug("filterUnchangedFiles failed, Project has no full compile history.")
            return files
        }
        gitFileMap[projectDir] = mutableListOf()
        gitCommitMap[projectDir] = deployHistoryData.fullCompileGitCommitHash

        deployHistoryData.subModulesFullCompileGitCommitHash?.firstNotNullOfOrNull { (subDir, subCommitHash) ->
            if (subCommitHash == null) {
                logger.debug("filterUnchangedFiles failed, subCommitHash is null, which should not happen.")
                return@firstNotNullOfOrNull false
            }
            gitCommitMap[File(subDir)] = subCommitHash
            gitFileMap[File(subDir)] = mutableListOf()
        }

        logger.debug("filterUnchangedFiles filtering ${files.map { it.name }} in ${gitFileMap.map { it.key.path }}")
        val unchangedFiles = mutableListOf<File>()
        files.forEach { file ->
            val path = file.relativeTo(projectDir).path
            val fileCrc = deployHistoryData.changedFiles?.get(path)

            val isOnUncommittedFileList = fileCrc != null
            if (isOnUncommittedFileList) {
                val newCrc = file.crc32
                if (fileCrc != newCrc) {
                    // file changed, don't put it in gitFileMap
                    return@forEach
                } else {
                    // file not changed
                    unchangedFiles.add(file)
                    return@forEach
                }
            }


            // file not in records, use git to check it later
            val gitRootDir = file.findClosestParent(gitFileMap.keys)
            if (gitRootDir != null) {
                gitFileMap[gitRootDir]?.add(file)
            }
        }

        gitFileMap.forEach { (projectDir, files) ->
            if (files.isEmpty()) {
                logger.debug("filterUnchangedFiles filtering [] in $projectDir")
                return@forEach
            }

            val gitManager = PlatformApi.createGitManagerAndTrySearchParent(projectDir)
            logger.debug("filterUnchangedFiles filtering ${files.map { it.name }} in ${gitManager.rootDir}")
            val changedFileByGit = gitManager.filterChangedFiles(gitCommitMap[projectDir]!!, files).map {
                it.absolutePath
            }.toSet()
            files.forEach {
                if (it.absolutePath !in changedFileByGit) {
                    unchangedFiles.add(it)
                }
            }
        }

        logger.debug("filterUnchangedFiles result: ${unchangedFiles.map { it.name }}")
        return unchangedFiles
    }

    private fun File.toChangedFilePair(startCompileTime: Long = Long.MAX_VALUE): Pair<String, Long> {
        val relativePath = relativeTo(projectDir).path
        val crc = if (lastModified() <= startCompileTime) {
            crc32
        } else {
            // mark file as dirty by set crc32 to 0 if file is modified after startCompileTime
            logger.debug("File $relativePath is modified after startCompileTime, mark it as dirty.")
            0L
        }
        return relativePath to crc
    }

    fun getLastBuildFiles(files: List<ChangedFile>): List<Pair<ChangedFile, File?>> {
        val deployHistoryData = DeployHistoryData.load(deployHistoryFile) ?: return files.map { it to null }

        val remainFiles = files.toMutableList()
        val result = mutableListOf<Pair<ChangedFile, File?>>()

        // find it in build dir first. some build files may not be commited, instead they are in buildFilesDir
        files.forEach { file ->
            val lastBuildFile = File(buildFilesDir, file.file.pathKey)
            if (lastBuildFile.exists()) {
                result.add(file to lastBuildFile)
                remainFiles.remove(file)
            }
        }

        val gitDirToHashMap = mutableMapOf<File, String?>()
        gitDirToHashMap[projectDir] = deployHistoryData.fullCompileGitCommitHash
        deployHistoryData.subModulesFullCompileGitCommitHash?.forEach { (rootDir, commitHash) ->
            gitDirToHashMap[File(rootDir)] = commitHash
        }

        remainFiles.forEach { file ->
            var lastBuildCommitId: String? = null
            val gitRootDir = file.file.findClosestParent(gitDirToHashMap.keys)
            if (gitRootDir != null) {
                lastBuildCommitId = gitDirToHashMap[gitRootDir]
            }

            if (lastBuildCommitId == null || gitRootDir == null) {
                logger.debug("getLastBuildFile for $file, lastBuildCommitId is null, which should not happen.")
                result.add(file to null)
                return@forEach
            }

            val lastBuildFile = File(buildFilesDir, file.file.pathKey)
            logger.debug("getLastBuildFile, $lastBuildFile exists: ${lastBuildFile.exists()}")
            if (!lastBuildFile.exists()) {
                val content = PlatformApi.createGitManager(gitRootDir)
                    .getLastCommitFileContent(lastBuildCommitId, file.file)
                logger.debug("getLastBuildFile, getLastCommitFileContent exists: ${content != null}")
                if (content != null) {
                    lastBuildFile.parentFile?.mkdirs()
                    lastBuildFile.writeText(content)
                }
            }

            if (lastBuildFile.exists()) {
                result.add(file to lastBuildFile)
            } else {
                result.add(file to null)
            }
        }

        return result
    }

    fun getDeployHistoryData(): DeployHistoryData? {
        return DeployHistoryData.load(deployHistoryFile)
    }

    private fun File.findClosestParent(directories: Collection<File>): File? {
        val parentDirectories = directories.filter { isChild(it) }
        return parentDirectories.maxByOrNull { it.absolutePath.length } // find the longest path
    }

    private val File.pathKey: String
            get() = relativeTo(projectDir).path.md5 + "." + extension

    private val String.md5: String get() = MessageDigest.getInstance("MD5").digest(this.toByteArray()).toHex()
    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}

/**
 * Persisted deploy history.
 * Used to find out changed files since last deploy, even IDE is closed (requires Git).
 */
data class DeployHistoryData(
    val fullCompileGitCommitHash: String?,
    /** dir -> commit */
    val subModulesFullCompileGitCommitHash: Map<String, String?>?,
    val incDeployTimes: Int,
    /**
     * Map of RelativeFilePath to Crc32Hash.
     * Records changed files on full compile and deploy.
     */
    val changedFiles: Map<String, Long>?,
    val version: Int = LATEST_VERSION,
) {

    fun save(target: File) {
        val gson = GsonBuilder().setPrettyPrinting().create()
        val string = gson.toJson(this)
        target.parentFile?.mkdirs()
        target.writeText(string)
        cache[target.absolutePath] = this
    }

    companion object {

        private const val LATEST_VERSION = 1

        private val cache = mutableMapOf<String, DeployHistoryData>()

        fun load(target: File, isUseCache: Boolean = true): DeployHistoryData? {
            if (!target.exists()) {
                return null
            }

            val cacheKey = target.absolutePath
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