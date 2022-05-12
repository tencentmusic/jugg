package com.sickworm.intellij.jugg.deploy

import com.android.tools.idea.run.ApkInfo
import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.jugg.compiler.CompileOutput
import com.sickworm.intellij.jugg.compiler.ModuleInfo
import com.sickworm.intellij.jugg.project.ChangedFile
import java.io.File

/**
 * Manage deployment history for a project.
 * Find files that haven't been deployed by using Git. So it's not available if project not using git.
 * All operation must be thread-safe.
 */
class DeployHistoryManager(
    private val projectDir: File,
    private val storageDir: File,
    private val logger: Logger,
    private val deployHistoryDb: DeployHistoryDb = DeployHistoryDb(
        projectDir = projectDir,
        dbDir = File(storageDir, "deploy_history.db"),
        logger = logger,
    ),
    private val compileContextDb: CompileContextDb = CompileContextDb(
        dbDir = File(storageDir, "compile_context.db"),
        projectDir = projectDir,
        logger = logger,
    ),
): IDeployHistoryManager {

    override val isRecoverFeatureAvailable: Boolean
        get() = deployHistoryDb.isGitAvailable

    private var hasBeenFullCompiledRuntime = false

    override val hasBeenFullCompiled: Boolean
        get() = if (isRecoverFeatureAvailable) {
            // we can recover from last full compile in db, because we have recover feature
            compileContextDb.hasBeenFullCompiled
        } else {
            // we need to do one full compile, because we don't have recover feature
            hasBeenFullCompiledRuntime
        }

    override fun tryGetContextRecoverInfoFromDb(): DeployContextRecoverInfo? {
        if (!isRecoverFeatureAvailable) {
            logger.warn("tryGetContextRecoverInfoFromDb failed, recover feature not available")
            return null
        }

        val changedFiles = deployHistoryDb.getChangedFilesSinceLastFullCompiled()
        val compileContextInfo = compileContextDb.getCompileBuildPathInfoFromDb()
        val deployedFiles = compileContextDb.getDeployedData()

        if (changedFiles == null) {
            logger.warn("getChangedFilesSinceLastFullCompiled failed, return null")
            return null
        }
        if (compileContextInfo == null) {
            logger.warn("getCompileBuildPathInfoFromDb failed, return null")
            return null
        }
        if (deployedFiles == null) {
            logger.warn("getDeployedData failed, return null")
            return null
        }

        return DeployContextRecoverInfo(changedFiles, compileContextInfo, deployedFiles)
    }

    @Synchronized
    override fun reInitAfterFullCompiled(
        apkInfos: List<ApkInfo>,
        modules: Map<String, ModuleInfo>,
    ): CompileContextInfo {
        deployHistoryDb.deleteHistory()
        val compileContextInfo = compileContextDb.copyFullCompileOutput(apkInfos, modules)
        deployHistoryDb.resetHistoryAfterFullCompiled()
        hasBeenFullCompiledRuntime = true
        return compileContextInfo
    }

    @Synchronized
    override fun updateHistoryOnAfterDeployed(sourceFiles: List<ChangedFile>, deployedFiles: List<CompileOutput>) {
        compileContextDb.updateDeployedData(deployedFiles)
        deployHistoryDb.updateHistory(sourceFiles)
    }
}