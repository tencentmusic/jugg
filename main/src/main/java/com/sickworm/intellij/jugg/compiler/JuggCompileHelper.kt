package com.sickworm.intellij.jugg.compiler

import com.intellij.openapi.Disposable
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.sickworm.intellij.jugg.apk.ApkReader
import com.sickworm.intellij.jugg.compiler.source.KmModuleMergerForCompilation
import com.sickworm.intellij.jugg.project.data.ModuleBuildPathInfo
import com.sickworm.intellij.jugg.deploy.*
import com.sickworm.intellij.jugg.deploy.run.IdeDeployState
import com.sickworm.intellij.jugg.gradle.compile.GradleCompileResult
import com.sickworm.intellij.jugg.gradle.compile.IGradleCompileClient
import com.sickworm.intellij.jugg.gradle.compile.LocalGradleCompileClient
import com.sickworm.intellij.jugg.gradle.compile.RemoteGradleCompileClient
import com.sickworm.intellij.jugg.ide.*
import com.sickworm.intellij.jugg.logger.JuggLogger
import com.sickworm.intellij.jugg.logger.TimeLogger
import com.sickworm.intellij.jugg.project.*
import com.sickworm.intellij.jugg.server.JuggServer
import com.sickworm.intellij.jugg.server.toRunConfigurationTemplate
import org.jetbrains.annotations.TestOnly
import java.io.File

class JuggCompilerHelper(
    private val project: Project,
    private val pathManager: JuggPathManager,
    private val juggServer: JuggServer,
    private val deployTargetManager: IDeployTargetManager,
    private val deployStateManager: DeployStateManager,
    private val deployFileManager: DeployFileManager,
    private val deployHistoryManager: IDeployHistoryManager,
    private val juggRunningTaskStatusManager: IJuggRunningTaskStatusManager,
    private val compileContextManager: CompileContextManager,
    private val fileChangesHandler: IFileChangesHandler,
    private val dependencyChangeManager: IDependencyChangeManager,
    private val gradleProjectInfoLocalFetchManager: GradleProjectInfoLocalFetchManager,
    private val logger: Logger = JuggLogger.getInstance(project, "JuggCompilerHelper"),
): Disposable {

    var juggCompiler: JuggCompiler? = null
        set(value) {
            field?.let {
                Disposer.dispose(it)
            }
            field = value
        }

    private val gradleCompileClientManager = GradleCompileClientManager(project).also {
        Disposer.register(this, it)
    }

    @Synchronized
    fun compile(
        options: JuggGradleCompileOptions,
        processHandler: SimpleProcessHandler,
        indicator: ProgressIndicator,
        isForceInstall: Boolean,
    ): CompileTaskResult {
        val result = doCompile(options, processHandler, indicator, isForceInstall)

        if (processHandler.isProcessTerminating || processHandler.isProcessTerminated) {
            logger.warn("Compile canceled.")
            return result.copy(
                isSuccess = false,
                isCanFallback = false,
                failedReason = "Compile canceled",
            )
        }
        return result
    }

    @Synchronized
    fun doCompile(
        options: JuggGradleCompileOptions,
        processHandler: SimpleProcessHandler,
        indicator: ProgressIndicator,
        isForceInstall: Boolean,
    ): CompileTaskResult {
        if (deployStateManager.isInitializingIncrementalCompile) {
            logger.info("Waiting Jugg initializing finish...")
            while (deployStateManager.isInitializingIncrementalCompile) {
                Thread.sleep(200)
            }
        }

        val statTime = System.currentTimeMillis()

        if (!isForceInstall) {
            checkFilesRollback(options, processHandler, indicator)
        }

        var incrementalResult: CompileTaskResult? = null
        if (!isForceInstall) {
            val loggerListener = IndicatorLoggerListener(indicator)
            JuggLogger.listenProjectLog(project, loggerListener)
            incrementalResult = incrementalCompile(processHandler)
            JuggLogger.stopListenProjectLog(project, loggerListener)
            incrementalResult = incrementalResult.copy(costTime = System.currentTimeMillis() - statTime)
            juggServer.report {
                action = "incremental_compile"
                isSuccess = incrementalResult.isSuccess
                costTime = incrementalResult.costTime
                detail = incrementalResult.failedReason
            }
            if (incrementalResult.isSuccess) {
                return incrementalResult
            } else if (!incrementalResult.isCanFallback && !(processHandler.isProcessTerminating || processHandler.isProcessTerminated)) {
                logger.warn("\nFound incremental compile error. Please see logs for details.")
                logger.warn("Run again directly will fall back to gradle compile.\n")
                return incrementalResult
            } else {
                logger.debug("incremental compile not proceed. Will fall back to gradle compile.")
                JuggRunningTask.notifyFallback(project, incrementalResult.failedReason ?: "See log for details.")
            }
        }

        if (processHandler.isProcessTerminating || processHandler.isProcessTerminated) {
            return CompileTaskResult(
                isSuccess = false,
                isGradleCompile = false,
                isCanFallback = false,
                costTime = System.currentTimeMillis() - statTime,
                failedReason = "Compile canceled",
                incrementalFailedReason = "Compile canceled",
            )
        }

        val result = gradleCompile(options, processHandler, indicator)
        if (result.isSuccess) {
            JuggSettings.defaultCompileSettings = options.toRunConfigurationTemplate()
        }
        return CompileTaskResult(isSuccess = result.isSuccess,
            isGradleCompile = true,
            isCanFallback = false,
            costTime = System.currentTimeMillis() - statTime,
            failedReason = result.failedReason,
            incrementalFailedReason = incrementalResult?.failedReason
        )
    }

    fun gradleCompile(
        options: JuggGradleCompileOptions,
        processHandler: SimpleProcessHandler,
        indicator: ProgressIndicator,
        isOnlyFetchResult: Boolean = false,
    ): GradleCompileResult {
        gradleProjectInfoLocalFetchManager.writeInitGradleFile()
        if (options.isRemoteCompile) {
            // remote build need run --dry-run -I readProjectInfo.gradle.kts at local
            gradleProjectInfoLocalFetchManager.runUpdateIfNeeded()
        } else {
            compileContextManager.ensureInitProjectInfo()
        }

        val client = gradleCompileClientManager.getClient(options.isRemoteCompile, pathManager.localClasspathStoragePathManager.classpathDir)
        val task = JuggGradleCompileTask(project, client, options, processHandler, indicator, isOnlyFetchResult)
        val result = task.run()
        if (result.isSuccess) {
            val apkFile = result.compileOutputFile
            val apkReader = ApkReader(apkFile, logger)
            val apkInfo = apkReader.getApkInfo()
            deployTargetManager.setApks(listOf(apkInfo))
            // reset expect overlay ids after gradle compilation, to avoid using old status if install failed
            deployHistoryManager.lastDeployOverlayIds = emptyMap()
        }

        if (!options.isRemoteCompile) {
            // local build will update project info by -I readProjectInfo.gradle.kts
            gradleProjectInfoLocalFetchManager.markIsNeedUpdate(false)
        }

        return result
    }

    /**
     * Check file whether is rollback
     * We need to do it here because file may not change on disk when AsyncFileListener callback
     */
    private fun checkFilesRollback(options: JuggGradleCompileOptions,
                                   processHandler: SimpleProcessHandler,
                                   indicator: ProgressIndicator,
                                   ) {
        if (JuggSettings.isCheckChecksumWhenFileChanges) {
            val uncompiledFiles = deployFileManager.getUncompiledFiles()
            val changedBuildFile = uncompiledFiles.find {
                it.type == CompileFile.Type.Gradle
            }
            // unnecessary to check if file size is small and no build file changed
            val isShouldCheck = uncompiledFiles.size > 20 || (changedBuildFile != null)
            logger.debug("checkFilesRollback file size: ${uncompiledFiles.size}, " +
                    "changedBuildFile: ${changedBuildFile != null}, " +
                    "isShouldCheck: $isShouldCheck")

            if (isShouldCheck) {
                try {
                    val startTime = System.currentTimeMillis()
                    if (uncompiledFiles.size > 100) {
                        logger.info("Checking files whether is really changed...")
                    }
                    val rollbackFiles = deployHistoryManager.filterUnchangedFiles(uncompiledFiles.map { it.file })
                    if (rollbackFiles.isNotEmpty()) {
                        logger.debug("Found ${rollbackFiles.size} files rollback, files: ${rollbackFiles.map { it.name }}")
                        deployFileManager.removeChangedFile(rollbackFiles)
                    }
                    val costTime = System.currentTimeMillis() - startTime

                    if (uncompiledFiles.size > 100) {
                        logger.info("Checking finished, cost ${costTime}ms. Rollback files ${rollbackFiles.size}.")
                    }
                } catch (e: Exception) {
                    logger.debug("Check files whether is really changed failed: ${e.message}", e)
                }
            }
        }

        var forceIncrementalCompile = dependencyChangeManager.changeStatus == IDependencyChangeManager.ChangeStatus.INCREMENTAL_COMPILE

        // we need to double-check because file may roll back to not changed
        val changedBuildFiles = deployFileManager.getUncompiledFiles().filter {
            it.type == CompileFile.Type.Gradle
        }
        if (!forceIncrementalCompile) {
            val outputListener = GradleOutputParser(options, processHandler, indicator, logger,)
            forceIncrementalCompile = checkDependencyIncrementalCompile(changedBuildFiles, outputListener)
        }

        val isNeedRebuild = changedBuildFiles.isNotEmpty()
        if (isNeedRebuild && !forceIncrementalCompile) {
            deployStateManager.isBuildFileChanged = true
            deployStateManager.whatBuildFileChanged = changedBuildFiles.firstOrNull()?.file?.name ?: "null"
            logger.info("${deployStateManager.whatBuildFileChanged} changed, need rebuild")
        } else {
            deployStateManager.isBuildFileChanged = false
            deployStateManager.whatBuildFileChanged = ""
        }
        val lastBuildModifiedTime = changedBuildFiles.maxOfOrNull { it.file.lastModified() } ?: 0L
        gradleProjectInfoLocalFetchManager.markIsNeedUpdate(isNeedRebuild, lastBuildModifiedTime)
    }

    private fun checkDependencyIncrementalCompile(
        changedBuildFiles: List<ChangedFile>,
        outputListener: IGradleCompileClient.TerminalOutputListener,
    ): Boolean {
        if (changedBuildFiles.isEmpty()) {
            return false
        }
        val (isFindOut, isIgnoreGradleChanges) = CommonConfirmDialog.showThreeButtonsAndGetResult(
            "Confirm Library Incremental compile",
            """<html>
            |<p>Changed files:
            |<ul>
            |${changedBuildFiles.joinToString("\n") { "<li><font color=\"#2ECC71\">${it.file.relativeTo(pathManager.projectDir).path}</font></li>" }}
            |</ul>
            |Choose <b>Find out</b> will try to get changed dependencies, which will take <b>30-60</b> seconds.<br>
            |Choose <b>Ignore</b> will ignore Gradle file changes.<br>
            |<font color="#EB984E"><b>Caution</b></font>: This may cause unexpected build result, Please check changes carefully.
            |<br> <br>
            |</p>
            |</html>
            """.trimMargin(),
            okButtonText = "Find out the Changed Libraries!",
            cancelButtonText = "Fallback to Gradle",
            leftButtonText = "Ignore Gradle Changes",
        )
        logger.debug("isConfirmIncrementalCompile: $isFindOut, isIgnoreGradleChanges: $isIgnoreGradleChanges")
        var isIncrementalCompile = false
        if (isFindOut) {
            logger.info("Jugg: Start reading dependencies from Gradle...\n")
            val startTime = System.currentTimeMillis()
            val result = gradleProjectInfoLocalFetchManager.runUpdateSynchronized(outputListener)
            val costTime = (System.currentTimeMillis() - startTime) / 1000
            logger.info("\nJugg: Finish reading dependencies from Gradle, cost ${costTime}s.\n")
            if (result) {
                dependencyChangeManager.tryShowChangeConfirmDialog(isAfterIdeSync = false)
            } else {
                JuggRunningTask.notifyFallback(project, "Update compile info failed")
            }

            isIncrementalCompile = dependencyChangeManager.changeStatus == IDependencyChangeManager.ChangeStatus.INCREMENTAL_COMPILE
        } else if (isIgnoreGradleChanges) {
            isIncrementalCompile = true
        }

        juggServer.report {
            action = "check_dependency_incremental_compile"
            detail = when {
                isFindOut && isIncrementalCompile -> "incremental_compile"
                isFindOut && !isIncrementalCompile -> "findout_fallback"
                isIgnoreGradleChanges -> "ignore_gradle_changes"
                else -> "fallback"
            }
        }
        return isIncrementalCompile
    }

    @TestOnly
    fun incrementalCompile(processHandler: SimpleProcessHandler): CompileTaskResult {
        val deployState = deployStateManager.updateDeployState()
        logger.debug("Try incremental compile. Current state: $deployState")

        if (!deployState.isReadyIncCompile) {
            logger.info("Deploy state ${deployStateManager.deployState} not ready for incremental compile. Return.")
            return CompileTaskResult.incrementalFailed(true, deployState.msg)
        }

        if (!deployState.isReadyDeploy) {
            if (deployState.ideDeployState.state == IdeDeployState.State.INVALID_DEVICE) {
                logger.info("Device not ready for incremental compile(${deployState.ideDeployState.message}). Return.")
                return CompileTaskResult.incrementalFailed(true, deployState.ideDeployState.message)
            }
        }

        val compiler = juggCompiler ?: run {
            logger.warn("Jugg compiler not init, may some error occurs. please see log for details")
            return CompileTaskResult.incrementalFailed(true, "Jugg compiler not init")
        }

        if (deployFileManager.isNoFileChanges() && !dependencyChangeManager.isNeedCompilation) {
            val deviceName = deployTargetManager.getDeviceNameList()
            if (juggRunningTaskStatusManager.isFirstTimeRun(deviceName)) {
                logger.info("No file changes, but it's first time run or last compilation not finished" +
                        ", will run with incremental compile.")
            } else {
                logger.info("No file changes. will fallback to gradle compile.")
                val isConfirmFallback = ConfirmFallbackDialog.showAndGetResult("No file changes, continue will fallback to gradle.", true)
                if (!isConfirmFallback) {
                    processHandler.detachProcess()
                }
                return CompileTaskResult.incrementalFailed(isConfirmFallback, "No file changes")
            }
        }

        // read all undeployed files
        val undeployedFiles = deployFileManager.getUndeployedFiles().toMutableList()
        // remove gradle files from undeployed files, it can not be compiled
        // since we go into this method, then it must be an incremental compile
        val gradleFiles = undeployedFiles.filter { it.type == CompileFile.Type.Gradle }
        undeployedFiles.removeAll(gradleFiles)

        if (dependencyChangeManager.changeStatus == IDependencyChangeManager.ChangeStatus.INCREMENTAL_COMPILE) {
            // user select libraries incremental compile, add them to undeployed files
            val undeployedLibraries = dependencyChangeManager.getNewLibraryFiles()
            undeployedFiles.addAll(undeployedLibraries)
            logger.debug("Dependency changed, will recompile libraries: $undeployedLibraries")

            // mark gradle files as compiled, to detect isNoFileChanges()
            deployFileManager.updateUncompiledFiles(gradleFiles.map {
                CompileFile(it.type, it.file, it.baseDir, it.module, it.extraInfo)
            }, emptyList())
        }

        return doIncrementalCompile(compiler, undeployedFiles, processHandler)
    }

    private fun doIncrementalCompile(
        compiler: JuggCompiler,
        undeployedFiles: List<ChangedFile>,
        processHandler: SimpleProcessHandler,
        compiledFilesThisTime: List<ChangedFile> = emptyList(), // used for avoid recompilation dead loop
    ): CompileTaskResult {
        if (processHandler.isProcessTerminating || processHandler.isProcessTerminated) {
            return CompileTaskResult.incrementalFailed(false, "Compile canceled")
        }

        val undeployedSourceFiles = undeployedFiles.filter {
            it.type == CompileFile.Type.Java || it.type == CompileFile.Type.Kotlin
        }
        val undeployedSourceModules = undeployedSourceFiles.map {
            it.module.name + "_" + it.type
        }.toSet()
        if (undeployedSourceModules.size > JuggSettings.maxCompileSourceModules) {
            logger.info("Compile modules too much(${undeployedSourceModules.size} modules), " +
                    "will fallback to gradle compile for better performance.")
            return CompileTaskResult.incrementalFailed(true, "Too many changes")
        } else if (undeployedSourceFiles.size > JuggSettings.maxCompileSourceFiles) {
            logger.info("Compile files too much(${undeployedSourceFiles.size} files), " +
                    "will fallback to gradle compile for better performance.")
            return CompileTaskResult.incrementalFailed(true, "Too many changes")
        }

        val compileFiles = undeployedFiles.map {
            CompileFile(it.type, it.file, it.baseDir, it.module, it.extraInfo)
        }

        // do compile
        logger.debug("Compile files: ${compileFiles.map { it.file.absolutePath }}")
        logger.info("Compile files:\n${compileFiles.desc()}")
        val startTime = System.currentTimeMillis()
        val compileResult = try {
            val isShouldCancelCallback = {
                processHandler.isProcessTerminating || processHandler.isProcessTerminated
            }
            compiler.compile(CompileTask(compileFiles, pathManager.stagingDir, isShouldCancelCallback))
        } catch (e: Exception) {
            logger.error("Compile unexpected error: ${e.message}", e)
            return CompileTaskResult.incrementalFailed(true, "Exception: $e")
        }

        // update file status
        val successFiles = compileResult.details.filter { it.isSuccess }.map { it.get() }
        val failedFiles = compileResult.details.filter { !it.isSuccess }.map { it.getFailure().file }
        deployFileManager.updateUncompiledFiles(successFiles, failedFiles)
        deployFileManager.addDeployFiles(compileResult.outputs)

        val failedStates = compileResult.failedFiles

        if (processHandler.isProcessTerminating || processHandler.isProcessTerminated) {
            return CompileTaskResult.incrementalFailed(false, "Compile canceled")
        }

        val costTime = System.currentTimeMillis() - startTime
        logger.info("Compile finished in ${costTime / 1000}s, " +
                "success: ${compileResult.successFiles.size}, " +
                "failure: ${compileResult.failedFiles.size}.")

        val isSuccess = failedStates.isEmpty()
        if (isSuccess) {
            val isRecompilation = compiledFilesThisTime.isNotEmpty()
            val recompileFiles = deployFileManager.getRecompileFiles(isRecompilation)
            val effectedSourceFiles = recompileFiles.effectedSourceFiles

            val nextCompileFiles = mutableListOf<ChangedFile>()
            val changedFiles = fileChangesHandler.filter(effectedSourceFiles)

            TimeLogger.start("CheckEffectByTopLevelClass")
            val compiledFilesThisTimeSet = compiledFilesThisTime.map { it.file.absolutePath }.toSet()
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
                nextCompileFiles.addAll(unCompiledEffectedFiles)
            }

            val redexClasses = recompileFiles.redexClasses.map {
                it.copy(module = compileContextManager.compileContext.tempModule)
            }
            if (redexClasses.isNotEmpty()) {
                logger.info("Compile success, but found classes that need to be redexed, continue compile. Classes: ${redexClasses.map { it.file.name }}")
                nextCompileFiles.addAll(redexClasses)
            }

            if (nextCompileFiles.isNotEmpty()) {
                return doIncrementalCompile(compiler, nextCompileFiles.distinct(), processHandler, compiledFilesThisTime = undeployedFiles + compiledFilesThisTime)
            }
        }

        return if (isSuccess) {
            CompileTaskResult.incrementalSuccess()
        } else {
            CompileTaskResult.incrementalFailed(false, "Compile failed")
        }
    }

    fun warmUp() {
        juggCompiler?.warmUp()
    }

    /**
     * Fetch classpath from gradle compile client.
     * @return classpath root dir
     */
    fun fetchClasspathResult(isRemote: Boolean, buildDirs: List<ModuleBuildPathInfo>): File? {
        return gradleCompileClientManager.getClient(isRemote, pathManager.localClasspathStoragePathManager.classpathDir).fetchClasspathResult(buildDirs)
    }

    override fun dispose() {
        juggCompiler?.dispose()
        juggCompiler = null
    }
}

private class GradleCompileClientManager(private val project: Project): Disposable {

    private var isCacheRemoteClient: Boolean? = null
    private var cacheClient: IGradleCompileClient? = null

    fun getClient(isRemote: Boolean, localClasspathStorageDir: File): IGradleCompileClient {
        val cacheClient = cacheClient
        val isCacheRemoteClient = isCacheRemoteClient

        return if (cacheClient != null && isCacheRemoteClient == isRemote) {
            cacheClient
        } else {
            cacheClient?.dispose()
            val newClient = if (isRemote) RemoteGradleCompileClient(project) else LocalGradleCompileClient(project, localClasspathStorageDir)
            Disposer.register(this, newClient)
            this.cacheClient = newClient
            this.isCacheRemoteClient = isRemote
            newClient
        }
    }

    override fun dispose() {
    }
}

data class CompileTaskResult(
    val isSuccess: Boolean,
    val isGradleCompile: Boolean,
    val isCanFallback: Boolean,
    val costTime: Long,
    val failedReason: String? = null,
    val incrementalFailedReason: String? = null,
) {
    companion object {

        fun incrementalSuccess() = CompileTaskResult(
            isSuccess = true,
            isGradleCompile = false,
            isCanFallback = false,
            costTime = 0,
        )

        fun incrementalFailed(isCanFallback: Boolean, failedReason: String) = CompileTaskResult(
            isSuccess = false,
            isGradleCompile = false,
            isCanFallback,
            costTime = 0,
            failedReason = failedReason,
            incrementalFailedReason = failedReason,
        )
    }
}
