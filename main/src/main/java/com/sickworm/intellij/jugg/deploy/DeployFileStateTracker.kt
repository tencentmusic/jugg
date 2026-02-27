package com.sickworm.intellij.jugg.deploy

import com.sickworm.intellij.jugg.compiler.CompileFile
import com.sickworm.intellij.jugg.compiler.CompileOutput
import com.sickworm.intellij.jugg.gradle.compile.isChild
import com.sickworm.intellij.jugg.project.ChangedFile
import java.io.File

/**
 * Tracks runtime deploy file states for incremental compile/deploy lifecycle.
 */
class DeployFileStateTracker {
    private var uncompiledFiles = mutableMapOf<String, ChangedFile>()
    private var compiledFiles = mutableMapOf<String, ChangedFile>()
    private var stagingFiles = mutableMapOf<String, CompileOutput>()
    private val deployedFiles = mutableMapOf<String, CompileOutput>()
    private val mergedDexFilePathSet = mutableSetOf<String>()

    @Synchronized
    fun clearMergedDexFilePaths() {
        mergedDexFilePathSet.clear()
    }

    @Synchronized
    fun addMergedDexFilePaths(filePaths: Collection<String>) {
        mergedDexFilePathSet += filePaths
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
        val newFiles = files.filter {
            !uncompiledFiles.containsKey(it.file.stdPath)
        }
        files.forEach {
            uncompiledFiles[it.file.stdPath] = it
            compiledFiles.remove(it.file.stdPath)
        }
        return newFiles
    }

    @Synchronized
    fun rollbackChangedFiles(files: List<ChangedFile>) {
        files.forEach {
            uncompiledFiles[it.file.stdPath] = it
            compiledFiles.remove(it.file.stdPath)
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
    fun getStagingFiles(): List<CompileOutput> {
        return stagingFiles.values.toList()
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
    fun getDeployedFilesMap(): Map<String, CompileOutput> {
        return deployedFiles.toMap()
    }

    @Synchronized
    fun getHistoryDexCountWithoutMerged(): Int {
        return deployedFiles.values.count {
            it.type == CompileOutput.Type.Dex && it.file.stdAbsPath !in mergedDexFilePathSet
        }
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

        if (remainUncompiledFiles.isNotEmpty()) {
            uncompiledFiles.putAll(remainUncompiledFiles)
        }
    }

    @Synchronized
    fun resetAfterReinstall() {
        mergedDexFilePathSet.clear()
        val stagingFileRelativeSet = stagingFiles.map { it.value.relativeFile.path }.toSet()
        val remainDeployedFiles = deployedFiles.values.filter {
            it.relativeFile.path !in stagingFileRelativeSet
        }
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
}
