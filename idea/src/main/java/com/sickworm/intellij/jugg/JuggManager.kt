package com.sickworm.intellij.jugg

import com.intellij.ide.actions.RevealFileAction
import com.intellij.execution.Executor
import com.intellij.execution.ExecutionResult
import com.intellij.execution.configurations.RunProfile
import com.intellij.execution.RunManager
import com.intellij.execution.RunnerAndConfigurationSettings
import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.DumbProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindowManager
import com.sickworm.intellij.jugg.compiler.*
import com.sickworm.intellij.jugg.compiler.custom.CustomCompilerManager
import com.sickworm.intellij.jugg.deploy.*
import com.sickworm.intellij.jugg.deploy.instrument.AndroidTestRunSpec
import com.sickworm.intellij.jugg.deploy.run.*
import com.sickworm.intellij.jugg.ide.*
import com.sickworm.intellij.jugg.ide.bean.JuggGradleCompileOptions
import com.sickworm.intellij.jugg.ide.bean.JuggSettings
import com.sickworm.intellij.jugg.ai.skills.JuggCliAutoUpdater
import com.sickworm.intellij.jugg.ide.logic.*
import com.sickworm.intellij.jugg.ide.ui.CheckUpdateHandler
import com.sickworm.intellij.jugg.ide.ui.CheckUpdatesProgressDialog
import com.sickworm.intellij.jugg.ide.ui.CommonConfirmDialog
import com.sickworm.intellij.jugg.ide.ui.InstallJuggSkillsDialog
import com.sickworm.intellij.jugg.ide.ui.JuggControlPanelController
import com.sickworm.intellij.jugg.ide.ui.ReportIssueDialog
import com.sickworm.intellij.jugg.ide.ui.ReportIssueProgressDialog
import com.sickworm.intellij.jugg.ide.ui.ReportIssueResultDialog
import com.sickworm.intellij.jugg.ide.ui.RemoteCommandDialog
import com.sickworm.intellij.jugg.loader.JuggInitializer
import com.sickworm.intellij.jugg.logger.JuggLogger
import com.sickworm.intellij.jugg.logger.TimeLogger
import com.sickworm.intellij.jugg.logger.getInstance
import com.sickworm.intellij.jugg.ai.mcp.*
import com.sickworm.intellij.jugg.diagnostics.IssueReportBundleBuilder
import com.sickworm.intellij.jugg.diagnostics.IssueReportBundle
import com.sickworm.intellij.jugg.diagnostics.IssueReportUploader
import com.sickworm.intellij.jugg.git.GitManager
import com.sickworm.intellij.jugg.platform.PlatformApi
import com.sickworm.intellij.jugg.project.*
import com.sickworm.intellij.jugg.project.dependency.GradleProjectInfoLocalFetchManager
import com.sickworm.intellij.jugg.project.dependency.IDependencyChangeManager
import com.sickworm.intellij.jugg.project.dependency.create
import com.sickworm.intellij.jugg.server.JuggHotUpdateDownloader
import com.sickworm.intellij.jugg.server.JuggServer
import kotlinx.coroutines.*
import org.jetbrains.annotations.TestOnly
import java.io.File
import java.lang.Runnable
import javax.swing.JComponent
import javax.swing.SwingUtilities
import kotlin.system.measureTimeMillis


class JuggManager @TestOnly constructor(
    val project: Project,
    private val pathManager: JuggPathManager,
    private val coroutineScope: CoroutineScope = CoroutineScope(Dispatchers.IO),
    private val logger: Logger = JuggLogger.getInstance(project, "JuggManager"),
    private val juggServer: JuggServer = JuggServer(project.name, pathManager, coroutineScope, logger),
    private val juggHotUpdateDownloader: JuggHotUpdateDownloader = JuggHotUpdateDownloader(juggServer, logger),
    private val fileChangesHandler: IFileChangesHandler = FileChangesHandler(pathManager.projectDir, pathManager.juggRootDir, JuggLogger.getInstance(project, "FileChangesHandler")),
    private val fileChangesDetector: IFileChangesDetector = FileChangesDetector(project, pathManager.projectDir),
    private val deployHistoryManager: IDeployHistoryManager = DeployHistoryManager(pathManager, fileChangesHandler, JuggLogger.getInstance(project, "DeployHistoryManager")),
    private val deployTargetManager: IDeployTargetManager = DeployTargetManager(project),
    private val deployStateManager: DeployStateManager = DeployStateManager(project, deployTargetManager, deployHistoryManager),
    private val taskRunnerManager: TaskRunnerManager = TaskRunnerManager(project, logger, deployStateManager, juggServer, coroutineScope),
    private val customCompilerManager: CustomCompilerManager = CustomCompilerManager(pathManager.projectDir, pathManager.customCompilerDir, juggServer, logger),
    private val deployFileManager: DeployFileManager = DeployFileManager(pathManager,taskRunnerManager, JuggLogger.getInstance(project, "DeployFileManager")),
    private val compileContextManager: CompileContextManager = CompileContextManager(project, pathManager, deployFileManager, deployHistoryManager, customCompilerManager),
    private val juggRunningTaskStatusManager: IJuggRunningTaskStatusManager = JuggRunningTaskStatusManager(),
    private val dependencyChangeManager: IDependencyChangeManager = IDependencyChangeManager.create(JuggLogger.getInstance(project, "DependencyChangeManager")),
    private val gradleProjectInfoLocalFetchManager: GradleProjectInfoLocalFetchManager = GradleProjectInfoLocalFetchManager(project, pathManager, compileContextManager, taskRunnerManager, dependencyChangeManager, deployHistoryManager, logger),
    private val gitFileChangesDetector: GitFileChangesDetector = GitFileChangesDetector(deployHistoryManager, deployFileManager, taskRunnerManager, logger),
    private val juggDeployerHelper: JuggDeployerHelper = JuggDeployerHelper(
        project,
        deployTargetManager,
        deployFileManager,
        deployHistoryManager,
        deployStateManager,
        dependencyChangeManager,
        juggRunningTaskStatusManager,
        compileContextManager,
        juggServer,
        taskRunnerManager,
    ),
    private val juggCompilerHelper: JuggCompilerHelper = JuggCompilerHelper(project, pathManager, juggServer, deployTargetManager, deployStateManager, deployFileManager, deployHistoryManager, juggRunningTaskStatusManager, compileContextManager, fileChangesHandler, dependencyChangeManager, gradleProjectInfoLocalFetchManager, gitFileChangesDetector, taskRunnerManager),
    private val customConfigManager: CustomConfigManager = CustomConfigManager(pathManager.configDir, JuggLogger.getInstance(project, "CustomConfigManager")),
    private val ideSyncProblemResolver: IdeSyncProblemResolver = IdeSyncProblemResolver(project),
    ): IJuggManagerCaller, Disposable, CoroutineScope by coroutineScope {

    companion object {
        private const val MAX_RUN_CONFIG_RETRIES = 7
        private const val RUN_CONFIG_RETRY_BASE_DELAY_MS = 2000L
    }

    private val juggConfigurationRunner: JuggConfigurationRunner = JuggConfigurationRunner(project, pathManager,
        deployHistoryManager, juggRunningTaskStatusManager,
        JuggRunningTaskCreator(), gitFileChangesDetector,
        logger)
    private val forceGradleCompileHelper: ForceGradleCompileHelper = IdeaForceGradleCompileHelper(project, juggConfigurationRunner,
        deployFileManager, taskRunnerManager,
        compileContextManager, logger)
    private val controlPanelController = JuggControlPanelController(
        project = project,
        manager = this,
        deployTargetManager = deployTargetManager,
        deployHistoryManager = deployHistoryManager,
        deployFileManager = deployFileManager,
        logger = logger.getInstance("JuggControlPanelController"),
    )
    private val mcpInvoker: McpToolInvoker = McpToolInvoker(pathManager.projectDir.absolutePath,
        IdeaMcpRuntime(logger.getInstance("McpRuntime"), project, deployTargetManager, deployStateManager, forceGradleCompileHelper, juggConfigurationRunner, deployFileManager, juggCompilerHelper, gitFileChangesDetector),
        eventModel = controlPanelController.model,
    )
    private val copyGeneratedSourceHelper = CopyGeneratedSourceHelper(taskRunnerManager, logger)
    private val fileChangeLock = Any()
    private val runConfigurationLock = Any()

    constructor(
        project2: Project,
        pathManager: JuggPathManager,
    ): this(project = project2, pathManager)

    override fun init() {
        Disposer.register(this, juggCompilerHelper)
        Disposer.register(this, gradleProjectInfoLocalFetchManager)
        runTaskSafe("Init Jugg", {
            AsDeployerCompat.init(JuggLogger.getInstance(project, "AsDeployerCompat"))
            loadCustomConfig()
            tryCreateRunConfigurations(isSyncFinished = false)
            IAsDeployerCompat.updateMinApi(JuggSettings.finalIsEnableCompatibleDeploymentMode)
            ProjectInfoReader(project, logger.getInstance("ProjectInfoReader")).printInfo()
            deployHistoryManager.checkProjectDirChanged()
            clearLegacySystemJuggDir()
            logger.info("Start jugg finished.")

            // init project info async
            runTaskSafe("Init project info", ::recoverDeployContext)
            // init deployment service async
            JuggDeploymentService.preInit(logger)

            logger.debug("Checking updates...")
            juggServer.checkUpdate {
                val checkUpdateHandler = CheckUpdateHandler(
                    project, juggServer.version, customConfigManager,
                    JuggLogger.getInstance(project, "CheckUpdateHandler"),
                )
                checkUpdateHandler.handle(it)
                loadCustomConfig()
                juggHotUpdateDownloader.init(project)
            }

            taskRunnerManager.runBackgroundSafe("Auto update Jugg CLI", delayMs = 10_000) {
                JuggCliAutoUpdater.checkAndUpdate(logger.getInstance("JuggCliAutoUpdater"))
            }
            taskRunnerManager.runBackgroundSafe("Cleanup mcp fetch cache", delayMs = 120_000) {
                ExpiredArtifactCleaner.cleanupExpiredFiles(
                    pathManager.mcpFetchDir,
                    logger.getInstance("McpArtifactCleaner"),
                    retentionDays = 30,
                )
            }
            taskRunnerManager.runBackgroundSafe("Cleanup diagnostics cache", delayMs = 120_000) {
                ExpiredArtifactCleaner.cleanupExpiredFiles(
                    pathManager.diagnosticsDir,
                    logger.getInstance("DiagnosticsCleaner"),
                    retentionDays = 7,
                )
            }
        })
    }

    private fun loadCustomConfig() {
        try {
            if (!customConfigManager.isConfigChanged()) {
                return
            }
            customConfigManager.config?.let { config ->
                juggServer.updateServer(config.servers)
                fileChangesHandler.updateBuildFileRules(config.buildFileRules, config.moduleCustomConfigs?.map { it.moduleStdPath } ?: emptyList())
                deployHistoryManager.updateDontFilterIgnoredFileRules(config.dontFilterIgnoredFileRules)
                compileContextManager.updateCustomClasspath(config.moduleCustomConfigs ?: emptyList())
                customCompilerManager.updateCustomCompilers(config.customCompilers)
            }
        } catch (e: Exception) {
            // maybe structure is updated
            logger.info("loadCustomConfig failed", e)
        }
    }

    private fun updateProjectInfo(
        isAfterSync: Boolean,
        preferGradleLibraryDependencies: Boolean = false,
    ) {
        logger.debug("updateProjectInfo isAfterSync: $isAfterSync, " +
                "preferGradleLibraryDependencies: $preferGradleLibraryDependencies")

        if (isAfterSync) {
            // gradle sync finished, reset hasRun flag to avoid "No file changes" fallback
            juggRunningTaskStatusManager.resetHasRun()
        }

        // update project info if needed
        var isForceUpdateGradle = false
        val isUpdated = compileContextManager.updateCompileContext(
            isAfterSync,
            preferGradleLibraryDependencies,
        ) {
            isForceUpdateGradle = true
        }
        logger.debug("updateProjectInfo isUpdated: $isUpdated, isForceUpdateGradle: $isForceUpdateGradle")
        gradleProjectInfoLocalFetchManager.runUpdateIfNeeded(isForceUpdateGradle)

        // reinit compiler after update compile context
        if (isUpdated) {
            reInitOnCompileContextUpdate()
            dependencyChangeManager.onEndSyncing(isFromIde = true, true, compileContextManager.compileContext)
            if (!juggConfigurationRunner.isCompiling) {
                warmUpCompile()
                launch {
                    // do it async to let warmUpCompile run
                    dependencyChangeManager.tryShowChangeConfirmDialog(isRunCompileLater = true)
                }
            }
        }

        // check dependency again to avoid missing dependency(in ide little chance)
        if (isAfterSync) {
            taskRunnerManager.runBackgroundSafe("Check Project Info Delay", delayMs = 5000L) {
                updateProjectInfo(isAfterSync = false)
            }
        }
    }

    override fun onSyncEvent(syncEvent: SyncEvent) {
        logger.debug("onSyncEvent: $syncEvent")
        controlPanelController.recordSyncEvent(syncEvent)
        try {
            when (syncEvent) {
                SyncEvent.SUCCEEDED -> {
                    ideSyncProblemResolver.onIdeSyncSucceeded()
                    tryCreateRunConfigurations(isSyncFinished = true)
                    runTaskSafe("Update project info", { updateProjectInfo(isAfterSync = true) })
                }
                SyncEvent.SKIPPED -> {
                    tryCreateRunConfigurations(isSyncFinished = true)
                    runTaskSafe("Update project info", { updateProjectInfo(isAfterSync = false) })
                }
                SyncEvent.STARTED -> {
                    dependencyChangeManager.onStartSyncing(isFromIde = true)
                }
                SyncEvent.FAILED -> {
                    dependencyChangeManager.onEndSyncing(isFromIde = true, false, compileContextManager.compileContext)
                }
            }
        } catch (e: Throwable) {
            logger.warn("onSyncEvent failed: ", e)
        }
    }

    private fun tryCreateRunConfigurations(
        isSyncFinished: Boolean,
        maxRetryCount: Int = MAX_RUN_CONFIG_RETRIES,
    ): Unit = synchronized(runConfigurationLock) {
        TimeLogger.start("tryCreateDefaultRunConfiguration")
        val currentList = RunManager.getInstance(project).getConfigurationSettingsList(JuggConfigurationType::class.java)
        val currentListNames = currentList.map { it.name }
        logger.debug("JuggConfigurationType currentList: $currentListNames")

        val currentListNamesExceptDefault = currentList.filterNot {
            SuggestRunConfiguration.isDefaultRunConfigName(it.name)
        }
        if (currentListNamesExceptDefault.isNotEmpty() && !isSyncFinished) {
            logger.debug("Not sync finished and exits non-default configs is not empty, skip create default run configuration")
            return@synchronized
        }

        val suggestRunConfiguration =
            try {
                AsDeployerCompat.getSuggestRunConfigurations(
                    currentListNames, project,
                    logger.getInstance("GetSuggestRunConfigurations"),
                    isNeedDefaultRunConfig = false,
                )
            } catch (e: Throwable) {
                logger.warn("Get suggest run configuration failed ", e)
                if (TestModeManager.isTestMode) {
                    throw e
                }
                emptyList()
            }
        logger.debug("Suggest run configurations: $suggestRunConfiguration")
        if (suggestRunConfiguration.isEmpty()) {
            logger.debug("No suggest run configuration")
            if (isSyncFinished && maxRetryCount <= 0) {
                logger.debug("Could not create a run configuration after retries")
            }
            if (currentListNamesExceptDefault.isEmpty() && isSyncFinished && maxRetryCount > 0) {
                val attempt = MAX_RUN_CONFIG_RETRIES - maxRetryCount
                val delayMs = RUN_CONFIG_RETRY_BASE_DELAY_MS * (1L shl attempt)
                logger.debug("No current run configuration, retry #${attempt + 1} after ${delayMs}ms")
                launch {
                    delay(delayMs)
                    tryCreateRunConfigurations(isSyncFinished = true, maxRetryCount = maxRetryCount - 1)
                }
            }
            return@synchronized
        }

        val runManager = RunManager.getInstance(project)
        val settingsList = createRunConfigurations(runManager, currentList, suggestRunConfiguration)

        val settingsListExceptDefault = settingsList.filterNot {
            SuggestRunConfiguration.isDefaultRunConfigName(it.name)
        }
        if (currentListNamesExceptDefault.isEmpty() && settingsListExceptDefault.isNotEmpty()) {
            runManager.selectedConfiguration = settingsListExceptDefault[0]
        } else if (isSyncFinished) {
            trySelectActiveBuildVariantConfiguration(
                runManager,
                currentList + settingsList,
                suggestRunConfiguration,
            )
        }
        if (currentListNamesExceptDefault.isNotEmpty() || settingsListExceptDefault.isNotEmpty()) {
            SwingUtilities.invokeLater {
                if (!project.isDisposed) {
                    ToolWindowManager.getInstance(project)
                        .getToolWindow(JuggControlPanelHost.TOOL_WINDOW_ID)
                        ?.setAvailable(true)
                }
            }
        }
        TimeLogger.end("tryCreateDefaultRunConfiguration", logger)
    }

    /** Creates missing Jugg configurations while preserving existing configurations with the same compile command. */
    private fun createRunConfigurations(
        runManager: RunManager,
        currentList: List<RunnerAndConfigurationSettings>,
        suggestions: List<SuggestRunConfiguration>,
    ): List<RunnerAndConfigurationSettings> {
        val distinctSuggestions = suggestions.distinctBy { suggestionTargetIdentity(it.compileCommand) }
        val existingCommands = currentList.mapNotNull {
            (it.configuration as? JuggRunConfiguration)?.state?.compileCommand
        }
        val usedNames = currentList.map { it.name }.toMutableList()
        val settingsList = distinctSuggestions
            .filterNot { suggestion ->
                existingCommands.any { matchesCompileTarget(it, suggestion.compileCommand) }
            }
            .map { suggest ->
                val factory: ConfigurationFactory = JuggConfigurationType.getInstance().configurationFactories[0]
                val preferredName = if (SuggestRunConfiguration.isDefaultRunConfigName(suggest.runConfigName) ||
                    suggest.baseRunConfigName in usedNames
                ) {
                    suggest.runConfigName
                } else {
                    suggest.baseRunConfigName
                }
                val name = RunManager.suggestUniqueName(preferredName, usedNames)
                usedNames.add(name)
                runManager.createConfiguration(name, factory).also { settings ->
                    (settings.configuration as JuggRunConfiguration).state?.let {
                        it.compileCommand = suggest.compileCommand
                        it.outputApkName = suggest.outputApkPath
                        it.setDefaultRemoteOption(JuggSettings.defaultCompileSettings)
                    }
                    settings.isActivateToolWindowBeforeRun = false
                }
            }
        settingsList.forEach(runManager::addConfiguration)
        return settingsList
    }

    /** Selects the active variant only when the current selection is a Jugg configuration. */
    private fun trySelectActiveBuildVariantConfiguration(
        runManager: RunManager,
        availableSettings: List<RunnerAndConfigurationSettings>,
        suggestions: List<SuggestRunConfiguration>,
    ) {
        val selectedSettings = runManager.selectedConfiguration ?: return
        val selectedState = (selectedSettings.configuration as? JuggRunConfiguration)?.state ?: return
        val selectedModuleName = SuggestRunConfiguration.getModuleNameByRunConfigName(selectedSettings.name)
        val selectedCompileCommand = selectedState.compileCommand ?: return
        val activeVariant = suggestions.firstOrNull {
            it.moduleName == selectedModuleName &&
                suggestionGradleTask(it.compileCommand)?.let { task ->
                    task !in gradleTaskTokens(selectedCompileCommand)
                } == true
        } ?: return
        val activeSettings = availableSettings.firstOrNull {
            val compileCommand = (it.configuration as? JuggRunConfiguration)?.state?.compileCommand
                ?: return@firstOrNull false
            matchesCompileTarget(compileCommand, activeVariant.compileCommand)
        } ?: return
        logger.info("Active Build Variant changed, select ${activeSettings.name} configuration.")
        runManager.selectedConfiguration = activeSettings
    }

    private fun suggestionTargetIdentity(compileCommand: String): String {
        return suggestionGradleTask(compileCommand)?.let { "task:$it" }
            ?: "command:${compileCommand.trim()}"
    }

    private fun matchesCompileTarget(existingCommand: String, suggestionCommand: String): Boolean {
        val suggestedTask = suggestionGradleTask(suggestionCommand)
            ?: return existingCommand.trim() == suggestionCommand.trim()
        return suggestedTask in gradleTaskTokens(existingCommand)
    }

    private fun suggestionGradleTask(compileCommand: String): String? {
        return gradleTaskTokens(compileCommand).singleOrNull()
    }

    private fun gradleTaskTokens(compileCommand: String): Set<String> {
        val executableNames = setOf("gradle", "gradlew", "gradle.bat", "gradlew.bat")
        return compileCommand.split(Regex("\\s+"))
            .asSequence()
            .map { it.trim().trim('\'', '"') }
            .filter {
                it.isNotEmpty() &&
                    it.substringAfterLast('/').substringAfterLast('\\') !in executableNames &&
                    !it.startsWith("-") &&
                    !it.contains("=")
            }
            .map { it.trimStart(':') }
            .toSet()
    }

    @TestOnly
    fun recoverDeployContext() {
        logger.debug("Start recover deploy context")

        val deployContextRecoverInfo = deployHistoryManager.tryGetContextRecoverInfoFromDb(isOnInit = true)
        if (deployContextRecoverInfo == null) {
            logger.debug("Can not recover from deploy history, please run gradle compile first")
            return
        } else {
            logger.debug("Recover deploy context from history successfully:")
            logger.debug("$deployContextRecoverInfo")
        }

        // step 1: recover compile context
        initCompile(deployContextRecoverInfo.compileContextInfo, deployContextRecoverInfo.deployedFiles,
            startCompileTime = null
        )
        // step 2: recover deploy files
        logger.debug("Start recover deploy history...")
        deployTargetManager.setApks(deployContextRecoverInfo.compileContextInfo.apkInfos)
        // step 3: recover changed files
        processFileChanged(deployContextRecoverInfo.changedFiles, emptyList(), from = "recover")

        logger.debug("Deploy history recover successfully, no need full compile.")
    }

    fun updateDeployState(): JuggDeployState {
        val oldDeployState = deployStateManager.deployState
        val deployState = deployStateManager.updateDeployState()
        if (deployState == oldDeployState) {
            // won't do anything if deploy state is not changed
            return deployState
        }

        return deployState
    }

    private fun processFileChanged(
        changedFiles: List<File>,
        deletedFiles: List<File>,
        from: String, // recover / ide / git
    ) = synchronized(fileChangeLock) {
        logger.trace("[PERF] JuggManager.processFileChanged from=$from, changedSize=${changedFiles.size}, deletedSize=${deletedFiles.size}")
        // prints file changed info
        if (deletedFiles.isNotEmpty()) {
            // not strict rules, just print it out for debug
            val simpleFilterFiles = changedFiles.filter {
                !it.path.contains("build") &&
                        !it.path.contains(".idea") &&
                        !it.path.contains(".git") &&
                        it.name != ".DS_Store"
            }
            if (simpleFilterFiles.isNotEmpty()) {
                logger.debug("Detect file deleted: ${simpleFilterFiles.map { it.name }}")
            }
            deployFileManager.removeChangedFile(deletedFiles)
        }
        if (changedFiles.isNotEmpty()) {
            // not strict rules, just print it out for debug
            val simpleFilterFiles = changedFiles.filter {
                !it.path.contains("build") &&
                    !it.path.contains(".idea") &&
                    !it.path.contains(".git") &&
                    !it.path.contains(".gradle") &&
                    it.name != ".DS_Store"
            }
            if (simpleFilterFiles.isNotEmpty()) {
                logger.debug("Detect file changed (before filter): ${simpleFilterFiles.map { it.path }}")
            }
        }

        val realChangedFiles = fileChangesHandler.filter(changedFiles)
        if (realChangedFiles.isEmpty()) {
            controlPanelController.refresh()
            return
        }
        logger.debug("Detect file changed (size=${realChangedFiles.size}): ${realChangedFiles.map { it.file.name }}")

        deployFileManager.addChangedFile(realChangedFiles)

        val isBuildFileChanged = realChangedFiles.any { it.type == CompileFile.Type.BuildFile }
        if (isBuildFileChanged || from == "recover") {
            val allBuildFiles = deployFileManager.getUndeployedFiles()
                .filter { it.type == CompileFile.Type.BuildFile }
                .map { it.file }
            dependencyChangeManager.onUpdateChangedBuildFiles(allBuildFiles)
        }

        if (from == "ide") {
            gitFileChangesDetector.onSourceFileChanged(realChangedFiles)
        }

        if (JuggSettings.compileOnSave) {
            runTaskSafe("Compile Changes", ::compileChanges)
        }
        controlPanelController.refresh()
    }

    override fun runTask(options: JuggRunConfigurationOptions): ExecutionResult {
        return runTask(options, null, null, null)
    }

    override fun runTask(
        options: JuggRunConfigurationOptions,
        executor: Executor?,
        runProfile: RunProfile?,
        androidTestRunSpec: AndroidTestRunSpec?,
    ): ExecutionResult {
        val isJuggDebugRun = shouldForceRestartAppForDebugExecutor(
            executorId = executor?.id,
            hasAndroidTestRunSpec = androidTestRunSpec != null,
        )
        val debugSessionManager = if (isJuggDebugRun) {
            JuggDebugSessionManager(project, deployTargetManager)
        } else {
            null
        }
        lateinit var compileUiHandler: JuggCompileUiHandler
        compileUiHandler = JuggCompileUiHandler(project,
            isForceGradleCompile = ForceGradleCompileHelper.isForceGradleCompileNextTime,
            isRpcMode = false,
            options.toCompileOptions(pathManager),
            logger,
            isAlwaysRestartApp = isJuggDebugRun,
            isDebugRun = isJuggDebugRun,
            isGradleCacheRefreshRequested = ForceGradleCompileHelper.isGradleCacheRefreshNextTime,
            onEndListener = { runResult ->
                debugSessionManager?.attachAfterSuccessfulRun(runResult, compileUiHandler)
            },
        )
        return juggConfigurationRunner.runTask(options.toCompileOptions(pathManager), compileUiHandler, executor, runProfile, androidTestRunSpec)
    }

    @TestOnly
    fun compileChanges() {
        juggCompilerHelper.incrementalCompile(CompileUiHandler.DEFAULT)
    }

    @TestOnly
    fun initIncrementalCompileAfterFullBuild(startCompileTime: Long, options: JuggGradleCompileOptions) {
        JuggLogger.resetLatestCompileLog(project)
        juggServer.afterFullCompile()
        pathManager.stagingDir.deleteRecursively()
        compileContextManager.compileContext.tempCompileDir.deleteRecursively()

        val isRemoteCompile = options.isRemoteCompile
        logger.debug("Init compile after full build, isRemoteCompile=$isRemoteCompile")
        if (!isRemoteCompile) {
            compileContextManager.updateCompileContextAfterLocalFetch(options.buildTarget)
        } else {
            gradleProjectInfoLocalFetchManager.waitForRemoteInitUpdate()
        }

        var projectInfo = compileContextManager.getProjectInfo()

        if (isRemoteCompile || JuggSettings.isEnableBackupClasspath) {
            logger.info("Fetching classpath...")
            val backupProjectInfo = juggCompilerHelper.fetchClasspath(
                isRemoteCompile, projectInfo, taskRunnerManager.currentIndicator, coroutineScope)
            if (backupProjectInfo == null) {
                if (isRemoteCompile) {
                    logger.warn("Fetch classpath failed, unable to init incremental compile. Please check log for details.")
                    // unable to continue
                    return
                }
                logger.warn("Fetch backup classpath failed, use local classpath instead.")
            } else {
                projectInfo = backupProjectInfo
            }
        } else {
            logger.debug("Backup classpath is disabled, use local classpath instead.")
        }

        TimeLogger.start("reInitAfterFullCompiled")
        pathManager.compileRootDir.clearDir()
        val apkInfos = deployTargetManager.getApks()
        if (apkInfos.isEmpty()) {
            logger.warn("Init compile failed for no apk found")
            return
        }
        val compileContextInfo = deployHistoryManager.reInitAfterFullCompiled(
            FullBuildInfo(options.compileCommand, options.buildTarget, System.currentTimeMillis()),
            apkInfos,
            projectInfo.modules,
            startCompileTime,
        )
        TimeLogger.end("reInitAfterFullCompiled", logger)

        if (isRemoteCompile) {
            copyGeneratedSourceHelper.copy(projectInfo.modules)
        }
        initCompile(compileContextInfo, emptyList(),
            startCompileTime = startCompileTime,
        )

        // checks whether project info is missing(cleaned by gradle)
        if (ideSyncProblemResolver.isNeedSyncAfterBuild()) {
            // The APK and Gradle project info come from the same full build. IDE data is supplemental here.
            updateProjectInfo(true, preferGradleLibraryDependencies = true)
        } else {
            updateProjectInfo(false)
        }
    }

    override fun gradleCompile() {
        logger.debug("[action] gradleCompile")
        forceGradleCompileHelper.executeGradleCompile()
    }

    /** Runs a non-interactive command with the currently selected remote Jugg configuration. */
    fun runRemoteCommand() {
        val selectedSettings = RunManager.getInstance(project).selectedConfiguration
        val configuration = selectedSettings?.configuration as? JuggRunConfiguration
            ?: return showRemoteCommandWarning("Select a Jugg run configuration first.")
        val state = configuration.state
            ?: return showRemoteCommandWarning("The selected Jugg configuration is unavailable.")
        if (!state.isRemoteCompile) {
            return showRemoteCommandWarning("The selected Jugg configuration does not use remote compilation.")
        }
        if (state.remoteSshUser.isNullOrBlank() || state.remoteSshIp.isNullOrBlank() || state.remoteSshPort <= 0) {
            return showRemoteCommandWarning("Complete the SSH user, host, and port in the selected Jugg configuration.")
        }
        val (options, workingDirectory) = try {
            state.toCompileOptions(pathManager).let { it to it.remoteProjectPath }
        } catch (e: Exception) {
            logger.warn("Failed to prepare remote command configuration", e)
            return showRemoteCommandWarning(e.message ?: "Failed to resolve the remote project directory.")
        }
        val target = "${options.remoteSshUser}@${options.remoteSshIp}:${options.remoteSshPort}"
        val targetKey = "$target|$workingDirectory"
        val dialog = RemoteCommandDialog(
            project,
            selectedSettings.name,
            target,
            workingDirectory,
            JuggSettings.getRemoteCommandHistory(targetKey),
        )
        if (!dialog.showAndGet()) return
        val command = dialog.command()
        RemoteCommandRunner(project, logger).run(selectedSettings.name, options, command)
        JuggSettings.recordRemoteCommand(targetKey, command)
    }

    private fun showRemoteCommandWarning(message: String) {
        Messages.showWarningDialog(project, message, "Run Remote Command")
    }

    override fun restartApp() {
        controlPanelController.restartApp()
    }

    /** Starts a full Gradle build that clears app data before reinstalling the selected app. */
    override fun cleanAndReinstall() {
        forceGradleCompileHelper.executeGradleCompile(autoConfirm = true, useCleanAndReinstall = true)
    }

    /** Resets the Jugg cache through the existing maintenance flow. */
    override fun resetJuggCache() {
        logger.info("[options] cleanAndResetJugg")
        val confirmed = CommonConfirmDialog.showAndGetResult(
            "Confirm Clean and Reset Jugg",
            "<html>This will delete all cache files and reopen project.<br>Are you sure to continue?</html>"
        )
        if (!confirmed) return
        logger.info("cleanAndResetJugg confirmed, start delete all files")
        pathManager.juggRootDir.listFiles()?.forEach {
            try {
                it.deleteRecursively()
            } catch (e: Exception) {
                logger.warn("Cannot delete dir ${it.name}, skip", e)
            }
        }
        logger.info("cleanAndResetJugg delete finished, start reopen all projects")
        JuggInitializer.reopenAllProjectsAsync()
    }

    fun forceReInstallNextTime() {
        juggConfigurationRunner.forceReInstallNextTime()
    }

    /** Keeps the stable ClassLoader bridge compatible after the legacy menu was removed. */
    @Deprecated("for compatibility")
    override fun getMoreOptions(options: JuggRunConfigurationOptions): ActionGroup {
        return DefaultActionGroup()
    }

    override fun installSkills() {
        InstallJuggSkillsDialog.installJuggMcpAndSkills(project, pathManager.projectDir, taskRunnerManager, logger)
    }

    override fun checkUpdates() {
        val dialog = CheckUpdatesProgressDialog()
        taskRunnerManager.runBackgroundSafe("Check updates") {
            val hotUpdateData = juggHotUpdateDownloader.checkHotUpdate(isPositiveCheck = true)
            dialog.setHotUpdateData(hotUpdateData) {
                taskRunnerManager.runBackgroundSafe("Download updates") {
                    try {
                        juggHotUpdateDownloader.downloadAndInstallUpdate(hotUpdateData!!)
                        dialog.setResult(hotUpdateData.targetVersion, true, hotUpdateData.isNeedReinstall, null) {
                            if (hotUpdateData.isNeedReinstall) {
                                CoroutineScope(Dispatchers.IO).launch {
                                    ApplicationManager.getApplication().restart()
                                }
                            } else {
                                JuggInitializer.reopenAllProjectsAsync()
                            }
                        }
                    } catch (e: Exception) {
                        logger.warn("Download updates failed: ", e)
                        dialog.setResult(hotUpdateData!!.targetVersion,
                            isSuccess = false,
                            isNeedReinstall = false,
                            failedReason = e.toString(),
                            onConfirmReopenProject = null
                        )
                    }
                }
            }
        }
        dialog.show()
    }

    fun setCustomServerUrl() {
        logger.info("[options] setNewServerUrl")
        juggServer.setCustomServer()
    }

    fun markAsProjectSyncedAndReInitCompiler() {
        logger.info("[test options] markAsSyncedAndReInitCompiler")
        onSyncEvent(SyncEvent.SUCCEEDED)
    }

    /** Reuses the selected Jugg configuration outputs as a full-build baseline without rebuilding. */
    fun markAsGradleCompiledAndReInitCompiler() {
        val selected = RunManager.getInstance(project).selectedConfiguration
        val options = (selected?.configuration as? JuggRunConfiguration)?.state
        if (options == null) {
            Messages.showWarningDialog(
                project,
                "Select a Jugg run configuration first.",
                "Mark as Gradle Compiled"
            )
            return
        }
        logger.info("[test options] markAsGradleCompiledAndReInitCompiler")
        taskRunnerManager.runTaskSafe("Mark as Gradle Compiled", {
            dependencyChangeManager.onStartBuilding()
            val compileOptions = options.toCompileOptions(pathManager)
            val result = juggCompilerHelper.gradleCompile(
                compileOptions,
                JuggCompileUiHandler(project,
                    isForceGradleCompile = true, isRpcMode = false,
                    compileOptions, logger,
                    progressIndicator = taskRunnerManager.currentIndicator ?: DumbProgressIndicator.INSTANCE,
                ),
                isOnlyFetchResult = true,
            )
            dependencyChangeManager.onEndBuilding(result.isSuccess, result.isCanceled)
            if (!result.isSuccess) {
                logger.warn("gradleCompile(isOnlyFetchResult) failed, please check log for details.")
                return@runTaskSafe
            }
            initIncrementalCompileAfterFullBuild(System.currentTimeMillis(), compileOptions)
        })
    }

    override fun getJuggRunSettingsComponent(): IJuggRunSettingsComponent {
        try {
            val result = JuggRunSettingsComponent()
            logger.debug("getJuggRunSettingsComponent ok")
            return result
        } catch (e: LinkageError) {
            logger.warn("getJuggRunSettingsComponent failed: ", e)
            throw e
        }
    }

    override fun getJuggControlPanel(page: String): JComponent {
        return controlPanelController.getPanel(page)
    }

    override fun reportIssue() {
        val progressDialog = ReportIssueProgressDialog("Preparing diagnostics...")
        taskRunnerManager.runBackgroundSafe("Prepare issue report") {
            try {
                val logcatErrorLog = deployTargetManager.dumpErrorLogs()
                val compileSettings = JuggSettings.defaultCompileSettings
                val gitUserName = GitManager.createGitManagerAndTrySearchParent(pathManager.projectDir).userName
                val knownSecrets = setOfNotNull(
                    compileSettings.remoteSshPassword,
                    compileSettings.remoteSshUser,
                    compileSettings.remoteSshIp,
                    gitUserName,
                    System.getProperty("user.name"),
                )
                val builder = IssueReportBundleBuilder(
                    pathManager.diagnosticsDir,
                    pathManager.projectDir,
                    File(System.getProperty("user.home")),
                    logger.getInstance("IssueReportBundleBuilder"),
                )
                val logFiles = pathManager.logDir.listFiles().orEmpty()
                    .filter { it.isFile && !it.name.startsWith("compile_latest") && !it.name.endsWith(".lck") }
                    .sortedByDescending { it.lastModified() }
                    .take(10)
                val candidates = builder.prepare(
                    environment = mapOf(
                        "pluginVersion" to juggServer.version,
                        "ideVersion" to PlatformApi.getIdeVersion(),
                        "os" to System.getProperty("os.name"),
                        "jvm" to System.getProperty("java.version"),
                    ),
                    projectSummary = mapOf(
                        "moduleCount" to compileContextManager.compileContext.modules.size,
                    ),
                    logFiles = logFiles,
                    logcat = logcatErrorLog,
                    hookDebugLog = File(JuggGlobalPathManager.rootDir, "skills/hooks/jugg-hook-debug.log"),
                    knownSecrets = knownSecrets,
                )
                SwingUtilities.invokeLater {
                    progressDialog.close(DialogWrapper.OK_EXIT_CODE)
                    showReportIssueDialog(builder, candidates, IssueReportUploader.JUGG_REPORT_URL)
                }
            } catch (e: Throwable) {
                SwingUtilities.invokeLater {
                    progressDialog.close(DialogWrapper.CANCEL_EXIT_CODE)
                }
                throw e
            }
        }
        progressDialog.show()
    }

    private fun showReportIssueDialog(
        builder: IssueReportBundleBuilder,
        candidates: List<com.sickworm.intellij.jugg.diagnostics.IssueReportCandidate>,
        uploadUrl: String,
    ) {
        val dialog = ReportIssueDialog(candidates, uploadUrl)
        if (!dialog.showAndGet()) {
            return
        }
        taskRunnerManager.runBackgroundSafe("Create issue report") {
            val bundle = builder.build(dialog.selectedPaths)
            if (dialog.isSaveLocally) {
                SwingUtilities.invokeLater {
                    RevealFileAction.openFile(bundle.file)
                    ReportIssueResultDialog(null).show()
                }
            } else {
                uploadIssueReport(bundle, uploadUrl)
            }
        }
    }

    private fun uploadIssueReport(bundle: IssueReportBundle, uploadUrl: String) {
        SwingUtilities.invokeLater {
            val progressDialog = ReportIssueProgressDialog("Uploading logs...")
            taskRunnerManager.runBackgroundSafe("Upload issue report") {
                val uploadResult = IssueReportUploader().upload(bundle, uploadUrl)
                SwingUtilities.invokeLater {
                    progressDialog.close(DialogWrapper.OK_EXIT_CODE)
                    ReportIssueResultDialog(uploadResult) {
                        uploadIssueReport(bundle, uploadUrl)
                    }.show()
                }
            }
            progressDialog.show()
        }
    }

    override fun invokeMcp(request: McpJsonRpcRequest): McpJsonRpcResponse {
        return mcpInvoker.invokeMcp(request)
    }

    private fun reInitOnCompileContextUpdate() {
        deployFileManager.updateModuleInfos(compileContextManager.compileContext.modules, compileContextManager.compileContext.mappingFile)
        val juggCompiler = JuggCompiler(compileContextManager.compileContext, this)
        juggCompilerHelper.juggCompiler = juggCompiler
        fileChangesHandler.init(compileContextManager.compileContext)
        gitFileChangesDetector.init(pathManager.projectDir, compileContextManager.compileContext.modules)
        customCompilerManager.init(compileContextManager.compileContext, juggCompiler)
    }

    private fun initCompile(
        compileContextInfo: CompileContextInfo,
        deployedFiles: List<CompileOutput>,
        startCompileTime: Long?,
    ) {
        logger.info("Init compile...")

        deployStateManager.isBuildFileChanged = false

        var finalApkInfos = compileContextInfo.apkInfos
        logger.debug("hasEmbeddedApks: ${customConfigManager.hasEmbeddedApks()}")
        if (customConfigManager.hasEmbeddedApks()) {
            finalApkInfos = customConfigManager.fillApkInfosWithEmbeddedApks(finalApkInfos, pathManager.localClasspathStoragePathManager.embeddedApkDir)
        }

        val costTime = measureTimeMillis {
            compileContextManager.setCompileContext(compileContextInfo)
            deployFileManager.init(finalApkInfos, deployedFiles, startCompileTime)
            dependencyChangeManager.init(pathManager.projectInfosDir, compileContextManager.compileContext)
            reInitOnCompileContextUpdate()
        }
        logger.debug("Init compile cost ${costTime}ms")

        fileChangesDetector.startListen(object: FileChangesListener {
            override fun onFileChanges(changedFiles: List<File>, deletedFiles: List<File>) {
                // is on EDT thread, will be stuck when using lock
                logger.trace("[PERF] fileChangesDetector.onFileChanges callback, thread=${Thread.currentThread().name}, changedSize=${changedFiles.size}")
                // beginFileProcessing() must be called synchronously here to prevent compile
                // from starting before the async task below has a chance to run
                deployStateManager.beginFileProcessing()
                taskRunnerManager.runBackgroundSafe("Process file changed", isNeedLog = false) {
                    try {
                        processFileChanged(changedFiles, deletedFiles, from = "ide")
                    } finally {
                        deployStateManager.endFileProcessing()
                    }
                }
            }
        })
        gitFileChangesDetector.startListen(object: FileChangesListener {
            override fun onFileChanges(changedFiles: List<File>, deletedFiles: List<File>) {
                logger.trace("[PERF] gitFileChangesDetector.onFileChanges callback, thread=${Thread.currentThread().name}, changedSize=${changedFiles.size}")
                processFileChanged(changedFiles, deletedFiles, from = "git")
            }
        })

        logger.info("Jugg init complete, start listening file changes.")

        if (JuggSettings.isEnableWarmUp) {
            warmUpCompile()
        }
    }

    private fun warmUpCompile() {
        runTaskSafe("Warm Up Compile", {
            juggCompilerHelper.warmUp()
        })
    }

    private fun runTaskSafe(jobName: String, action: Runnable, isNeedShowIndicator: Boolean = true) {
        taskRunnerManager.runTaskSafe(jobName, action, isNeedShowIndicator)
    }

    override fun dispose() {
        logger.debug("project ${pathManager.projectDir} dispose")
        controlPanelController.clear()
        deployFileManager.dispose()
        coroutineScope.cancel()
    }

    private fun clearLegacySystemJuggDir(systemPath: File = File(PathManager.getSystemPath())) {
        val legacyDir = File(systemPath, "jugg")
        if (legacyDir.exists() && !legacyDir.deleteRecursively() && legacyDir.exists()) {
            throw IllegalStateException("Failed to delete legacy Jugg system dir: ${legacyDir.absolutePath}")
        }
    }

    private inner class JuggRunningTaskCreator : IJuggRunningTaskCreator {
        override fun createAndRun(
            options: JuggGradleCompileOptions,
            compileUiHandler: CompileUiHandler
        ): IJuggRunningTask {
            return createAndRun(options, compileUiHandler, androidTestRunSpec = null)
        }

        override fun createAndRun(
            options: JuggGradleCompileOptions,
            compileUiHandler: CompileUiHandler,
            androidTestRunSpec: AndroidTestRunSpec?,
        ): IJuggRunningTask {
            logger.debug("Create running task: ${options.toSafeString()}")

            val startCompileTime = System.currentTimeMillis()
            val initIncrementalCompileTask = task@{
                // do it async
                fun action() {
                    initIncrementalCompileAfterFullBuild(startCompileTime, options)
                }
                runTaskSafe("Init Incremental Compile", ::action)
            }
            val task = JuggRunningTask(options, project, juggServer, deployTargetManager, dependencyChangeManager,
                juggRunningTaskStatusManager, deployHistoryManager, juggCompilerHelper, juggDeployerHelper, initIncrementalCompileTask,
                compileUiHandler, controlPanelController.model, androidTestRunSpec,
                controlPanelController = controlPanelController,
            )

            // try reload custom config if changed
            loadCustomConfig()
            ProgressManager.getInstance().run(task)

            return task
        }

    }

}
