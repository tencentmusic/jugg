package com.sickworm.intellij.jugg.deploy

import com.sickworm.intellij.jugg.apk.ApkInfo
import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.jugg.project.change.ChangedFile
import com.sickworm.intellij.jugg.compiler.CompileFile
import com.sickworm.intellij.jugg.compiler.CompileOutput
import com.sickworm.intellij.jugg.compiler.DesugarInfo
import com.sickworm.intellij.jugg.compiler.toCompileOutput
import com.sickworm.intellij.jugg.compiler.constref.ConstRefAnalyzer
import com.sickworm.intellij.jugg.compiler.constref.ConstRefEngine
import com.sickworm.intellij.jugg.compiler.obfuscation.ClassObfuscator
import com.sickworm.intellij.jugg.compiler.obfuscation.MinifyInfo
import com.sickworm.intellij.jugg.project.info.ModuleInfo
import com.sickworm.intellij.jugg.deploy.data.ConstRefEffectProvider
import com.sickworm.intellij.jugg.deploy.data.ConstRefReadiness
import com.sickworm.intellij.jugg.deploy.data.DeployDataGenerator
import com.sickworm.intellij.jugg.deploy.data.ResourceApkGenerator
import com.sickworm.intellij.jugg.deploy.data.SourceFileManager
import com.sickworm.intellij.jugg.deploy.run.JuggDeployData
import com.sickworm.intellij.jugg.ide.bean.JuggSettings
import com.sickworm.intellij.jugg.logger.getInstance
import com.sickworm.intellij.jugg.project.runtime.JuggPathManager
import com.sickworm.intellij.jugg.project.runtime.TaskRunnerManager
import org.jetbrains.annotations.TestOnly
import java.io.File

/**
 * Manage runtime deploy file status and provides [JuggDeployData]
 */
class DeployFileManager(
    private val pathManager: JuggPathManager,
    private val taskRunnerManager: TaskRunnerManager,
    private val logger: Logger,
) {
    private val isConstRefTasksEnabled: Boolean
        get() = JuggSettings.isEnableConstRefTasks

    private val stateTracker = DeployFileStateTracker(logger.getInstance("DeployFileStateTracker"))

    /**
     * get source file by source file name in dex file
     */
    private val sourceFileManager = SourceFileManager(pathManager.projectDir, pathManager.databaseDir, logger.getInstance("SourceFileManager"))

    private val constRefEngine = ConstRefEngine(
        analyzer = ConstRefAnalyzer(logger.getInstance("ConstRefAnalyzer")),
        dbFile = pathManager.constRefSharedDbFile,
        repoFingerprintDbFile = pathManager.repoFingerprintDbFile,
        logger = logger.getInstance("ConstRefEngine"),
        taskRunnerManager = taskRunnerManager,
    )

    private val constRefEffectProvider = object : ConstRefEffectProvider {
        override fun ensureReadyForRecompile(changedSourcePaths: Collection<String>, timeoutMs: Long): ConstRefReadiness {
            if (!isConstRefTasksEnabled) {
                return ConstRefReadiness.READY
            }
            val readiness = constRefEngine.ensureReadyForRecompile(changedSourcePaths, timeoutMs)
            return ConstRefReadiness(
                isReady = readiness.isReady,
                unreadyPaths = readiness.unreadyPaths,
                pendingSourceDirs = readiness.pendingSourceDirs,
            )
        }

        override fun getEffectedFiles(changedSourcePaths: Collection<String>) =
            if (!isConstRefTasksEnabled) emptyList() else constRefEngine.getEffectedFiles(changedSourcePaths)
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
        stateTracker = stateTracker,
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

        taskRunnerManager.runBackgroundSafe("DeployFileManager#updateSourceFiles", isProjectWrite = true) {
            sourceFileManager.updateFiles(newFiles, emptyList())
        }
        files.filter {
            it.type == CompileFile.Type.Java || it.type == CompileFile.Type.Kotlin
        }.forEach {
            if (isConstRefTasksEnabled) {
                constRefEngine.onFileSaved(it.file.stdAbsPath)
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

        taskRunnerManager.runBackgroundSafe("DeployFileManager#removeSourceFiles", isProjectWrite = true) {
            sourceFileManager.updateFiles(emptyList(), files.filter { !it.exists() })
        }
        files.forEach {
            if (isConstRefTasksEnabled) {
                constRefEngine.onFileDeleted(it.stdAbsPath)
            }
        }
    }

    fun awaitConstRefAnalysis(filePaths: List<String>) {
        if (!isConstRefTasksEnabled) {
            return
        }
        val targetPaths = filePaths
            .map { File(it).stdAbsPath }
            .filter { it.endsWith(".java") || it.endsWith(".kt") }
            .distinct()
        if (targetPaths.isEmpty()) {
            return
        }
        logger.debug("Const-ref on-demand analysis start, " +
                "files=${targetPaths.map { File(it).name }}"
        )
        val startTime = System.currentTimeMillis()
        val readiness = constRefEngine.analyzeOnDemand(targetPaths)
        val costTime = System.currentTimeMillis() - startTime
        if (readiness.isReady) {
            if (costTime < 1_000) {
                logger.debug("Const-ref on-demand analysis finish, cost ${costTime}ms")
            } else {
                logger.info("Const-ref on-demand analysis finish, cost ${costTime}ms")
            }
        } else {
            logger.warn(
                "Const-ref on-demand analysis finish with unready files, " +
                    "unreadyPathCount=${readiness.unreadyPaths.size}, cost ${costTime}ms"
            )
        }
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
        return deployDataPlanner.buildDeployData(isWarmUp, isEnableCompatDeploy)
    }

    @Synchronized
    fun updateApks(apks: List<ApkInfo>) {
        val deployItems = stateTracker.getDeployedFiles().map { it.toDeployItem() }
        deployDataGenerator.init(apks, deployItems)
    }

    fun appendCompatDeployFiles(deployData: JuggDeployData): JuggDeployData {
        return deployDataPlanner.appendCompatDeployFiles(deployData, stateTracker.getNotStagingDeployedFiles())
    }

    @Synchronized
    fun clearResourceApkCache() {
        resourceApkGenerator.deleteResourceApk()
    }

    @Synchronized
    fun getDeployedFiles(): List<CompileOutput> {
        return stateTracker.getDeployedFiles()
    }

    @Synchronized
    fun commit(juggDeployData: JuggDeployData) {
        logger.trace("[PERF] DeployFileManager.commit entered, thread=${Thread.currentThread().name}")
        logger.debug("commit juggDeployData, staging file size: ${stateTracker.getStagingFiles().size}, " +
                "deployed file size: ${stateTracker.getDeployedFiles()}")
        val deployedChangedFiles = stateTracker.getUndeployedFiles().map { it.file }
        deployDataGenerator.commitDeployedData(juggDeployData)
        if (isConstRefTasksEnabled) {
            constRefEngine.acknowledgeEffectedFilesAfterDeployCommit()
        }
        stateTracker.commitAndClear { path ->
            logger.debug("remove gradle file: $path")
        }
        LastChangedDeployRegistry.INSTANCE.record(pathManager.projectDir.path, deployedChangedFiles)
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
        logger.debug("resetAfterReinstall start, staging file size: ${stateTracker.getStagingFiles().size}, " +
                "deployed file size: ${stateTracker.getDeployedFiles().size}")
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
            constRefEngine.initializeFullScan(sourceDirs)
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
    fun getMinifyInfo(compileFiles: List<CompileFile>): MinifyInfo? {
        val stagingFiles = compileFiles.mapNotNull { it.toCompileOutput() }
        return compileEffectAnalyzer.getMinifyInfo(
            stagingFiles = stagingFiles,
            moduleInfos = moduleInfos,
        )
    }

    fun dispose() {
        constRefEngine.dispose()
    }
}
