package com.sickworm.intellij.jugg.deploy

import com.sickworm.intellij.jugg.apk.ApkInfo
import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.jugg.project.ChangedFile
import com.sickworm.intellij.jugg.compiler.CompileFile
import com.sickworm.intellij.jugg.compiler.CompileOutput
import com.sickworm.intellij.jugg.compiler.DesugarInfo
import com.sickworm.intellij.jugg.compiler.constref.ConstRefAnalyzer
import com.sickworm.intellij.jugg.compiler.constref.ConstRefCacheDatabase
import com.sickworm.intellij.jugg.compiler.constref.RepoSharedFingerprintStore
import com.sickworm.intellij.jugg.compiler.constref.ConstRefScheduler
import com.sickworm.intellij.jugg.compiler.obfuscation.ClassObfuscator
import com.sickworm.intellij.jugg.compiler.obfuscation.MinifyInfo
import com.sickworm.intellij.jugg.project.data.ModuleInfo
import com.sickworm.intellij.jugg.deploy.data.ConstRefEffectProvider
import com.sickworm.intellij.jugg.deploy.data.ConstRefReadiness
import com.sickworm.intellij.jugg.deploy.data.DeployDataGenerator
import com.sickworm.intellij.jugg.deploy.data.ResourceApkGenerator
import com.sickworm.intellij.jugg.deploy.data.SourceFileManager
import com.sickworm.intellij.jugg.deploy.run.DeployItem
import com.sickworm.intellij.jugg.deploy.run.JuggDeployData
import com.sickworm.intellij.jugg.ide.bean.JuggSettings
import com.sickworm.intellij.jugg.logger.getInstance
import com.sickworm.intellij.jugg.project.IBackgroundTaskRunner
import com.sickworm.intellij.jugg.project.JuggInternalException
import com.sickworm.intellij.jugg.project.JuggPathManager
import org.jetbrains.annotations.TestOnly
import java.io.File
import java.util.zip.CRC32

/**
 * Manage runtime deploy file status and provides [JuggDeployData]
 */
class DeployFileManager(
    private val pathManager: JuggPathManager,
    private var backgroundTaskRunner: IBackgroundTaskRunner,
    private val logger: Logger,
) {
    private val isConstRefTasksEnabled: Boolean
        get() = JuggSettings.isEnableConstRefTasks

    private val stateTracker = DeployFileStateTracker()

    /**
     * get source file by source file name in dex file
     */
    private val sourceFileManager = SourceFileManager(pathManager.projectDir, pathManager.databaseDir, logger.getInstance("SourceFileManager"))

    private val constRefCacheDatabase = ConstRefCacheDatabase(
        pathManager.constRefSharedDbFile,
        logger.getInstance("ConstRefCacheDatabase"),
    )

    private val repoSharedFingerprintStore = run {
        val fingerprintLogger = logger.getInstance("RepoSharedFingerprintStore")
        logger.debug("Const-ref db paths: sharedDb=${pathManager.constRefSharedDbFile.absolutePath}, " +
                    "repoFingerprintDb=${pathManager.repoFingerprintDbFile.absolutePath}")
        RepoSharedFingerprintStore.migrateLegacyDbIfNeeded(pathManager.repoFingerprintDbFile, fingerprintLogger)
        RepoSharedFingerprintStore(
            logger = fingerprintLogger,
            dbFile = pathManager.repoFingerprintDbFile,
        )
    }

    private val constRefScheduler = ConstRefScheduler(
        analyzer = ConstRefAnalyzer(logger.getInstance("ConstRefAnalyzer")),
        database = constRefCacheDatabase,
        logger = logger.getInstance("ConstRefScheduler"),
        backgroundTaskRunner = backgroundTaskRunner,
        repoSharedFingerprintStore = repoSharedFingerprintStore,
    )

    private val constRefEffectProvider = object : ConstRefEffectProvider {
        override fun ensureReadyForRecompile(changedSourcePaths: Collection<String>, timeoutMs: Long): ConstRefReadiness {
            if (!isConstRefTasksEnabled) {
                return ConstRefReadiness.READY
            }
            val readiness = constRefScheduler.ensureReadyForRecompile(changedSourcePaths, timeoutMs)
            return ConstRefReadiness(
                isReady = readiness.isReady,
                unreadyPaths = readiness.unreadyPaths,
                pendingSourceDirs = readiness.pendingSourceDirs,
            )
        }

        override fun getEffectedFiles(changedSourcePaths: Collection<String>) =
            if (!isConstRefTasksEnabled) emptyList() else constRefScheduler.getEffectedFiles(changedSourcePaths)
    }

    /**
     * build [JuggDeployData]
     */
    private val deployDataGenerator = DeployDataGenerator(
        logger = logger.getInstance("DeployDataGenerator"),
        databaseDir = pathManager.databaseDir,
        constRefEffectProvider = constRefEffectProvider,
    )

    private val resourceApkGenerator = ResourceApkGenerator(
        deployDataGenerator.deployDataDatabase,
        pathManager.databaseDir.resolve("resource_apks"),
        logger,
    )
    private val deployDataPlanner = DeployDataPlanner(
        pathManager = pathManager,
        deployDataGenerator = deployDataGenerator,
        resourceApkGenerator = resourceApkGenerator,
        logger = logger,
    )
    private val compileEffectAnalyzer = CompileEffectAnalyzer(
        pathManager = pathManager,
        deployDataGenerator = deployDataGenerator,
        sourceFileManager = sourceFileManager,
        logger = logger,
    )

    private var moduleInfos: Map<String, ModuleInfo> = emptyMap()

    @Synchronized
    fun init(apks: List<ApkInfo>, deployedFiles: List<CompileOutput>, resetFilesBeforeTimeMill: Long?) {
        logger.debug("init deploy file manager, apks: ${apks.size}, deployedFiles: ${deployedFiles.size}, resetFilesBeforeTimeMill: $resetFilesBeforeTimeMill")
        reset(resetFilesBeforeTimeMill)
        stateTracker.clearMergedDexFilePaths()
        val deployItems = deployedFiles.map { it.toDeployItem() }
        deployDataGenerator.init(apks, deployItems)
        resourceApkGenerator.deleteResourceApk()
        stateTracker.replaceDeployedFiles(deployedFiles)
    }

    @Synchronized
    fun addChangedFile(files: List<ChangedFile>) {
        logger.debug("add changed files, size: ${files.size}, paths: $files")
        val newFiles = stateTracker.addChangedFiles(files)

        backgroundTaskRunner.runBackgroundSafe("DeployFileManager#updateSourceFiles") {
            sourceFileManager.updateFiles(newFiles, emptyList())
        }
        files.filter {
            it.type == CompileFile.Type.Java || it.type == CompileFile.Type.Kotlin
        }.forEach {
            if (isConstRefTasksEnabled) {
                constRefScheduler.onFileSaved(it.file.stdAbsPath)
            }
        }
    }

    @Synchronized
    fun rollbackChangedFile(files: List<ChangedFile>) {
        logger.debug("rollback changed files after cancel, size: ${files.size}, paths: $files")
        stateTracker.rollbackChangedFiles(files)
    }

    @Synchronized
    fun removeChangedFile(files: List<File>) {
        stateTracker.removeChangedFiles(files)

        backgroundTaskRunner.runBackgroundSafe("DeployFileManager#removeSourceFiles") {
            sourceFileManager.updateFiles(emptyList(), files.filter { !it.exists() })
        }
        files.forEach {
            if (isConstRefTasksEnabled) {
                constRefScheduler.onFileDeleted(it.stdAbsPath)
            }
        }
    }

    @Synchronized
    fun setBackgroundTaskRunner(backgroundTaskRunner: IBackgroundTaskRunner) {
        this.backgroundTaskRunner = backgroundTaskRunner
        constRefScheduler.setBackgroundTaskRunner(backgroundTaskRunner)
    }

    fun awaitConstRefAnalysis(filePaths: List<String>, timeoutMs: Long = 5000L) {
        if (!isConstRefTasksEnabled) {
            return
        }
        constRefScheduler.awaitAnalysis(filePaths, timeoutMs)
    }

    @Synchronized
    fun updateUncompiledFiles(successFiles: List<CompileFile>, failedFiles: List<CompileFile>) {
        logger.debug("updateUncompiledFiles, successFiles: ${successFiles.map { it.file.name } }" +
                ", failedFiles: ${failedFiles.map { it.file.name } }")
        stateTracker.updateUncompiledFiles(successFiles, failedFiles)
    }

    @Synchronized
    fun getUncompiledFiles(): List<ChangedFile> {
        return stateTracker.getUncompiledFiles()
    }

    @Synchronized
    fun getCompiledFiles(): List<ChangedFile> {
        return stateTracker.getCompiledFiles()
    }

    @Synchronized
    fun getUndeployedFiles(): List<ChangedFile> {
        return stateTracker.getUndeployedFiles()
    }

    /**
     * @return is no file changes since last compile finished (no matter success or failed)
     */
    @Synchronized
    fun isNoFileChanges(): Boolean {
        return stateTracker.isNoFileChanges()
    }

    @Synchronized
    fun getStagingFiles(): List<CompileOutput> {
        return stateTracker.getStagingFiles()
    }

    @Synchronized
    fun addStagingFiles(compileOutputFiles: List<CompileOutput>) {
        stateTracker.addStagingFiles(compileOutputFiles)
    }

    @Synchronized
    fun clearStagingFiles() {
        stateTracker.clearStagingFiles()
    }

    @Synchronized
    fun getDeployData(isWarmUp: Boolean = false, isEnableCompatDeploy: Boolean = false): JuggDeployData {
        val result = deployDataPlanner.buildDeployData(
            stagingOutputs = stateTracker.getStagingFiles(),
            historyDexCountWithoutMerged = stateTracker.getHistoryDexCountWithoutMerged(),
            deployedFiles = stateTracker.getDeployedFilesMap(),
            isWarmUp = isWarmUp,
            isEnableCompatDeploy = isEnableCompatDeploy,
        )
        if (result.mergedDexSourcePaths.isNotEmpty()) {
            stateTracker.addMergedDexFilePaths(result.mergedDexSourcePaths)
        }
        return result.deployData
    }

    fun appendCompatDeployFiles(deployData: JuggDeployData): JuggDeployData {
        return deployDataPlanner.appendCompatDeployFiles(deployData, stateTracker.getDeployedFilesMap())
    }

    @Synchronized
    fun getDeployedFiles(): List<CompileOutput> {
        return stateTracker.getDeployedFiles()
    }

    @Synchronized
    fun commit(juggDeployData: JuggDeployData) {
        logger.debug("commit juggDeployData, staging file size: ${stateTracker.getStagingFiles().size}, deployed file size: ${stateTracker.getDeployedFiles().size}")
        deployDataGenerator.commitDeployedData(juggDeployData)
        stateTracker.commitAndClear { path ->
            logger.debug("remove gradle file: $path")
        }
    }

    @TestOnly
    @Synchronized
    fun reset(resetFilesBeforeTimeMill: Long? = null) {
        logger.debug("reset deploy file manager, resetFilesBeforeTimeMill=$resetFilesBeforeTimeMill")
        stateTracker.resetKeepingRecentUncompiled(resetFilesBeforeTimeMill)
    }

    @TestOnly
    @Synchronized
    fun replaceDeployedFilesForTest(outputs: List<CompileOutput>) {
        stateTracker.replaceDeployedFiles(outputs)
    }

    @Synchronized
    fun resetAfterReinstall() {
        logger.debug("resetAfterReinstall start, staging file size: ${stateTracker.getStagingFiles().size}, deployed file size: ${stateTracker.getDeployedFiles().size}")
        deployDataGenerator.clearDeployedData()
        resourceApkGenerator.deleteResourceApk()
        stateTracker.resetAfterReinstall()
        logger.debug("resetAfterReinstall done, staging file size: ${stateTracker.getStagingFiles().size}")
    }

    @Synchronized
    fun updateModuleInfos(moduleInfos: Map<String, ModuleInfo>, mappingFile: File?) {
        this.moduleInfos = moduleInfos
        stateTracker.remapUncompiledFiles { changedFile ->
            val isValidModule = moduleInfos[changedFile.module.name] != null ||
                changedFile.module.name == ModuleInfo.virtualModule.name
            if (!isValidModule) {
                return@remapUncompiledFiles null
            }
            val newModuleInfo = moduleInfos[changedFile.module.name] ?: ModuleInfo.virtualModule
            changedFile.copy(module = newModuleInfo)
        }
        val sourceDirs = moduleInfos.values.flatMap {
            it.sourceDirs
        }
        sourceFileManager.init(sourceDirs)
        if (isConstRefTasksEnabled) {
            constRefScheduler.initializeFullScan(sourceDirs)
        }
        deployDataGenerator.mappingFile = mappingFile
    }

    @Synchronized
    fun getRecompileFiles(isMinified: Boolean, isCompilingEffectedSourceFiles: Boolean, classObfuscator: ClassObfuscator?): RecompileFiles {
        logger.debug("getRecompileFiles")
        return compileEffectAnalyzer.getRecompileFiles(
            stagingFiles = stateTracker.getStagingFiles(),
            compiledFiles = stateTracker.getCompiledFiles(),
            moduleInfos = moduleInfos,
            isMinified = isMinified,
            isCompilingEffectedSourceFiles = isCompilingEffectedSourceFiles,
            classObfuscator = classObfuscator,
        )
    }

    @Synchronized
    fun getDesugarInfo(compileFiles: List<CompileFile>, moduleInfo: ModuleInfo, toDir: File, apkFile: File): DesugarInfo {
        return compileEffectAnalyzer.getDesugarInfo(
            compileFiles = compileFiles,
            moduleInfo = moduleInfo,
            moduleInfos = moduleInfos,
            toDir = toDir,
            apkFile = apkFile,
        )
    }

    fun isEnableDesugared(): Boolean {
        return deployDataGenerator.isEnableDesugared()
    }

    @Synchronized
    fun getMinifyInfo(): MinifyInfo? {
        return compileEffectAnalyzer.getMinifyInfo(
            stagingFiles = stateTracker.getStagingFiles(),
            moduleInfos = moduleInfos,
        )
    }

    fun dispose() {
        constRefScheduler.dispose()
    }
}
