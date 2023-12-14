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
        logger = logger,
    ),
): IDeployHistoryManager {

    override val isRecoverFeatureAvailable: Boolean
        get() = deployHistoryDb.isAvailable

    private var hasBeenFullCompiledRuntime = false

    override val hasBeenFullCompiled: Boolean
        get() = if (isRecoverFeatureAvailable) {
            // we can recover from last full compile in db, because we have recover feature
            compileContextDb.hasBeenFullCompiled
        } else {
            // we need to do one full compile, because we don't have recover feature
            hasBeenFullCompiledRuntime
        }

    override var lastDeployOverlayIds: Map<String, String>
        get() = deployHistoryDb.overlayIds
        set(value) {
            deployHistoryDb.overlayIds = value
        }

    override fun tryGetContextRecoverInfoFromDb(): DeployContextRecoverInfo? {
        if (!isRecoverFeatureAvailable) {
            logger.warn("tryGetContextRecoverInfoFromDb failed, recover feature not available")
            return null
        }
        logger.debug("tryGetContextRecoverInfoFromDb recover feature is available")

        val startTime = System.currentTimeMillis()
        val changedFiles = deployHistoryDb.getChangedFilesSinceLastFullCompiled()
        val changedFilesTime = System.currentTimeMillis()

        val compileContextInfo = compileContextDb.getCompileBuildPathInfoFromDb()
        val compileContextInfoTime = System.currentTimeMillis()

        val deployedFiles = compileContextDb.getDeployedData()
        val deployedFilesTime = System.currentTimeMillis()

        logger.debug("tryGetContextRecoverInfoFromDb, " +
                "changedFiles: ${changedFiles?.size}, cost: ${changedFilesTime - startTime}ms; " +
                "compileContextInfo: ${compileContextInfo?.moduleBuildPathInfos?.size}, cost: ${compileContextInfoTime - changedFilesTime}ms; " +
                "deployedFiles: ${deployedFiles?.size},  cost: ${deployedFilesTime - compileContextInfoTime}ms.")

        if (changedFiles == null) {
            logger.debug("getChangedFilesSinceLastFullCompiled failed, return null")
            return null
        }
        if (compileContextInfo == null) {
            logger.debug("getCompileBuildPathInfoFromDb failed, return null")
            return null
        }
        if (deployedFiles == null) {
            logger.debug("getDeployedData failed, return null")
            return null
        }

        return DeployContextRecoverInfo(changedFiles, compileContextInfo, deployedFiles)
    }

    @Synchronized
    override fun reInitAfterFullCompiled(
        apkInfos: List<ApkInfo>,
        modules: Map<String, ModuleInfo>,
    ): CompileContextInfo {
        logger.debug("reInitAfterFullCompiled, apkInfos: ${apkInfos.size}, modules: ${modules.size}")
        deployHistoryDb.deleteHistory()
        val compileContextInfo = compileContextDb.saveCompileContext(apkInfos, modules)
        deployHistoryDb.resetHistoryAfterFullCompiled(modules)
        hasBeenFullCompiledRuntime = true
        return compileContextInfo
    }

    @Synchronized
    override fun updateHistoryOnAfterDeployed(sourceFiles: List<ChangedFile>, deployedFiles: List<CompileOutput>) {
        logger.debug("updateHistoryOnAfterDeployed, sourceFiles: ${sourceFiles.size}, deployedFiles: ${deployedFiles.size}")
        compileContextDb.updateDeployedData(deployedFiles)
        deployHistoryDb.updateHistory(sourceFiles)
    }
}