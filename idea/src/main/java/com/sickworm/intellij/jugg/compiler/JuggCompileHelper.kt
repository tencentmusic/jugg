package com.sickworm.intellij.jugg.compiler

import com.google.gson.Gson
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
import com.sickworm.intellij.jugg.gradle.compile.*
import com.sickworm.intellij.jugg.ide.*
import com.sickworm.intellij.jugg.ide.ui.BuildChangesConfirmDialog
import com.sickworm.intellij.jugg.ide.ui.CommonConfirmDialog
import com.sickworm.intellij.jugg.ide.ui.SimpleProcessHandler
import com.sickworm.intellij.jugg.logger.JuggLogger
import com.sickworm.intellij.jugg.logger.TimeLogger
import com.sickworm.intellij.jugg.project.*
import com.sickworm.intellij.jugg.project.dependency.DependencyDiffResultSet
import com.sickworm.intellij.jugg.project.dependency.GradleProjectInfoLocalFetchManager
import com.sickworm.intellij.jugg.project.dependency.IDependencyChangeManager
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
    private fun doCompile(
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

        // decide gradle compile or incremental compile
        var incrementalResult: CompileTaskResult? = preprocessIncrementalCompile(options, processHandler, indicator, isForceInstall)
        val isGradleCompile = incrementalResult != null

        val startTime = System.currentTimeMillis()
        if (processHandler.isProcessTerminating || processHandler.isProcessTerminated) {
            return CompileTaskResult.incrementalCanceled(startTime)
        }

        if (!isGradleCompile) {
            deployHistoryManager.beforeIncrementalCompile(deployFileManager.getUndeployedFiles())

            val compileStatusHolder = JuggCompileStatusHolder(processHandler, indicator, logger)
            incrementalResult = incrementalCompile(compileStatusHolder)
            incrementalResult = incrementalResult.copy(costTime = System.currentTimeMillis() - startTime)
            juggServer.report {
                action = "incremental_compile"
                isSuccess = incrementalResult.isSuccess
                costTime = incrementalResult.costTime
                detail = incrementalResult.failedReason
            }

            if (processHandler.isProcessTerminating || processHandler.isProcessTerminated) {
                return CompileTaskResult.incrementalCanceled(startTime)
            }

            if (incrementalResult.isSuccess) {
                return incrementalResult
            } else if (!incrementalResult.isCanFallback && !(processHandler.isProcessTerminating || processHandler.isProcessTerminated)) {
                logger.warn("\nFound incremental compile error. Please see logs for details.")
                logger.warn("Run again directly will fall back to gradle compile.\n")
                return incrementalResult
            }
        }

        logger.debug("incremental compile not proceed. Will fall back to gradle compile.")
        if (!isForceInstall) {
            JuggRunningTask.notifyFallback(project, incrementalResult?.failedReason ?: "See log for details.")
        }

        val result = gradleCompile(options, processHandler, indicator)
        if (result.isSuccess) {
            JuggSettings.defaultCompileSettings = options.toRunConfigurationTemplate()
        }
        return CompileTaskResult(isSuccess = result.isSuccess,
            isGradleCompile = true,
            isCanFallback = false,
            costTime = System.currentTimeMillis() - startTime,
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
        compileContextManager.ensureInitProjectInfo()
        deployHistoryManager.beforeFullCompiled(deployFileManager.getUndeployedFiles())

        if (options.isRemoteCompile) {
            // remote build need run --dry-run -I readProjectInfo.gradle.kts at local
            if (!gradleProjectInfoLocalFetchManager.isProjectInfoExits) {
                // project info not fetched, run it during remote gradle compile
                // local compile will auto run after build finish
                gradleProjectInfoLocalFetchManager.runUpdateIfNeeded(isForce = true)
            } else {
                val changedBuildFiles = deployFileManager.getUndeployedFiles().filter {
                    it.type == CompileFile.Type.BuildFile
                }
                val lastBuildModifiedTime = changedBuildFiles.maxOfOrNull { it.file.lastModified() } ?: 0L
                logger.debug("Remote build changed files: ${changedBuildFiles.map { it.file.name }}")
                if (changedBuildFiles.isNotEmpty()) {
                    gradleProjectInfoLocalFetchManager.markIsNeedUpdate(true, lastBuildModifiedTime)
                    gradleProjectInfoLocalFetchManager.runUpdateIfNeeded(isForce = false)
                }
            }
        }

        gradleProjectInfoLocalFetchManager.writeInitGradleFile()
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

        return result
    }

    /**
     * Check file whether is rollback
     * We need to do it here because file may not change on disk when AsyncFileListener callback
     */
    private fun checkFilesRollback() {
        if (JuggSettings.isCheckChecksumWhenFileChanges) {
            val uncompiledFiles = deployFileManager.getUncompiledFiles()
            val changedBuildFile = uncompiledFiles.find {
                it.type == CompileFile.Type.BuildFile
            }

            val isFirstTimeDeploy = deployFileManager.getDeployedFiles().isEmpty()
            val changedOverlayFiles = uncompiledFiles.filter {
                it.type == CompileFile.Type.Resource || it.type == CompileFile.Type.Asset
            }

            // unnecessary to check if file size is small and no build file changed
            val isShouldCheck = uncompiledFiles.size > JuggSettings.sourceFileSizeToTriggerDetectRollback
                    || (changedBuildFile != null)
                    // some files may regenerate during gradle compile with same content, and it may trigger full overlay deployment
                    // here we check rollback for them
                    || (isFirstTimeDeploy && changedOverlayFiles.isNotEmpty())
            logger.debug("checkFilesRollback file size: ${uncompiledFiles.size}, " +
                    "changedBuildFile: ${changedBuildFile != null}, " +
                    "isFirstTimeDeploy: $isFirstTimeDeploy, changedOverlayFiles: ${changedOverlayFiles.size}, " +
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
    }

    private fun preprocessIncrementalCompile(
        options: JuggGradleCompileOptions,
        processHandler: SimpleProcessHandler,
        indicator: ProgressIndicator,
        isForceInstall: Boolean,
    ): CompileTaskResult? {
        val isNoFileChangesSinceLastCompile = deployFileManager.isNoFileChanges()
        val isLastGradleCompileFailed = deployHistoryManager.isLastFullCompileFailed
        logger.debug("preprocessIncrementalCompile isForceInstall $isForceInstall, isNoFileChangesSinceLastCompile: $isNoFileChangesSinceLastCompile")
        if (isForceInstall) {
            return CompileTaskResult.incrementalFailed(true, "force fallback")
        }

        checkDeviceFallback()?.let {
            return it
        }
        if (!isNoFileChangesSinceLastCompile && !isLastGradleCompileFailed) {
            checkFilesRollback()
        }
        checkFilesFallback(deployFileManager.getUncompiledFiles())?.let {
            return it
        }

        if (!isNoFileChangesSinceLastCompile && !isLastGradleCompileFailed) {
            checkLibraryIncrementalCompile(options, processHandler, indicator) // user may cancel in this step
        }

        val deployState = deployStateManager.updateDeployState()
        logger.debug("Try incremental compile. Current state: $deployState")
        if (!deployState.isReadyIncCompile) {
            logger.info("Deploy state ${deployStateManager.deployState} not ready for incremental compile. Return.")
            return CompileTaskResult.incrementalFailed(true, deployState.msg)
        }
        return null
    }

    /**
     * @return need fallback when result is not null
     */
    private fun checkDeviceFallback(): CompileTaskResult? {
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
        if (undeployedSourceModules.size > JuggSettings.maxCompileSourceModules) {
            logger.info("Compile modules too much(${undeployedSourceModules.size} modules), " +
                    "will fallback to gradle compile for better performance.")
            return CompileTaskResult.incrementalFailed(true, "Too many changes")
        } else if (undeployedSourceFiles.size > JuggSettings.maxCompileSourceFiles) {
            logger.info("Compile files too much(${undeployedSourceFiles.size} files), " +
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

    private fun checkLibraryIncrementalCompile(options: JuggGradleCompileOptions,
                                               processHandler: SimpleProcessHandler,
                                               indicator: ProgressIndicator,
    ) {
        val changedBuildFiles = deployFileManager.getUncompiledFiles().filter {
            it.type == CompileFile.Type.BuildFile
        }
        var isIncrementalCompileLibrary = dependencyChangeManager.changeStatus == IDependencyChangeManager.ChangeStatus.INCREMENTAL_COMPILE
        val isFallback = dependencyChangeManager.changeStatus == IDependencyChangeManager.ChangeStatus.REBUILD
        logger.debug("checkLibraryIncrementalCompile forceIncrementalCompile: $isIncrementalCompileLibrary")
        if (!isIncrementalCompileLibrary && !isFallback && JuggSettings.isEnableInjectGradleCompile && changedBuildFiles.isNotEmpty()) {
            val lastBuildFilesMap = deployHistoryManager.getLastBuildFiles(changedBuildFiles)
            val step1Result = BuildChangesConfirmDialog.showAndGetResult(project, lastBuildFilesMap.map { it.first.file to it.second })
            var step2Result = ConfirmResult.INVALID
            logger.debug("isConfirmIncrementalCompile: result $step1Result")
            if (step1Result == BuildChangesConfirmDialog.Result.FIND_CHANGE) {
                logger.info("Jugg: Start reading dependencies from Gradle...\n")
                JuggRunningTask.notifyByBalloon(project, "Start reading dependencies from Gradle...")
                val startTime = System.currentTimeMillis()
                val outputListener = GradleOutputParser(options, processHandler, indicator, logger)
                val runResult = runGradleLibraryDiff(options, outputListener)
                val costTime = (System.currentTimeMillis() - startTime) / 1000
                logger.info("\nJugg: Finish reading dependencies from Gradle, cost ${costTime}s.\n")
                step2Result = dependencyChangeManager.tryShowChangeConfirmDialog(runResult)

                if (step2Result == ConfirmResult.POSITIVE) {
                    compileContextManager.updateTempLibraries(
                        runResult?.diffResultWithFull?.newLibraryDependencies,
                        runResult?.diffResultWithFull?.oldLibraryDependencies,
                    )
                }
            } else if (step1Result == BuildChangesConfirmDialog.Result.IGNORE_CHANGE) {
                dependencyChangeManager.onConfirmIncrementalCompile(true)
            } else {
                dependencyChangeManager.onConfirmIncrementalCompile(false)
            }
            isIncrementalCompileLibrary = dependencyChangeManager.changeStatus == IDependencyChangeManager.ChangeStatus.INCREMENTAL_COMPILE

            juggServer.report {
                action = "check_dependency_incremental_compile"
                detail = Gson().toJson(mapOf(
                    "confirm_step_1" to step1Result.toString(),
                    "confirm_step_2" to step2Result.toString(),
                ))
            }

            if (step1Result == BuildChangesConfirmDialog.Result.CANCEL || step2Result == ConfirmResult.CANCEL) {
                processHandler.destroyProcess()
                return
            }
        }

        val isNeedRebuild = changedBuildFiles.isNotEmpty()
        if (isNeedRebuild && !isIncrementalCompileLibrary) {
            deployStateManager.isBuildFileChanged = true
            deployStateManager.whatBuildFileChanged = changedBuildFiles.firstOrNull()?.file?.name ?: "null"
            logger.info("${deployStateManager.whatBuildFileChanged} changed, need rebuild")
        } else {
            deployStateManager.isBuildFileChanged = false
            deployStateManager.whatBuildFileChanged = ""
        }
    }

    private fun runGradleLibraryDiff(options: JuggGradleCompileOptions, outputListener: GradleOutputParser): DependencyDiffResultSet? {
        gradleProjectInfoLocalFetchManager.writeInitGradleFile()
        val client = gradleCompileClientManager.getClient(options.isRemoteCompile, pathManager.localClasspathStoragePathManager.classpathDir)
        client.terminalOutputListener = outputListener
        client.login(options)

        val deployHistoryData = deployHistoryManager.getDeployHistoryData()
        val incDeployTimes = deployHistoryData?.incDeployTimes ?: 0
        logger.debug("incDeployTimes: $incDeployTimes")
        return client.fetchLibraryChanges(incDeployTimes)
    }

    @TestOnly
    fun incrementalCompile(compileStatusHolder: CompileStatusHolder): CompileTaskResult {

        val compiler = juggCompiler ?: run {
            logger.warn("Jugg compiler not init, may some error occurs. please see log for details")
            return CompileTaskResult.incrementalFailed(true, "Jugg compiler not init")
        }

        if (deployFileManager.isNoFileChanges() && !dependencyChangeManager.isNeedCompilation) {
            val deviceName = deployTargetManager.getDeviceNameList()
            if (juggRunningTaskStatusManager.isFirstTimeRun(deviceName)) {
                if (deployFileManager.getUncompiledFiles().isEmpty()) {
                    logger.info("No file changes, but it's first time run, deploy directly.")
                    return CompileTaskResult.incrementalSuccess()
                } else {
                    logger.info("No file changes, but it's last compilation not finished" +
                            ", will run with incremental compile.")
                }
            } else {
                logger.info("No file changes. will fallback to gradle compile.")

                val confirmResult =
                    if (!JuggSettings.isConfirmFallbackWhenNoFileChanges) {
                        ConfirmResult.POSITIVE
                    } else {
                        CommonConfirmDialog.showAndGetOrCancel(
                            title = "Confirm Fallback to Gradle",
                            content = "No file changes, do you want to fallback to gradle?",
                            okButtonText = "Fallback to Gradle",
                            negativeButtonText = "Don't fallback",
                            leftButtonText = "Cancel",
                            doNotAskAction = {
                                JuggSettings.isConfirmFallbackWhenNoFileChanges = false
                            }
                        )
                    }

                when (confirmResult) {
                    ConfirmResult.POSITIVE -> {
                        // fallback to gradle compile
                        return CompileTaskResult.incrementalFailed(true, "No file changes")
                    }
                    ConfirmResult.CANCEL, ConfirmResult.LEFT -> {
                        // just stop compile
                        compileStatusHolder.cancel()
                        return CompileTaskResult.incrementalFailed(false, "No file changes")
                    }
                    else -> {
                        // continue
                    }
                }
            }
        }

        // read all undeployed files
        val undeployedFiles = deployFileManager.getUndeployedFiles().toMutableList()
        // remove gradle files from undeployed files, it can not be compiled
        // since we go into this method, then it must be an incremental compile
        val buildFileFiles = undeployedFiles.filter { it.type == CompileFile.Type.BuildFile }
        undeployedFiles.removeAll(buildFileFiles)

        if (dependencyChangeManager.changeStatus == IDependencyChangeManager.ChangeStatus.INCREMENTAL_COMPILE) {
            // user select libraries incremental compile, add them to undeployed files
            val undeployedLibraries = dependencyChangeManager.getNewLibraryFiles()
            undeployedFiles.addAll(undeployedLibraries)
            logger.debug("Dependency changed, will recompile libraries: $undeployedLibraries")

            // mark gradle files as compiled, to detect isNoFileChanges()
            deployFileManager.updateUncompiledFiles(buildFileFiles.map {
                CompileFile(it.type, it.file, it.baseDir, it.module, it.extraInfo)
            }, emptyList())
        }

        return doIncrementalCompile(compiler, undeployedFiles, compileStatusHolder)
    }

    private fun doIncrementalCompile(
        compiler: JuggCompiler,
        undeployedFiles: List<ChangedFile>,
        compileStatusHolder: CompileStatusHolder,
        compiledFilesThisTime: List<ChangedFile> = emptyList(), // used for avoid recompilation dead loop
    ): CompileTaskResult {
        if (compileStatusHolder.isShouldCancel) {
            return CompileTaskResult.incrementalFailed(false, "Compile canceled")
        }

        val compileFiles = undeployedFiles.map {
            CompileFile(it.type, it.file, it.baseDir, it.module, it.extraInfo)
        }

        // do compile
        logger.debug("Compile files: ${compileFiles.map { it.file.absolutePath }}")
        logger.info("Compile files:\n${compileFiles.desc()}")
        val notifyText = if (compiledFilesThisTime.isEmpty()) {
            "Compiling ${compileFiles.size} files..."
        } else {
            "Detect effected sources, compiling ${compileFiles.size} files..."
        }
        JuggRunningTask.notifyByBalloon(project, notifyText)

        val startTime = System.currentTimeMillis()
        compileStatusHolder.setCompileFiles(compileFiles)
        val compileResult = try {
            compiler.compile(CompileTask(compileFiles, pathManager.stagingDir, compileStatusHolder))
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
            val isRecompilation = compiledFilesThisTime.isNotEmpty()
            val recompileFiles = deployFileManager.getRecompileFiles(isRecompilation)
            val effectedSourceFiles = recompileFiles.effectedSourceFiles

            val nextCompileFiles = mutableListOf<ChangedFile>()
            val changedFiles = fileChangesHandler.filter(effectedSourceFiles)

            TimeLogger.start("CheckEffectByTopLevelClass")
            logger.debug("CheckEffectByTopLevelClass compiledFilesThisTime: $compiledFilesThisTime, undeployedFiles: $undeployedFiles, recompileFiles: $recompileFiles")
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
                checkFilesFallback(unCompiledEffectedFiles)?.let {
                    return it
                }
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
                return doIncrementalCompile(compiler, nextCompileFiles.distinct(), compileStatusHolder, compiledFilesThisTime = undeployedFiles + compiledFilesThisTime)
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

        fun incrementalCanceled(startTime: Long) = CompileTaskResult(
            isSuccess = false,
            isGradleCompile = false,
            isCanFallback = false,
            costTime = System.currentTimeMillis() - startTime,
            failedReason = "Compile canceled",
            incrementalFailedReason = "Compile canceled",
        )
    }
}
