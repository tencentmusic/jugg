package com.sickworm.intellij.jugg.compiler

import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.jugg.compiler.obfuscation.ClassObfuscator
import com.sickworm.intellij.jugg.compiler.source.DexFileMerger
import com.sickworm.intellij.jugg.deploy.DeployFileManager
import com.sickworm.intellij.jugg.deploy.IDeployStateManager
import com.sickworm.intellij.jugg.deploy.run.IdeDeployState
import com.sickworm.intellij.jugg.ide.bean.JuggSettings
import com.sickworm.intellij.jugg.logger.TimeLogger
import com.sickworm.intellij.jugg.logger.getInstance
import com.sickworm.intellij.jugg.project.ChangedFile
import com.sickworm.intellij.jugg.project.IFileChangesHandler
import com.sickworm.intellij.jugg.project.JuggPathManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File

/**
 * IncrementalCompilerHelper orchestrates one incremental compile loop, including retry/recompile decisions and deploy-state updates.
 * Collaboration: Delegates compilation to [JuggCompiler.compile], tracks staged outputs through [DeployFileManager], and resolves follow-up impacts via [IFileChangesHandler] and [IIncrementalCompileRetryResolver].
 * Data Contract: [compile] exits early when [CompileStatusHolder.isShouldCancel] is true, updates undeployed-file state on the first round, and only enters effect-detection retry logic after a successful compile round.
 */
class IncrementalCompilerHelper(
    private val compiler: JuggCompiler,
    private val pathManager: JuggPathManager,
    private val deployStateManager: IDeployStateManager,
    private val deployFileManager: DeployFileManager,
    private val fileChangesHandler: IFileChangesHandler,
    private val retryResolver: IIncrementalCompileRetryResolver,
    loggerArg: Logger,
) {
    private val logger = loggerArg.getInstance("JuggCompilerHelper")

    fun compile(
        undeployedFiles: List<ChangedFile>,
        uiHandler: CompileUiHandler,
        compileStatusHolder: CompileStatusHolder,
        compileLoopStatus: CompileLoopStatus = CompileLoopStatus(),
    ): CompileTaskResult {
        val isFirstRoundCompile = compileLoopStatus.isFirstRoundCompile

        if (compileStatusHolder.isShouldCancel) {
            return CompileTaskResult.incrementalFailed(false, "Compile canceled")
        }

        val compileFiles = undeployedFiles.map {
            CompileFile(it.type, it.file, it.baseDir, it.module, it.extraInfo)
        }

        // do compile
        logger.debug("Compile files: ${compileFiles.map { it.file.absolutePath }}")
        logger.info("Compile files:\n${compileFiles.desc()}")
        val notifyText = if (compileLoopStatus.isFirstRoundCompile) {
            "Compiling ${compileFiles.size} files..."
        } else {
            "Detect effected sources, compiling ${compileFiles.size} files..."
        }
        uiHandler.notifyByBalloon(notifyText)

        val startTime = System.currentTimeMillis()
        compileStatusHolder.setCompileFiles(compileFiles)
        val compileResult = try {
            asyncCheckBeforeCompile(isFirstRoundCompile, undeployedFiles)
            compiler.compile(CompileTask(compileFiles, pathManager.stagingDir, compileStatusHolder))
        } catch (e: Exception) {
            logger.error("Compile unexpected error: ${e.message}", e)
            return CompileTaskResult.incrementalFailed(true, "Exception: $e")
        }

        // update file status
        if (isFirstRoundCompile) {
            val successFiles = compileResult.details.filter { it.isSuccess }.map { it.get() }
            val failedFiles = compileResult.details.filter { !it.isSuccess }.map { it.getFailure().file }
            deployFileManager.updateUncompiledFiles(successFiles, failedFiles)
        }
        deployFileManager.addStagingFiles(compileResult.outputs)

        val failedStates = compileResult.failedFiles

        if (compileStatusHolder.isShouldCancel) {
            return CompileTaskResult.incrementalFailed(false, "Compile canceled")
        }

        val costTime = System.currentTimeMillis() - startTime
        logger.info("Compile finished in ${costTime / 1000}s, " +
                "all: ${compileResult.details.size}, " +
                "success: ${compileResult.successFiles.size}, " +
                "failure: ${compileResult.compiledFailedFiles.size}.")

        val isSuccess = failedStates.isEmpty()
        if (isSuccess) {
            syncCheckAfterCompile(isFirstRoundCompile, undeployedFiles)
            val classObfuscator = compiler.context.mappingFile
                ?.takeIf { it.exists() }
                ?.let { ClassObfuscator.fromMappingFile(it) }
            logger.trace("[PERF] deployFileManager.getRecompileFiles start, thread=${Thread.currentThread().name}")
            val getRecompileStart = System.currentTimeMillis()
            val recompileFiles = deployFileManager.getRecompileFiles(compiler.context.isMinified, !compileLoopStatus.isFirstRoundCompile, classObfuscator)
            logger.trace("[PERF] deployFileManager.getRecompileFiles end, cost=${System.currentTimeMillis() - getRecompileStart}ms, thread=${Thread.currentThread().name}")
            val effectedSourceFiles = recompileFiles.effectedSourceFiles

            val nextCompileFiles = mutableListOf<ChangedFile>()
            val changedFiles = fileChangesHandler.filter(effectedSourceFiles)

            TimeLogger.start("CheckEffectByTopLevelClass")
            val lastRoundCompiledPaths = undeployedFiles.map { it.file.absolutePath }.toSet()
            val juggDeployData = recompileFiles.juggDeployData
            logger.debug("CheckEffectByTopLevelClass" +
                    ", undeployedFiles: $undeployedFiles" +
                    ", effectedSourceFiles: $effectedSourceFiles" +
                    ", changedFiles: $changedFiles" +
                    ", compiledFilesThisTime: ${compileLoopStatus.compiledFilesThisTime.map { it.file }}" +
                    ", lastRoundCompiledPaths: $lastRoundCompiledPaths" +
                    ", satisfiedEffectTriggerCount: ${compileLoopStatus.satisfiedEffectTriggers.size}"
            )

            compileLoopStatus.compiledFilesThisTime += undeployedFiles
            val unCompiledEffectedFiles = ContinueCompileEffectFilter.resolveUncompiledEffectedFiles(
                justCompiledFiles = undeployedFiles,
                changedFiles = changedFiles,
                lastRoundCompiledPaths = lastRoundCompiledPaths,
                satisfiedEffectTriggers = compileLoopStatus.satisfiedEffectTriggers,
                pendingEffectTriggerKeys = compileLoopStatus.pendingEffectTriggerKeys,
                juggDeployData = juggDeployData,
            )
            TimeLogger.end("CheckEffectByTopLevelClass", logger)

            if (unCompiledEffectedFiles.isNotEmpty()) {
                logger.info("Compile success, but found effected source files, continue compile. Files: ${unCompiledEffectedFiles.map { it.file.name }}")
                checkFilesFallback(unCompiledEffectedFiles)?.let {
                    return it
                }
                nextCompileFiles.addAll(unCompiledEffectedFiles)
            } else {
                logger.debug("Compile success, no effected source files found.")
            }

            val redexClasses = recompileFiles.redexClasses.map {
                it.copy(module = compiler.context.tempModule)
            }
            if (redexClasses.isNotEmpty()) {
                logger.info("Compile success, but found classes that need to be redexed, continue compile. Classes: ${redexClasses.map { it.file.name }}")
                nextCompileFiles.addAll(redexClasses)
            }

            if (nextCompileFiles.isNotEmpty()) {
                ContinueCompileEffectFilter.schedulePendingEffectTriggers(
                    unCompiledEffectedFiles,
                    juggDeployData,
                    compileLoopStatus.pendingEffectTriggerKeys,
                )
                val result = compile(nextCompileFiles.distinct(), uiHandler, compileStatusHolder, compileLoopStatus)
                if (compileStatusHolder.isShouldCancel) {
                    // revert file compile status, compile again next round
                    if (isFirstRoundCompile) {
                        deployFileManager.rollbackChangedFile(undeployedFiles)
                        deployFileManager.clearStagingFiles()
                    }
                    return CompileTaskResult.incrementalFailed(false, "Compile canceled")
                }
                return result
            }
        }

        if (!isSuccess && !compileLoopStatus.isRetry) {
            val isCanRetry = retryResolver.resolve(compileResult)
            logger.debug("retryResolver isCanRetry: $isCanRetry")
            if (isCanRetry) {
                logger.info("\nCompile failed, but try fixing success, retry compile once.\n")
                val status = CompileLoopStatus().also {
                    it.isRetry = true
                }
                return compile(undeployedFiles, uiHandler, compileStatusHolder, status)
            }
        }

        return if (isSuccess) {
            CompileTaskResult.incrementalSuccess(compileResult)
        } else {
            CompileTaskResult.incrementalFailed(false, "Compile failed", compileResult = compileResult)
        }
    }

    /**
     * @param dexOutputDir merged dex output directory, will clean first, so do not reuse
     * @return CompileResult with merged dex result and filter dex output before merge
     */
    fun mergeDex(compileResult: CompileResult, dexOutputDir: File): CompileResult? {
        return mergeDex(logger, compileResult, dexOutputDir)
    }

    companion object {
        /**
         * Shared dex merge entry for compile/deploy flows.
         */
        fun mergeDex(logger: Logger, compileResult: CompileResult, dexOutputDir: File): CompileResult? {
            try {
                val dexFiles = compileResult.outputs
                    .filter { it.type == CompileOutput.Type.Dex }
                    .map { it.file }
                if (dexFiles.isEmpty()) {
                    logger.debug("No need merge dex, no dex files")
                    return compileResult
                }

                dexOutputDir.deleteRecursively()
                dexOutputDir.mkdirs()
                val mergedDexFiles = doMergeDex(logger, compileResult.outputs.filter { it.type == CompileOutput.Type.Dex }, dexOutputDir)
                if (mergedDexFiles.isEmpty()) {
                    logger.warn("Merge dex failed, no dex files found")
                    return null
                }
                // filter out origin dex files, add merged dex files
                val mergedOutput = mergedDexFiles +
                        compileResult.outputs.filter { it.type != CompileOutput.Type.Dex  }
                val mergedIncrementalCompileResult = compileResult.copy(outputs = mergedOutput)
                return mergedIncrementalCompileResult
            } catch (e: Exception) {
                logger.debug("Merge dex failed", e)
                logger.warn("Merge dex failed, reason: ${e.message}")
                return null
            }
        }

        private fun doMergeDex(logger: Logger, dexOutputs: List<CompileOutput>, outputDir: File): List<CompileOutput> {
            val dexMerger = DexFileMerger(logger)
            val dexFiles = dexOutputs.map { it.file }
            dexMerger.merge(dexFiles, outputDir)
            val apkPath = dexOutputs.firstNotNullOfOrNull { it.apkPath }
            val targetApkPaths = dexOutputs.flatMap { it.targetApkPaths }.distinct()
            val mergedDexFiles = outputDir.listFiles()!!
                .filter { it.extension == "dex" }
                .map {
                    CompileOutput(
                        CompileOutput.Type.Dex,
                        it,
                        outputDir,
                        apkPath = apkPath,
                        targetApkPaths = targetApkPaths,
                    )
                }
            return mergedDexFiles
        }
    }

    /**
     * @return need fallback when result is not null
     */
    private fun checkFilesFallback(undeployedFiles: List<ChangedFile>): CompileTaskResult? {
        // too many changes fallback
        val undeployedSourceFiles = undeployedFiles.filter {
            it.type == CompileFile.Type.Java || it.type == CompileFile.Type.Kotlin
        }
        val undeployedSourceModules = undeployedSourceFiles.map {
            it.module.name + "_" + it.type
        }.toSet()

        val javaSourceFiles = undeployedSourceFiles.filter { it.type == CompileFile.Type.Java }
        val kotlinSourceFiles = undeployedSourceFiles.filter { it.type == CompileFile.Type.Kotlin }
        // see JuggSettings.maxCompileSourceFilePoints
        val undeployedSourceFilesPoints = javaSourceFiles.size * 2 + kotlinSourceFiles.size * 3
        logger.debug("javaSourceSize: ${javaSourceFiles.size}, kotlinSourceFiles ${kotlinSourceFiles.size}, undeployedSourceFilesPoints: $undeployedSourceFilesPoints")

        if (undeployedSourceModules.size > JuggSettings.maxCompileSourceModules) {
            logger.warn("Compile modules too much(${undeployedSourceModules.size} modules), " +
                    "will fallback to gradle compile for better performance.")
            return CompileTaskResult.incrementalFailed(true, "Too many changes")
        } else if (undeployedSourceFilesPoints > JuggSettings.maxCompileSourceFilePoints) {
            logger.warn("Compile files too much(${undeployedSourceFiles.size} files), " +
                    "will fallback to gradle compile for better performance.")
            return CompileTaskResult.incrementalFailed(true, "Too many changes")
        }

        // deploy state fallback
        val deployState = deployStateManager.updateDeployState()
        if (!deployState.isReadyDeploy) {
            if (deployState.ideDeployState.state == IdeDeployState.State.INVALID_DEVICE) {
                logger.info("Device not ready for incremental compile(${deployState.ideDeployState.message}). Return.")
                return CompileTaskResult.incrementalFailed(true, deployState.ideDeployState.message)
            }
        }

        return null
    }

    private fun asyncCheckBeforeCompile(isFirstRoundCompile: Boolean, undeployedFiles: List<ChangedFile>) {
        CoroutineScope(Dispatchers.IO).launch {
            syncCheckAfterCompile(isFirstRoundCompile, undeployedFiles)
        }
    }

    @Synchronized
    private fun syncCheckAfterCompile(isFirstRoundCompile: Boolean, undeployedFiles: List<ChangedFile>) {
        if (isFirstRoundCompile) {
            val changedSourcePaths = undeployedFiles
                .filter { it.type == CompileFile.Type.Java || it.type == CompileFile.Type.Kotlin }
                .map { it.file.absolutePath }
            deployFileManager.awaitConstRefAnalysis(changedSourcePaths)
        }
    }

    /**
     * CompileLoopStatus carries compiledFilesThisTime and isRetry.
     */
    class CompileLoopStatus(
        /** Accumulates all rounds in this compile session; continue-compile filtering uses last round only. */
        var compiledFilesThisTime: List<ChangedFile> = emptyList(),
        /** Effect trigger keys (effected source + trigger classes) already recompiled in this session. */
        val satisfiedEffectTriggers: MutableSet<String> = mutableSetOf(),
        /** Pending trigger keys scheduled by parent frame for nested continue-compile sources. */
        val pendingEffectTriggerKeys: MutableMap<String, String> = mutableMapOf(),
        var isRetry: Boolean = false,
    ) {
        val isFirstRoundCompile get() = compiledFilesThisTime.isEmpty()
    }
}
