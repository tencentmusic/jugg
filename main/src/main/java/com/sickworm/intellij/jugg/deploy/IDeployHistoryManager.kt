package com.sickworm.intellij.jugg.deploy

import com.android.tools.idea.run.ApkInfo
import com.sickworm.intellij.jugg.compiler.CompileOutput
import com.sickworm.intellij.jugg.project.data.ModuleBuildPathInfo
import com.sickworm.intellij.jugg.project.data.ModuleInfo
import com.sickworm.intellij.jugg.project.ChangedFile
import java.io.File

/**
 * Manage deployment history for a project.
 */
interface IDeployHistoryManager {

    /**
     * history feature is available for use.
     */
    val isRecoverFeatureAvailable: Boolean

    /**
     * Whether current project has deployment history.
     */
    val hasBeenFullCompiled: Boolean

    /**
     * Whether last local full compilation is failed. Use to detect whether is need to fallback.
     */
    var isLastFullCompileFailed: Boolean

    /**
     * Records the last overlay ids to verify deployment state before deploy.
     */
    var lastDeployOverlayIds: Map<String, String>

    /**
     * Mark to force reinstall apk on next running.
     */
    var isForceReinstall: Boolean

    /**
     * Delete the deployment history and fallback at next time compilation.
     */
    fun deleteDeployHistory()

    /**
     * @return False if [hasBeenFullCompiled] is false. Otherwise, return true.
     */
    fun tryGetContextRecoverInfoFromDb(isOnInit: Boolean): DeployContextRecoverInfo?

    /**
     * Invoke this method to cache changed files
     */
    fun beforeFullCompiled(changedFiles: List<ChangedFile>)

    /**
     * Invoke this method to reset deploy history after project complete compiling by gradle.
     * Will do:
     * 1. Clear deploy history
     * 2. Collect incremental compile dependencies after full build.
     */
    fun reInitAfterFullCompiled(apkInfos: List<ApkInfo>, modules: Map<String, ModuleInfo>, startCompileTime: Long): CompileContextInfo

    /**
     * Invoke this method to cache changed files
     */
    fun beforeIncrementalCompile(sourceFiles: List<ChangedFile>)

    /**
     * Invoke this method to update deploy history after project complete deploying by Jugg.
     */
    fun updateHistoryOnAfterDeployed(deployedFiles: List<CompileOutput>)

    /**
     * Check whether file is changed with checking its checksum.
     */
    fun filterUnchangedFiles(files: List<File>): List<File>

    /**
     * Get the build file for specific build file since last full build.
     */
    fun getLastBuildFiles(files: List<ChangedFile>): List<Pair<ChangedFile, File?>>

    fun getDeployHistoryData(): DeployHistoryData?

    fun checkProjectDirChanged()

    /**
     * Update the ignore update build file rules to filter result of [filterUnchangedFiles]
     */
    fun updateDontFilterIgnoredFileRules(rules: List<String>)
}

/**
 * All things we need to recover incremental compile.
 */
data class DeployContextRecoverInfo(
    val changedFiles: List<File>,
    val compileContextInfo: CompileContextInfo,
    val deployedFiles: List<CompileOutput>,
)

/**
 * All incremental compile dependencies that we need.
 */
data class CompileContextInfo(
    val apkInfos: List<ApkInfo>,
    val moduleBuildPathInfos: Map<String, ModuleBuildPathInfo>,
) {

    override fun toString(): String {
        val projectRootDir = moduleBuildPathInfos.values.firstOrNull()?.projectRootDir ?: File("")
        return "CompileContextInfo(" +
                "apkInfos=${apkInfos.map { it.getDescription() }}, " +
                "projectRootDir=$projectRootDir, " +
                "moduleBuildPathInfos=${moduleBuildPathInfos.mapValues { it.value.moduleRootDir.relativeToOrSelf(projectRootDir) }}" +
                ")"
    }

    private fun ApkInfo.getDescription(): String {
        return "ApkInfo(applicationId=$applicationId, files=${files.map { "Module: ${it.moduleName}, File: ${it.apkFile}" }})"
    }
}