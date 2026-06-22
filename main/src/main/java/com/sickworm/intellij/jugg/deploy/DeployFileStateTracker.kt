package com.sickworm.intellij.jugg.deploy

import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.jugg.compiler.CompileFile
import com.sickworm.intellij.jugg.compiler.CompileOutput
import com.sickworm.intellij.jugg.gradle.compile.isChild
import com.sickworm.intellij.jugg.project.ChangedFile
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date

/**
 * Tracks runtime deploy file states for incremental compile/deploy lifecycle.
 */
class DeployFileStateTracker(
    private val logger: Logger? = null,
) {
    private var uncompiledFiles = mutableMapOf<String, ChangedFile>()
    private var compiledFiles = mutableMapOf<String, ChangedFile>()
    private var stagingFiles = mutableMapOf<String, CompileOutput>()
    private val deployedFiles = mutableMapOf<String, CompileOutput>()
    private val mergedDexFilePathSet = mutableMapOf<String, Long>()
    private val handledFileSnapshots = mutableMapOf<String, FileSnapshot>()

    @Synchronized
    fun clearMergedDexFilePaths() {
        mergedDexFilePathSet.clear()
    }

    @Synchronized
    fun markMergedDexFilePaths(dexFiles: List<CompileOutput>) {
        dexFiles.forEach {
            mergedDexFilePathSet[it.relativeFile.path] = it.file.lastModified()
        }
    }

    @Synchronized
    fun replaceDeployedFiles(outputs: List<CompileOutput>) {
        deployedFiles.clear()
        outputs.forEach {
            deployedFiles[it.file.stdAbsPath] = it
        }
    }

    @Synchronized
    fun addChangedFiles(files: List<ChangedFile>): List<ChangedFile> {
        val filesNeedCompile = files.filterNot {
            isHandledSnapshot(it)
        }
        val newFiles = filesNeedCompile.filter {
            !uncompiledFiles.containsKey(it.file.stdPath)
        }
        filesNeedCompile.forEach {
            markNeedsCompile(it)
        }
        return newFiles
    }

    @Synchronized
    fun rollbackChangedFiles(files: List<ChangedFile>) {
        files.forEach {
            markNeedsCompile(it)
        }
    }

    @Synchronized
    fun removeChangedFiles(files: List<File>) {
        files.forEach { file ->
            uncompiledFiles.iterator().let { iterator ->
                iterator.forEach { (stdPath, changedFile) ->
                    if (stdPath == file.stdPath || changedFile.file.isChild(file)) {
                        iterator.remove()
                    }
                }
            }
            compiledFiles.iterator().let { iterator ->
                iterator.forEach { (stdPath, changedFile) ->
                    if (stdPath == file.stdPath || changedFile.file.isChild(file)) {
                        iterator.remove()
                    }
                }
            }
            removeHandledSnapshots(file)
        }
    }

    @Synchronized
    fun updateUncompiledFiles(successFiles: List<CompileFile>, failedFiles: List<CompileFile>) {
        successFiles.forEach {
            val fileKey = it.file.stdAbsPath
            val changedFile = uncompiledFiles[fileKey] ?: return@forEach
            changedFile.compiledTimes++
            uncompiledFiles.remove(fileKey)
            compiledFiles[fileKey] = changedFile
            markHandled(it.file)
        }
        failedFiles.forEach {
            val fileKey = it.file.stdAbsPath
            val changedFile = uncompiledFiles[fileKey] ?: return@forEach
            changedFile.compiledTimes++
        }
    }

    @Synchronized
    fun getUncompiledFiles(): List<ChangedFile> {
        return uncompiledFiles.values.toList()
    }

    @Synchronized
    fun getCompiledFiles(): List<ChangedFile> {
        return compiledFiles.values.toList()
    }

    @Synchronized
    fun getUndeployedFiles(): List<ChangedFile> {
        return uncompiledFiles.values + compiledFiles.values
    }

    @Synchronized
    fun isNoFileChanges(): Boolean {
        return getUndeployedFiles().all { it.hasCompiledOnce }
    }

    @Synchronized
    fun getStagingFiles(isFilterMergedDex: Boolean = false): List<CompileOutput> {
        return stagingFiles.values.filter {
            if (isFilterMergedDex) {
                val mergedTime = mergedDexFilePathSet[it.relativeFile.path]
                if (mergedTime != null && it.file.lastModified() == mergedTime) {
                    return@filter false // merged dex file
                }
            }
            return@filter true
        }
    }

    @Synchronized
    fun addStagingFiles(compileOutputFiles: List<CompileOutput>) {
        compileOutputFiles.forEach {
            stagingFiles[it.file.stdAbsPath] = it
        }
    }

    @Synchronized
    fun clearStagingFiles() {
        stagingFiles.clear()
    }

    @Synchronized
    fun getDeployedFiles(): List<CompileOutput> {
        return deployedFiles.values.toList()
    }

    @Synchronized
    fun getNotStagingDeployedFiles(): List<CompileOutput> {
        val stagingDeployKeys = stagingFiles.values.flatMap { it.deployKeys() }.toSet()
        val stagingDexRelativePaths = stagingFiles.values
            .filter { it.type == CompileOutput.Type.Dex }
            .map { it.relativeFile.path }
            .toSet()
        return deployedFiles.values.filter {
            if (it.deployKeys().any { key -> key in stagingDeployKeys }) {
                return@filter false // staging file
            }
            if (it.isLossyDexHistory() && it.relativeFile.path in stagingDexRelativePaths) {
                return@filter false // staging dex shadows recovered history without APK scope
            }
            if (it.relativeFile.path in mergedDexFilePathSet) {
                return@filter false // merged dex file
            }
            return@filter true
        }
    }

    private fun CompileOutput.isLossyDexHistory(): Boolean {
        return type == CompileOutput.Type.Dex && apkPath == null && targetApkPaths.isEmpty()
    }

    private fun CompileOutput.deployKeys(): List<String> {
        val apkKeys = when {
            targetApkPaths.isNotEmpty() -> targetApkPaths
            apkPath != null -> listOf(apkPath)
            else -> listOf("")
        }
        return apkKeys.map { apkPath -> "$apkPath:${relativeFile.path}" }
    }

    /**
     * Move staging outputs into deployed set and cleanup transient compile states.
     */
    @Synchronized
    fun commitAndClear(onRemoveBuildFile: (String) -> Unit) {
        deployedFiles.putAll(stagingFiles)
        stagingFiles.clear()
        compiledFiles.clear()

        val removeBuildFileFiles = uncompiledFiles.filter {
            it.value.type == CompileFile.Type.BuildFile
        }
        removeBuildFileFiles.keys.forEach {
            onRemoveBuildFile(it)
            uncompiledFiles.remove(it)
        }
    }

    @Synchronized
    fun resetKeepingRecentUncompiled(resetFilesBeforeTimeMill: Long?) {
        val remainUncompiledFiles = uncompiledFiles.filter {
            resetFilesBeforeTimeMill != null &&
                it.value.file.exists() &&
                it.value.file.lastModified() > resetFilesBeforeTimeMill
        }

        uncompiledFiles.clear()
        compiledFiles.clear()
        stagingFiles.clear()
        handledFileSnapshots.clear()

        if (remainUncompiledFiles.isNotEmpty()) {
            uncompiledFiles.putAll(remainUncompiledFiles)
        }
    }

    @Synchronized
    fun resetAfterReinstall() {
        val remainDeployedFiles = getNotStagingDeployedFiles()
        // put remainDeployedFiles into stagingFiles for next deployment
        remainDeployedFiles.forEach {
            stagingFiles[it.file.stdAbsPath] = it
        }
    }
    
    @Synchronized
    fun remapUncompiledFiles(transform: (ChangedFile) -> ChangedFile?) {
        val mappedFiles = mutableMapOf<String, ChangedFile>()
        uncompiledFiles.values.forEach { changedFile ->
            val mapped = transform(changedFile) ?: return@forEach
            mappedFiles[mapped.file.stdPath] = mapped
        }
        uncompiledFiles = mappedFiles
    }

    private fun isHandledSnapshot(changedFile: ChangedFile): Boolean {
        if (uncompiledFiles.containsKey(changedFile.file.stdPath)) {
            return false
        }
        val currentSnapshot = FileSnapshot.from(changedFile.file)
        val handledSnapshot = handledFileSnapshots[changedFile.file.stdAbsPath]
        if (currentSnapshot != handledSnapshot) {
            return false
        }
        logger?.debug(
            "Ignore stale changed file event, path=${changedFile.file.stdAbsPath}, " +
                "lastModified=${currentSnapshot.formattedLastModified()}"
        )
        return true
    }

    private fun markNeedsCompile(changedFile: ChangedFile) {
        uncompiledFiles[changedFile.file.stdPath] = changedFile
        compiledFiles.remove(changedFile.file.stdPath)
        removeHandledSnapshot(changedFile.file)
    }

    private fun markHandled(file: File) {
        handledFileSnapshots[file.stdAbsPath] = FileSnapshot.from(file)
    }

    private fun removeHandledSnapshot(file: File) {
        handledFileSnapshots.remove(file.stdAbsPath)
    }

    private fun removeHandledSnapshots(file: File) {
        val path = file.stdAbsPath
        handledFileSnapshots.keys.removeIf {
            it == path || it.startsWith("$path/")
        }
    }

    private data class FileSnapshot(
        val lastModified: Long,
        val length: Long,
    ) {
        fun formattedLastModified(): String {
            return SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS").format(Date(lastModified))
        }

        companion object {
            fun from(file: File): FileSnapshot {
                return FileSnapshot(
                    lastModified = file.lastModified(),
                    length = file.length(),
                )
            }
        }
    }
}
