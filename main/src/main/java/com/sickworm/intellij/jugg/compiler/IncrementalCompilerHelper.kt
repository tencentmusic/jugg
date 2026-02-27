package com.sickworm.intellij.jugg.compiler

import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.jugg.compiler.obfuscation.ClassObfuscator
import com.sickworm.intellij.jugg.compiler.source.DexFileMerger
import com.sickworm.intellij.jugg.compiler.source.kotlin.KmModuleMergerForCompilation
import com.sickworm.intellij.jugg.deploy.DeployFileManager
import com.sickworm.intellij.jugg.deploy.IDeployStateManager
import com.sickworm.intellij.jugg.deploy.run.IdeDeployState
import com.sickworm.intellij.jugg.ide.bean.JuggSettings
import com.sickworm.intellij.jugg.logger.TimeLogger
import com.sickworm.intellij.jugg.logger.getInstance
import com.sickworm.intellij.jugg.project.ChangedFile
import com.sickworm.intellij.jugg.project.IFileChangesHandler
import com.sickworm.intellij.jugg.project.JuggPathManager
import java.io.File

/**
 * IncrementalCompilerHelper orchestrates one incremental compile loop, including retry/recompile decisions and deploy-state updates.
 * Collaboration: Delegates compilation to [JuggCompiler.compile], tracks staged outputs through [DeployFileManager], and resolves follow-up impacts via [IFileChangesHandler] and [IDependencyMissingResolver].
 * Data Contract: [compile] exits early when [CompileStatusHolder.isShouldCancel] is true, updates undeployed-file state on the first round, and only enters effect-detection retry logic after a successful compile round.
 */
class IncrementalCompilerHelper(
    private val compiler: JuggCompiler,
    private val pathManager: JuggPathManager,
    private val deployStateManager: IDeployStateManager,
    private val deployFileManager: DeployFileManager,
    private val fileChangesHandler: IFileChangesHandler,
    private val dependencyMissingResolver: IDependencyMissingResolver,
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

        if (isFirstRoundCompile) {
            val changedSourcePaths = undeployedFiles
                .filter { it.type == CompileFile.Type.Java || it.type == CompileFile.Type.Kotlin }
                .map { it.file.absolutePath }
            deployFileManager.awaitConstRefAnalysis(changedSourcePaths)
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
            val classObfuscator = compiler.context.mappingFile
                ?.takeIf { it.exists() }
                ?.let { ClassObfuscator.fromMappingFile(it) }
            val recompileFiles = deployFileManager.getRecompileFiles(compiler.context.isMinified, !compileLoopStatus.isFirstRoundCompile, classObfuscator)
            val effectedSourceFiles = recompileFiles.effectedSourceFiles

            val nextCompileFiles = mutableListOf<ChangedFile>()
            val changedFiles = fileChangesHandler.filter(effectedSourceFiles)

            TimeLogger.start("CheckEffectByTopLevelClass")
            logger.debug("CheckEffectByTopLevelClass" +
                    ", undeployedFiles: $undeployedFiles" +
                    ", effectedSourceFiles: $effectedSourceFiles" +
                    ", changedFiles: $changedFiles" +
                    ", compiledFilesThisTime: ${compileLoopStatus.compiledFilesThisTime.map { it.file }}"
            )

            compileLoopStatus.compiledFilesThisTime += undeployedFiles
            val compiledFilesThisTimeSet = compileLoopStatus.compiledFilesThisTime.map { it.file.absolutePath }.toSet()
            val undeployedFilesSet = undeployedFiles.map { it.file.absolutePath }.toSet()
            val unCompiledEffectedFiles = changedFiles.filter { changedFile ->
                if (compiledFilesThisTimeSet.contains(changedFile.file.absolutePath)) {
                    return@filter false
                }

                if (undeployedFilesSet.contains(changedFile.file.absolutePath)) {
                    // check whether the file has top level class changed.
                    // if so, it should be recompiled through it's in compiledFilesThisTimeSet
                    logger.debug("CheckEffectByTopLevelClass ${changedFile.file.name} is in compiledFilesThisTimeSet and effected, check recompile")
                    val kmModuleMerger = KmModuleMergerForCompilation(changedFile.module.buildPathInfo.kotlinClassPath)
                    kmModuleMerger.loadAndMerge()
                    val extensionClasses = kmModuleMerger.getExtensionClasses().toSet()
                    if (extensionClasses.isNotEmpty()) {
                        logger.debug("CheckEffectByTopLevelClass extensionClasses: $extensionClasses, effectNodes: ${recompileFiles.juggDeployData.effectedClassNodes}")
                        recompileFiles.juggDeployData.effectedClassNodes
                            .filter {
                                it.sourceFileName == changedFile.file.name
                            }.forEach {
                                it.effectedByClasses.forEach { effectedByClass ->
                                    if (extensionClasses.contains(effectedByClass)) {
                                        logger.debug("CheckEffectByTopLevelClass ${changedFile.file.name} is in compiledFilesThisTimeSet, but it's effected by top level class, force recompile")
                                        return@filter true
                                    }
                                }
                            }
                    }
                    logger.debug("${changedFile.file.name} is in compiledFilesThisTimeSet and effected, no need recompile")
                    return@filter false
                }
                return@filter true
            }
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
                val result = compile(nextCompileFiles.distinct(), uiHandler, compileStatusHolder, compileLoopStatus)
                if (compileStatusHolder.isShouldCancel) {
                    // revert file compile status, compile again next round
                    if (isFirstRoundCompile) {
                        deployFileManager.rollbackChangedFile(undeployedFiles)
                        deployFileManager.clearStagingFiles()
                    }
                    return CompileTaskResult.incrementalFailed(false, "Compile canceled")
                } else {
                    return result
                }
            }
        }

        if (!isSuccess && !compileLoopStatus.isRetry) {
            val isCanRetry = dependencyMissingResolver.resolve(compileResult)
            logger.debug("DependencyMissingResolver isCanRetry: $isCanRetry")
            if (isCanRetry) {
                logger.info("\nCompile failed, but try fixing dependency success, retry compile once.\n")
                val status = CompileLoopStatus().also {
                    it.isRetry = true
                }
                return compile(undeployedFiles, uiHandler, compileStatusHolder, status)
            }
        }

        return if (isSuccess) {
            CompileTaskResult.incrementalSuccess(compileResult)
        } else {
            CompileTaskResult.incrementalFailed(false, "Compile failed")
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
                val mergedDexFiles = doMergeDex(logger, dexFiles, dexOutputDir)
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
                logger.warn("Merge dex failed", e)
                logger.warn("Merge dex failed, reason: ${e.message}")
                return null
            }
        }

        private fun doMergeDex(logger: Logger, dexFiles: List<File>, outputDir: File): List<CompileOutput> {
            val dexMerger = DexFileMerger(logger)
            dexMerger.merge(dexFiles, outputDir)
            val mergedDexFiles = outputDir.listFiles()!!
                .filter { it.extension == "dex" }
                .map { CompileOutput(CompileOutput.Type.Dex, it, outputDir) }
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

    /**
     * CompileLoopStatus carries compiledFilesThisTime and isRetry.
     */
    class CompileLoopStatus(
        /** used for avoid recompilation dead loop */
        var compiledFilesThisTime: List<ChangedFile> = emptyList(),
        var isRetry: Boolean = false,
    ) {
        val isFirstRoundCompile get() = compiledFilesThisTime.isEmpty()
    }
}
