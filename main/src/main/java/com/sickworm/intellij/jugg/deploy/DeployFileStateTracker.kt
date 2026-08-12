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
    // Copied files may preserve old mtimes, so full-build cleanup must use when Jugg observed the change.
    private var uncompiledObservedAt = mutableMapOf<String, Long>()
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
                        uncompiledObservedAt.remove(stdPath)
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
            uncompiledObservedAt.remove(fileKey)
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
        compileOutputFiles.forEach(::addStagingFile)
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
            if (it.isShadowedByStaging(stagingDeployKeys, stagingDexRelativePaths)) {
                return@filter false
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

    private fun CompileOutput.isShadowedByStaging(
        stagingDeployKeys: Set<String>,
        stagingDexRelativePaths: Set<String>,
    ): Boolean {
        return deployKeys().any { it in stagingDeployKeys } ||
            isLossyDexHistory() && relativeFile.path in stagingDexRelativePaths
    }

    /**
     * Adds one output to the latest logical staging snapshot.
     * Later outputs replace the same deploy key, while scoped Dex always wins over lossy recovered Dex.
     */
    private fun addStagingFile(output: CompileOutput) {
        // A recovered Dex without APK scope must not replace an already staged scoped Dex.
        if (output.isLossyDexHistory()) {
            val scopedDex = stagingFiles.values.find {
                !it.isLossyDexHistory() &&
                    it.type == CompileOutput.Type.Dex &&
                    it.relativeFile.path == output.relativeFile.path
            }
            if (scopedDex != null) {
                logger?.debug("Ignore lossy staging dex, file=${output.file.stdAbsPath}, " +
                        "scopedFile=${scopedDex.file.stdAbsPath}")
                return
            }
        }

        val deployKeys = output.deployKeys().toSet()
        // Replace the same deploy key and any lossy Dex history represented by the same relative path.
        val replacedFiles = stagingFiles.filterValues {
            it.deployKeys().any(deployKeys::contains) ||
                it.isLossyDexHistory() &&
                output.type == CompileOutput.Type.Dex &&
                it.relativeFile.path == output.relativeFile.path
        }
        replacedFiles.keys.forEach(stagingFiles::remove)
        if (replacedFiles.isNotEmpty()) {
            logger?.debug("Replace staging output, oldFiles=${replacedFiles.values.map { it.file.stdAbsPath }}, " +
                    "newFile=${output.file.stdAbsPath}, deployKeys=$deployKeys")
        }
        stagingFiles[output.file.stdAbsPath] = output
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
        val stagingDeployKeys = stagingFiles.values.flatMap { it.deployKeys() }.toSet()
        val stagingDexRelativePaths = stagingFiles.values
            .filter { it.type == CompileOutput.Type.Dex }
            .map { it.relativeFile.path }
            .toSet()
        val shadowedDeployedFiles = deployedFiles.filterValues {
            it.isShadowedByStaging(stagingDeployKeys, stagingDexRelativePaths)
        }
        if (shadowedDeployedFiles.isNotEmpty()) {
            val details = shadowedDeployedFiles.values.take(20).map {
                val reason = if (it.deployKeys().any(stagingDeployKeys::contains)) {
                    "same deploy key"
                } else {
                    "unscoped dex replaced by scoped staging dex"
                }
                "${it.file.stdAbsPath} ($reason)"
            }
            logger?.debug("Remove shadowed deployed outputs before commit because staging replacements must be " +
                    "the only deployed records; otherwise a later deploy or dex merge may consume duplicate " +
                    "logical artifacts. removed=${shadowedDeployedFiles.size}, first20=$details")
        }
        shadowedDeployedFiles.keys.forEach(deployedFiles::remove)
        deployedFiles.putAll(stagingFiles)
        stagingFiles.clear()
        compiledFiles.clear()

        val removeBuildFileFiles = uncompiledFiles.filter {
            it.value.type == CompileFile.Type.BuildFile
        }
        removeBuildFileFiles.keys.forEach {
            onRemoveBuildFile(it)
            uncompiledFiles.remove(it)
            uncompiledObservedAt.remove(it)
        }
    }

    @Synchronized
    fun resetKeepingRecentUncompiled(resetFilesBeforeTimeMill: Long?) {
        val remainUncompiledFiles = uncompiledFiles.filter {
            resetFilesBeforeTimeMill != null &&
                it.value.file.exists() &&
                uncompiledObservedAt.getOrDefault(it.key, 0L) > resetFilesBeforeTimeMill
        }
        val remainObservedAt = remainUncompiledFiles.keys.associateWith {
            uncompiledObservedAt.getValue(it)
        }

        uncompiledFiles.clear()
        uncompiledObservedAt.clear()
        compiledFiles.clear()
        stagingFiles.clear()
        handledFileSnapshots.clear()

        if (remainUncompiledFiles.isNotEmpty()) {
            uncompiledFiles.putAll(remainUncompiledFiles)
            uncompiledObservedAt.putAll(remainObservedAt)
            remainUncompiledFiles.values.forEach {
                markHandled(it.file)
            }
        }
    }

    @Synchronized
    fun resetAfterReinstall() {
        val remainDeployedFiles = getNotStagingDeployedFiles()
        // put remainDeployedFiles into stagingFiles for next deployment
        remainDeployedFiles.forEach(::addStagingFile)
    }
    
    @Synchronized
    fun remapUncompiledFiles(transform: (ChangedFile) -> ChangedFile?) {
        val mappedFiles = mutableMapOf<String, ChangedFile>()
        val mappedObservedAt = mutableMapOf<String, Long>()
        uncompiledFiles.forEach { (path, changedFile) ->
            val mapped = transform(changedFile) ?: return@forEach
            val mappedPath = mapped.file.stdPath
            mappedFiles[mappedPath] = mapped
            mappedObservedAt[mappedPath] = uncompiledObservedAt.getValue(path)
        }
        uncompiledFiles = mappedFiles
        uncompiledObservedAt = mappedObservedAt
    }

    private fun isHandledSnapshot(changedFile: ChangedFile): Boolean {
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
        val filePath = changedFile.file.stdPath
        uncompiledFiles[filePath] = changedFile
        uncompiledObservedAt[filePath] = System.currentTimeMillis()
        compiledFiles.remove(filePath)
        markHandled(changedFile.file)
    }

    private fun markHandled(file: File) {
        handledFileSnapshots[file.stdAbsPath] = FileSnapshot.from(file)
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
