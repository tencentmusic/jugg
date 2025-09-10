package com.sickworm.intellij.jugg.deploy

import com.android.tools.idea.run.ApkInfo
import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.jugg.compiler.CompileOutput
import com.sickworm.intellij.jugg.gradle.compile.pathEquals
import com.sickworm.intellij.jugg.project.data.ModuleInfo
import com.sickworm.intellij.jugg.project.ChangedFile
import com.sickworm.intellij.jugg.project.IFileChangesHandler
import com.sickworm.intellij.jugg.project.JuggPathManager
import java.io.File

/**
 * Manage deployment history for a project.
 * Find files that haven't been deployed by using Git. So it's not available if project not using git.
 * All operation must be thread-safe.
 */
class DeployHistoryManager(
    private val pathManager: JuggPathManager,
    private val fileChangesHandler: IFileChangesHandler,
    private val logger: Logger,
    private val deployHistoryDb: DeployHistoryDb = DeployHistoryDb(
        projectDir = pathManager.projectDir,
        dbDir = pathManager.deployHistoryDbDir,
        fileChangesHandler = fileChangesHandler,
        logger = logger,
    ),
    private val compileContextDb: CompileContextDb = CompileContextDb(
        dbDir = pathManager.compileContextDbDir,
        logger = logger,
    ),
): IDeployHistoryManager {

    override val isRecoverFeatureAvailable: Boolean
        get() = deployHistoryDb.isAvailable

    private var hasBeenFullCompiledRuntime = false

    override val hasBeenFullCompiled: Boolean
        get() = if (isRecoverFeatureAvailable) {
            // we can recover from last full compile in db, because we have recover feature
            compileContextDb.hasBeenFullCompiled && deployHistoryDb.getDeployHistoryData() != null
        } else {
            // we need to do one full compile, because we don't have recover feature
            hasBeenFullCompiledRuntime
        }

    override var isLastFullCompileFailed: Boolean
        get() = compileContextDb.isLastFullCompileFailed
        set(value) {
            compileContextDb.isLastFullCompileFailed = value
        }

    override var lastDeployOverlayIds: Map<String, String>
        get() = deployHistoryDb.overlayIds
        set(value) {
            deployHistoryDb.overlayIds = value
        }

    override var isForceReinstall: Boolean
        get() {
            return lastDeployOverlayIds.any { it.value == "force re-install" }
        }
        set(value) {
            if (!value) {
                throw IllegalArgumentException("isForceReinstall can only be set to true")
            }
            lastDeployOverlayIds = lastDeployOverlayIds.mapValues { "force re-install" }
        }

    override fun deleteDeployHistory() {
        hasBeenFullCompiledRuntime = false
        compileContextDb.deleteCompileContext()
        deployHistoryDb.deleteHistory()
    }

    override fun tryGetContextRecoverInfoFromDb(isOnInit: Boolean): DeployContextRecoverInfo? {
        if (!isRecoverFeatureAvailable) {
            logger.warn("tryGetContextRecoverInfoFromDb failed, recover feature not available")
            return null
        }
        logger.debug("tryGetContextRecoverInfoFromDb recover feature is available")

        val startTime = System.currentTimeMillis()
        val changedFiles = try {
            deployHistoryDb.getChangedFilesSinceLastFullCompiled(isOnInit)?.filter { it.isFile }
        } catch (e: Exception) {
            logger.warn("getChangedFilesSinceLastFullCompiled failed ", e)
            null
        }
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

    override fun beforeFullCompiled(changedFiles: List<ChangedFile>) {
        logger.debug("beforeFullCompiled, changedFiles: ${changedFiles.size}")
        deployHistoryDb.beforeFullCompiled(changedFiles)
    }

    @Synchronized
    override fun reInitAfterFullCompiled(
        apkInfos: List<ApkInfo>,
        modules: Map<String, ModuleInfo>,
        startCompileTime: Long,
    ): CompileContextInfo {
        logger.debug("reInitAfterFullCompiled, apkInfos: ${apkInfos.size}")
        val compileContextInfo = compileContextDb.saveCompileContext(apkInfos, modules)
        deployHistoryDb.resetHistoryAfterFullCompiled(modules, startCompileTime)
        hasBeenFullCompiledRuntime = true
        return compileContextInfo
    }

    override fun beforeIncrementalCompile(sourceFiles: List<ChangedFile>) {
        logger.debug("beforeIncrementalCompile, files: ${sourceFiles.map { it.file.name }}")
        deployHistoryDb.beforeIncrementalCompile(sourceFiles)
    }

    @Synchronized
    override fun updateHistoryOnAfterDeployed(deployedFiles: List<CompileOutput>) {
        logger.debug("updateHistoryOnAfterDeployed, deployedFiles: ${deployedFiles.size}")
        compileContextDb.updateDeployedData(deployedFiles)
        deployHistoryDb.updateHistoryAfterIncrementalCompile()
    }

    override fun filterUnchangedFiles(files: List<File>): List<File> {
       return deployHistoryDb.filterUnchangedFiles(files)
    }

    override fun getLastBuildFiles(files: List<ChangedFile>): List<Pair<ChangedFile, File?>> {
        return deployHistoryDb.getLastBuildFiles(files)
    }

    override fun getDeployHistoryData(): DeployHistoryData? {
        return deployHistoryDb.getDeployHistoryData()
    }

    /**
     * Current project directory is history.
     * Used to detect whether user copied the project
     */
    private var historyProjectDir: File?
        get() {
            return pathManager.historyProjectDirFile.takeIf { it.exists() }
                ?.readText(Charsets.UTF_8)
                ?.let { File(it) }
        }
        set(value) {
            val historyProjectDirFile = pathManager.historyProjectDirFile
            if (value != null) {
                historyProjectDirFile.parentFile?.mkdirs()
                historyProjectDirFile.writeText(value.path, Charsets.UTF_8)
            } else {
                historyProjectDirFile.delete()
            }
        }

    override fun checkProjectDirChanged() {
        // check project info is changed (e.g. user copied/moved a project)
        val historyProjectDir = historyProjectDir
        val realProjectDir = pathManager.projectDir
        logger.debug("checkProjectDirChanged, historyProjectDir: $historyProjectDir, realProjectDir: $realProjectDir")
        if (realProjectDir.pathEquals(historyProjectDir)) {
            logger.debug("Project dir is not changed, continue to recover from deploy history later")
        } else {
            if (historyProjectDir != null) {
                logger.debug("Project dir is changed, delete database: ${pathManager.databaseDir}")
                pathManager.databaseDir.deleteRecursively()
            }
            logger.debug("Update history project dir to $realProjectDir")
            this.historyProjectDir = realProjectDir
        }
    }

    override fun updateDontFilterIgnoredFileRules(rules: List<String>) {
        deployHistoryDb.updateDontFilterIgnoredFileRules(pathManager.projectDir, rules)
    }
}