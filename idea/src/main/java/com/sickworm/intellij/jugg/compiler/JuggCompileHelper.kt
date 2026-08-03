package com.sickworm.intellij.jugg.compiler

import com.google.gson.Gson
import com.intellij.openapi.Disposable
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.sickworm.intellij.jugg.apk.ApkInfoReader
import com.sickworm.intellij.jugg.ai.mcp.util.LastCompileTimestampRegistry
import com.sickworm.intellij.jugg.compiler.ui.BuildChangesConfirmResult
import com.sickworm.intellij.jugg.deploy.*
import com.sickworm.intellij.jugg.deploy.instrument.LibraryTestApkBuildHistory
import com.sickworm.intellij.jugg.deploy.run.IdeDeployState
import com.sickworm.intellij.jugg.gradle.compile.*
import com.sickworm.intellij.jugg.ide.bean.ConfirmResult
import com.sickworm.intellij.jugg.ide.bean.JuggGradleCompileOptions
import com.sickworm.intellij.jugg.ide.bean.JuggSettings
import com.sickworm.intellij.jugg.ide.bean.inferLibraryTestApkHistoryBuildVariant
import com.sickworm.intellij.jugg.ide.bean.requestedGradleTasks
import com.sickworm.intellij.jugg.ide.bean.withGradleCacheRefresh
import com.sickworm.intellij.jugg.ide.logic.JuggRunningTask
import com.sickworm.intellij.jugg.ide.ui.CommonConfirmDialog
import com.sickworm.intellij.jugg.logger.JuggLogger
import com.sickworm.intellij.jugg.project.*
import com.sickworm.intellij.jugg.project.GitFileChangesDetector
import com.sickworm.intellij.jugg.project.data.JuggProjectInfo
import com.sickworm.intellij.jugg.project.dependency.DependencyDiffResultSet
import com.sickworm.intellij.jugg.project.dependency.GradleProjectInfoLocalFetchManager
import com.sickworm.intellij.jugg.project.dependency.IDependencyChangeManager
import com.sickworm.intellij.jugg.server.JuggServer
import com.sickworm.intellij.jugg.server.toRunConfigurationTemplate
import kotlinx.coroutines.CoroutineScope
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
    gitFileChangesDetector: GitFileChangesDetector,
    taskRunnerManager: TaskRunnerManager,
    private val logger: Logger = JuggLogger.getInstance(project, "JuggCompilerHelper"),
    private val gitChangeChecker: GitChangesCompileChecker = GitChangesCompileChecker(
        gitFileChangesDetector,
        deployFileManager,
        taskRunnerManager,
        logger,
    ),
): Disposable, IIncrementalCompileFallbackChecker {
    companion object {
        private const val FILE_PROCESSING_WAIT_TIMEOUT_MS = 1_000L
    }

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

    private val dependencyMissingResolver = IncrementalCompileRetryResolverChain(
        listOf(
            GitChangesRetryResolver(gitFileChangesDetector, deployFileManager, logger),
            IncrementalCompileRetryResolver(compileContextManager, gradleProjectInfoLocalFetchManager, logger),
        )
    )

    /**
     * Checks whether incremental compile would fall back to Gradle at query time, without
     * performing any compile operations or triggering git scans.
     *
     * Returns the fallback reason when fallback is required, or null when incremental compile
     * can proceed.
     */
    override fun checkFallback(): String? {
        checkDeviceFallback()?.let { return it.failedReason }
        checkFilesFallback(deployFileManager.getUncompiledFiles())?.let { return it.failedReason }
        val deployState = deployStateManager.updateDeployState()
        if (!deployState.isReadyIncCompile) {
            return deployState.msg
        }
        return null
    }

    @Synchronized
    fun compile(
        options: JuggGradleCompileOptions,
        uiHandler: CompileUiHandler,
        isAndroidTestRun: Boolean = false,
    ): CompileTaskResult {
        // Record compile-tool invocation baseline for MCP status/hook gating.
        LastCompileTimestampRegistry.INSTANCE.recordNow(pathManager.projectDir.absolutePath)
        logger.trace("[PERF] JuggCompileHelper.compile entered, thread=${Thread.currentThread().name}")
        val result = doCompile(options, uiHandler, isAndroidTestRun)

        if (uiHandler.isCanceled) {
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
        uiHandler: CompileUiHandler,
        isAndroidTestRun: Boolean,
    ): CompileTaskResult {
        if (deployStateManager.isInitializingIncrementalCompile) {
            logger.info("Waiting Jugg initializing finish...")
            while (deployStateManager.isInitializingIncrementalCompile) {
                Thread.sleep(200)
            }
        }
        if (deployStateManager.hasPendingFileProcessing()) {
            logger.info("Waiting file processing finish...")
            val waitResult = deployStateManager.waitForPendingFileProcessing(FILE_PROCESSING_WAIT_TIMEOUT_MS)
            if (waitResult.isTimeout) {
                logger.debug(
                    "waitForPendingFileProcessing timeout, timeoutMs=$FILE_PROCESSING_WAIT_TIMEOUT_MS, " +
                        "pendingCount=${waitResult.pendingCount}, initialPendingCount=${waitResult.initialPendingCount}, " +
                        "waitedMs=${waitResult.waitedMs}, thread=${Thread.currentThread().name}"
                )
            }
            logger.trace("[PERF] waitForPendingFileProcessing done, cost=${waitResult.waitedMs}ms, thread=${Thread.currentThread().name}")
        }

        // decide gradle compile or incremental compile
        var incrementalResult: CompileTaskResult? = preprocessIncrementalCompile(options, uiHandler)
        val isGradleCompile = incrementalResult != null

        val startTime = System.currentTimeMillis()
        if (uiHandler.isCanceled) {
            return CompileTaskResult.incrementalCanceled(startTime)
        }

        if (!isGradleCompile) {
            deployHistoryManager.beforeIncrementalCompile(deployFileManager.getUndeployedFiles())

            incrementalResult = incrementalCompile(uiHandler, options.buildTarget, isAndroidTestRun)

            // Consume the async Git check only when it finished during compilation.
            val foundResult = gitChangeChecker.getAsyncResultIfCompleted()
            if (foundResult?.isFoundNewChangedFiles == true) {
                logger.info("Git check after compile, found ${foundResult.foundFilesSize} new file(s) after compile success, compile again.")
                incrementalResult = incrementalCompile(uiHandler, options.buildTarget, isAndroidTestRun)
            } else if (foundResult != null) {
                logger.debug("Git check after compile found no new changed files.")
            } else {
                logger.debug("Git check after compile but timeout.")
            }

            incrementalResult = incrementalResult.copy(costTime = System.currentTimeMillis() - startTime)
            juggServer.report {
                action = "incremental_compile"
                isSuccess = incrementalResult.isSuccess
                costTime = incrementalResult.costTime
                detail = incrementalResult.failedReason
            }

            if (uiHandler.isCanceled) {
                return CompileTaskResult.incrementalCanceled(startTime)
            }

            if (incrementalResult.isSuccess) {
                return incrementalResult
            } else if (!incrementalResult.isCanFallback && !(uiHandler.isCanceled)) {
                logger.warn("\nFound incremental compile error. Please see logs for details.")
                if (!uiHandler.isRpcMode) {
                    logger.warn("Run again directly will fall back to gradle compile.\n")
                }
                return incrementalResult
            }
        }

        logger.debug("incremental compile not proceed. Will fall back to gradle compile.")
        if (!uiHandler.isForceGradleCompile) {
            JuggRunningTask.notifyFallback(project, incrementalResult?.failedReason ?: "See log for details.")
        }

        val gradleOptions = if (uiHandler.isGradleCacheRefreshRequested) {
            options.withGradleCacheRefresh()
        } else {
            options
        }
        val result = gradleCompile(gradleOptions, uiHandler)
        if (result.isSuccess) {
            JuggSettings.defaultCompileSettings = options.toRunConfigurationTemplate()
        }
        return CompileTaskResult(isSuccess = result.isSuccess,
            isGradleCompile = true,
            isCanFallback = false,
            costTime = System.currentTimeMillis() - startTime,
            failedReason = result.failedReason,
            incrementalFailedReason = incrementalResult?.failedReason,
            errorLog = result.errorLog,
            hasFileChanges = incrementalResult?.hasFileChanges ?: true,
        )
    }

    fun gradleCompile(
        options: JuggGradleCompileOptions,
        uiHandler: CompileUiHandler,
        isOnlyFetchResult: Boolean = false,
    ): GradleCompileResult {
        compileContextManager.ensureInitProjectInfo()
        val effectiveOptions = withLibraryTestApkHistory(options)
        GradleWrapperRepairer(logger).repairIfNeeded(pathManager.projectDir, effectiveOptions.compileCommand)
        val isLocalBuildTargetChanged = !effectiveOptions.isRemoteCompile &&
                deployHistoryManager.isBuildTargetChanged(effectiveOptions)
        deployHistoryManager.beforeFullCompiled(deployFileManager.getUndeployedFiles())

        if (effectiveOptions.isRemoteCompile) {
            prepareRemoteProjectInfo(effectiveOptions)
        }

        GradleScriptWriter(pathManager, logger).writeInitGradleFile()
        val client = gradleCompileClientManager.getClient(
            effectiveOptions.isRemoteCompile,
            pathManager.localClasspathStoragePathManager.classpathDir,
        )
        val task = JuggGradleCompileTask(project, client, effectiveOptions, uiHandler, isOnlyFetchResult)
        val result = task.run()
        if (result.isSuccess) {
            val apkInfos = ApkInfoReader(logger).createApkInfo(result.compileOutputFile)
            deployTargetManager.setApks(apkInfos)
            // reset expect overlay ids after gradle compilation, to avoid using old status if install failed
            deployHistoryManager.lastDeployOverlayIds = emptyMap()
            if (isLocalBuildTargetChanged) {
                compileContextManager.updateCompileContextAfterLocalFetch(effectiveOptions.buildTarget)
            }
        }

        return result
    }

    private fun prepareRemoteProjectInfo(options: JuggGradleCompileOptions) {
        val isCompileCommandChanged = isCompileCommandChanged(options)
        if (!gradleProjectInfoLocalFetchManager.isProjectInfoAvailable || isCompileCommandChanged) {
            gradleProjectInfoLocalFetchManager.runUpdateIfNeeded(
                isForce = true,
                specificCompileCommand = options.compileCommand,
                buildTarget = options.buildTarget,
                shouldWaitForRemoteInit = true,
            )
            return
        }

        val changedBuildFiles = deployFileManager.getUndeployedFiles().filter {
            it.type == CompileFile.Type.BuildFile
        }
        logger.debug("Remote build changed files: ${changedBuildFiles.map { it.file.name }}")
        if (changedBuildFiles.isEmpty()) {
            return
        }
        val lastBuildModifiedTime = changedBuildFiles.maxOf { it.file.lastModified() }
        gradleProjectInfoLocalFetchManager.markIsNeedUpdate(true, lastBuildModifiedTime)
        gradleProjectInfoLocalFetchManager.runUpdateIfNeeded(
            isForce = false,
            specificCompileCommand = options.compileCommand,
            buildTarget = options.buildTarget,
            shouldWaitForRemoteInit = true,
        )
    }

    private fun withLibraryTestApkHistory(options: JuggGradleCompileOptions): JuggGradleCompileOptions {
        if (options.buildTarget != BuildTarget.ANDROID_TEST) {
            return options
        }
        val projectInfo = compileContextManager.getProjectInfo()
        val buildVariant = inferLibraryTestApkHistoryBuildVariant(options, projectInfo.modules)
            ?: return options
        val requestedTasks = options.requestedGradleTasks()
        val records = LibraryTestApkBuildHistory(pathManager.projectDir, logger = logger)
            .selectRecentForAndroidTest(
                modules = projectInfo.modules,
                buildVariant = buildVariant,
                requestedTasks = requestedTasks,
            )
        if (records.isEmpty()) {
            return options
        }
        val tasks = records.map { it.gradleTask }
        logger.info("Going to build recent library Test APKs: ${tasks.joinToString()}")
        return options.copy(
            libraryTestApkGradleTasks = tasks,
            libraryTestApkOutputPatterns = records.map { it.outputApkPattern },
        )
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
                        logger.debug("Found files rollback, size: ${rollbackFiles.size}, files: ${rollbackFiles.map { it.name }}")
                        deployFileManager.removeChangedFile(rollbackFiles)
                    } else {
                        logger.debug("No files rollback.")
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
        uiHandler: CompileUiHandler,
    ): CompileTaskResult? {
        var isNoFileChangesSinceLastCompile = deployFileManager.isNoFileChanges()
        val isLastGradleCompileFailed = deployHistoryManager.isLastFullCompileFailed
        logger.debug("preprocessIncrementalCompile isForceInstall ${uiHandler.isForceGradleCompile}, isNoFileChangesSinceLastCompile: $isNoFileChangesSinceLastCompile")

        // Always run git change detection asynchronously to avoid blocking compile flow.
        gitChangeChecker.checkUndetectedFilesAsync(deployFileManager.getUndeployedFiles())

        if (uiHandler.isForceGradleCompile) {
            return CompileTaskResult.incrementalFailed(true, "Force fallback")
        }

        // Build target switch (APP <-> ANDROID_TEST) requires a full Gradle compile to produce correct APKs.
        if (deployHistoryManager.isBuildTargetChanged(options)) {
            logger.info("Build target changed to ${options.buildTarget}, forcing Gradle full compile.")
            return CompileTaskResult.incrementalFailed(true, "Build target changed to ${options.buildTarget}")
        }

        if (isCompileCommandChanged(options)) {
            logger.info("Compile command changed, forcing Gradle full compile.")
            return CompileTaskResult.incrementalFailed(true, "Compile command changed")
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
            checkLibraryIncrementalCompile(options, uiHandler) // user may cancel in this step
        }

        val deployState = deployStateManager.updateDeployState()
        logger.debug("Try incremental compile. Current state: $deployState")
        if (!deployState.isReadyIncCompile) {
            logger.info("Deploy state ${deployStateManager.deployState} not ready for incremental compile. Return.")
            return CompileTaskResult.incrementalFailed(true, deployState.msg)
        }

        if (JuggSettings.isEmbeddedToApk) {
            val isStillNeedEmbedded = uiHandler.confirmEmbeddedToApk()
            if (isStillNeedEmbedded != ConfirmResult.POSITIVE) {
                JuggSettings.isEmbeddedToApk = false
            }
        }
        return null
    }

    private fun isCompileCommandChanged(options: JuggGradleCompileOptions): Boolean {
        val lastCompileCommand = deployHistoryManager.getFullBuildInfo()?.compileCommand
        return lastCompileCommand != null && lastCompileCommand != options.compileCommand
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

    private fun checkLibraryIncrementalCompile(options: JuggGradleCompileOptions, uiHandler: CompileUiHandler) {
        val changedBuildFiles = deployFileManager.getUncompiledFiles().filter {
            it.type == CompileFile.Type.BuildFile
        }
        var isIncrementalCompileLibrary = dependencyChangeManager.changeStatus == IDependencyChangeManager.ChangeStatus.INCREMENTAL_COMPILE
        val isFallback = dependencyChangeManager.changeStatus == IDependencyChangeManager.ChangeStatus.REBUILD
        logger.debug("checkLibraryIncrementalCompile forceIncrementalCompile: $isIncrementalCompileLibrary")
        if (!isIncrementalCompileLibrary && !isFallback && JuggSettings.isEnableInjectGradleCompile && changedBuildFiles.isNotEmpty()) {
            val lastBuildFilesMap = deployHistoryManager.getLastBuildFiles(changedBuildFiles)
            val step1Result = uiHandler.confirmBuildChanges(project, lastBuildFilesMap.map { it.first.file to it.second })
            var step2Result = ConfirmResult.INVALID
            logger.debug("isConfirmIncrementalCompile: result $step1Result")
            if (step1Result == BuildChangesConfirmResult.FIND_CHANGE) {
                logger.info("Jugg: Start reading dependencies from Gradle...\n")
                JuggRunningTask.notifyByBalloon(project, "Start reading dependencies from Gradle...")
                val startTime = System.currentTimeMillis()
                val runResult = runGradleLibraryDiff(options, uiHandler.createOutputParser())
                val costTime = (System.currentTimeMillis() - startTime) / 1000
                logger.info("\nJugg: Finish reading dependencies from Gradle, cost ${costTime}s.\n")
                step2Result = uiHandler.confirmDependencyChanges(dependencyChangeManager, runResult)

                if (step2Result == ConfirmResult.POSITIVE) {
                    compileContextManager.updateTempLibraries(
                        runResult?.diffResultWithFull?.newLibraryDependencies,
                        runResult?.diffResultWithFull?.oldLibraryDependencies,
                    )
                }
            } else if (step1Result == BuildChangesConfirmResult.IGNORE_CHANGE) {
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

            if (step1Result == BuildChangesConfirmResult.CANCEL || step2Result == ConfirmResult.CANCEL) {
                uiHandler.cancel()
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

    private fun runGradleLibraryDiff(options: JuggGradleCompileOptions, outputListener: IGradleCompileClient.TerminalOutputListener): DependencyDiffResultSet? {
        GradleScriptWriter(pathManager, logger).writeInitGradleFile()
        val client = gradleCompileClientManager.getClient(options.isRemoteCompile, pathManager.localClasspathStoragePathManager.classpathDir)
        client.terminalOutputListener = outputListener
        client.login(options)

        val deployHistoryData = deployHistoryManager.getDeployHistoryData()
        val incDeployTimes = deployHistoryData?.incDeployTimes ?: 0
        logger.debug("incDeployTimes: $incDeployTimes")
        return client.fetchLibraryChanges(incDeployTimes)
    }

    @TestOnly
    fun incrementalCompile(
        uiHandler: CompileUiHandler,
        buildTarget: BuildTarget = BuildTarget.APP,
        isAndroidTestRun: Boolean = false,
    ): CompileTaskResult {

        val compiler = juggCompiler ?: run {
            logger.warn("Jugg compiler not init, may some error occurs. please see log for details")
            return CompileTaskResult.incrementalFailed(true, "Jugg compiler not init")
        }

        if (deployFileManager.isNoFileChanges() && !dependencyChangeManager.isNeedCompilation) {
            val uncompiledFiles = deployFileManager.getUncompiledFiles()
            if (uncompiledFiles.isEmpty() && buildTarget == BuildTarget.ANDROID_TEST && isAndroidTestRun) {
                logger.info("No file changes for androidTest, but current run should deploy directly.")
                return CompileTaskResult.incrementalSuccess(
                    CompileResult.empty(uiHandler.createCompileStatusHolder()),
                ).copy(hasFileChanges = false)
            }

            val deviceName = deployTargetManager.getDeviceNameList()
            if (juggRunningTaskStatusManager.isFirstTimeRun(deviceName)) {
                if (uncompiledFiles.isEmpty()) {
                    logger.info("No file changes, but it's first time run, deploy directly.")
                    return CompileTaskResult.incrementalSuccess(CompileResult.empty(uiHandler.createCompileStatusHolder()))
                } else {
                    logger.info("No file changes, but last compilation not finished" +
                            ", will run with incremental compile.")
                }
            } else if (juggRunningTaskStatusManager.isProjectSwitchedThisRun) {
                if (uncompiledFiles.isEmpty()) {
                    logger.info("No file changes, but project switched since last run, deploy directly.")
                    return CompileTaskResult.incrementalSuccess(CompileResult.empty(uiHandler.createCompileStatusHolder()))
                } else {
                    logger.info("No file changes, but project switched since last run" +
                            ", will run with incremental compile.")
                }
            } else if (uiHandler.isDebugRun && uncompiledFiles.isEmpty()) {
                logger.info("No file changes for debug run, deploy directly.")
                return CompileTaskResult.incrementalSuccess(
                    CompileResult.empty(uiHandler.createCompileStatusHolder()),
                ).copy(hasFileChanges = false)
            } else {
                logger.info("No file changes. will fallback to gradle compile.")

                val confirmResult =
                    if (!JuggSettings.isConfirmFallbackWhenNoFileChanges) {
                        ConfirmResult.POSITIVE
                    } else {
                        val r = uiHandler.confirmFallbackWhenNoFileChanges()
                        r
                    }

                when (confirmResult) {
                    ConfirmResult.POSITIVE -> {
                        // fallback to gradle compile
                        return CompileTaskResult.incrementalFailed(true, "No file changes", hasFileChanges = false)
                    }
                    ConfirmResult.CANCEL, ConfirmResult.LEFT -> {
                        // just stop compile
                        uiHandler.cancel()
                        return CompileTaskResult.incrementalFailed(false, "No file changes", hasFileChanges = false)
                    }
                    else -> {
                        // continue
                    }
                }
            }
        }

        // read all undeployed files
        val allUndeployedFiles = deployFileManager.getUndeployedFiles()
        val missingUndeployedFiles = allUndeployedFiles.filter { !it.file.exists() }
        if (missingUndeployedFiles.isNotEmpty()) {
            logger.warn("Skipping missing source files (likely renamed or deleted): " +
                    missingUndeployedFiles.joinToString { it.file.path })
            deployFileManager.removeChangedFile(missingUndeployedFiles.map { it.file })
        }
        val undeployedFiles = allUndeployedFiles.filter { it.file.exists() }.toMutableList()
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

        val incrementalCompilerHelper = IncrementalCompilerHelper(
            compiler, pathManager, deployStateManager, deployFileManager, fileChangesHandler, dependencyMissingResolver, logger
        )
        return incrementalCompilerHelper.compile(undeployedFiles, uiHandler, uiHandler.createCompileStatusHolder())
    }

    fun warmUp() {
        juggCompiler?.warmUp()
    }

    /**
     * Fetch classpath from gradle compile client.
     */
    fun fetchClasspath(
        isRemote: Boolean,
        projectInfo: JuggProjectInfo,
        progressIndicator: ProgressIndicator?,
        coroutineScope: CoroutineScope,
    ): JuggProjectInfo? {
        val client = gradleCompileClientManager.getClient(isRemote, pathManager.localClasspathStoragePathManager.classpathDir)
        val classpathBackupHelper = ClasspathBackupHelper(client, progressIndicator, coroutineScope, logger)
        return classpathBackupHelper.fetch(projectInfo)
    }

    override fun dispose() {
        juggCompiler?.dispose()
        juggCompiler = null
    }
}

private class GradleCompileClientManager(private val project: Project): Disposable {

    private val logger = JuggLogger.getInstance(project, "GradleCompileClientManager")

    private var isCacheRemoteClient: Boolean? = null
    private var cacheClient: IGradleCompileClient? = null
    private var localClientEnvSignature: String? = null

    fun getClient(isRemote: Boolean, localClasspathStorageDir: File): IGradleCompileClient {
        val cacheClient = cacheClient
        val isCacheRemoteClient = isCacheRemoteClient
        val currentLocalEnv = if (isRemote) {
            null
        } else {
            LocalGradleCompileClient.buildCompileEnv(project, logger)
        }
        val currentLocalEnvSignature = currentLocalEnv?.joinToString(separator = "\n")
        val isNeedRecreateLocalClient =
            !isRemote && cacheClient != null && isCacheRemoteClient == false &&
                localClientEnvSignature != currentLocalEnvSignature

        return if (cacheClient != null && isCacheRemoteClient == isRemote && !isNeedRecreateLocalClient) {
            cacheClient
        } else {
            if (isNeedRecreateLocalClient) {
                logger.debug("Recreate LocalGradleCompileClient because compile env changed.")
            }
            cacheClient?.dispose()
            val newClient = if (isRemote)
                RemoteGradleCompileClient(project)
            else
                LocalGradleCompileClient(
                    File(project.basePath!!),
                    localClasspathStorageDir,
                    currentLocalEnv,
                    logger,
                )
            Disposer.register(this, newClient)
            this.cacheClient = newClient
            this.isCacheRemoteClient = isRemote
            this.localClientEnvSignature = currentLocalEnvSignature
            newClient
        }
    }

    override fun dispose() {
    }
}
