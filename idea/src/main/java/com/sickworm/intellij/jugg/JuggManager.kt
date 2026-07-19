package com.sickworm.intellij.jugg

import com.intellij.ide.actions.RevealFileAction
import com.intellij.execution.Executor
import com.intellij.execution.ExecutionResult
import com.intellij.execution.configurations.RunProfile
import com.intellij.execution.RunManager
import com.intellij.execution.RunnerAndConfigurationSettings
import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindowManager
import com.sickworm.intellij.jugg.compiler.*
import com.sickworm.intellij.jugg.compiler.context.CompileContextManager
import com.sickworm.intellij.jugg.compiler.context.IdeaCompileEnvironmentSource
import com.sickworm.intellij.jugg.compiler.context.IdeaProjectModelSource
import com.sickworm.intellij.jugg.compiler.custom.CustomCompilerManager
import com.sickworm.intellij.jugg.deploy.*
import com.sickworm.intellij.jugg.deploy.cache.JuggDeploymentCacheStore
import com.sickworm.intellij.jugg.deploy.instrument.AndroidTestRunSpec
import com.sickworm.intellij.jugg.deploy.run.*
import com.sickworm.intellij.jugg.ide.*
import com.sickworm.intellij.jugg.ide.bean.JuggGradleCompileOptions
import com.sickworm.intellij.jugg.ide.bean.JuggSettings
import com.sickworm.intellij.jugg.ai.skills.JuggCliAutoUpdater
import com.sickworm.intellij.jugg.ide.logic.*
import com.sickworm.intellij.jugg.ide.ui.CheckUpdateHandler
import com.sickworm.intellij.jugg.ide.ui.InstallJuggSkillsDialog
import com.sickworm.intellij.jugg.ide.ui.JuggControlPanelController
import com.sickworm.intellij.jugg.ide.ui.ReportIssueDialog
import com.sickworm.intellij.jugg.ide.ui.ReportIssueProgressDialog
import com.sickworm.intellij.jugg.ide.ui.ReportIssueResultDialog
import com.sickworm.intellij.jugg.gradle.compile.CopyGeneratedSourceHelper
import com.sickworm.intellij.jugg.ide.ui.RemoteCommandDialog
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
import com.sickworm.intellij.jugg.project.change.FileChangeManager
import com.sickworm.intellij.jugg.project.change.FileChangeResult
import com.sickworm.intellij.jugg.project.change.FileChangeSource
import com.sickworm.intellij.jugg.project.change.FileChangesHandler
import com.sickworm.intellij.jugg.project.change.GitFileChangesDetector
import com.sickworm.intellij.jugg.project.change.IFileChangeMonitor
import com.sickworm.intellij.jugg.project.change.IFileChangesHandler
import com.sickworm.intellij.jugg.project.change.IdeaFileChangeMonitor
import com.sickworm.intellij.jugg.project.dependency.GradleProjectInfoLocalFetchManager
import com.sickworm.intellij.jugg.project.dependency.IDependencyChangeManager
import com.sickworm.intellij.jugg.project.dependency.create
import com.sickworm.intellij.jugg.project.info.ProjectInfoReader
import com.sickworm.intellij.jugg.project.runtime.JuggGlobalPathManager
import com.sickworm.intellij.jugg.project.runtime.JuggPathManager
import com.sickworm.intellij.jugg.project.runtime.ProjectCustomConfigManager
import com.sickworm.intellij.jugg.project.runtime.TaskRunnerManager
import com.sickworm.intellij.jugg.project.runtime.migrateLegacyJuggSettings
import com.sickworm.intellij.jugg.server.JuggHotUpdateDownloader
import com.sickworm.intellij.jugg.server.JuggServer
import com.sickworm.intellij.jugg.runtime.HostTaskExecutor
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
    private val fileChangesHandler: IFileChangesHandler = FileChangesHandler(pathManager.projectDir, pathManager.juggRootDir, JuggLogger.getInstance(project, "FileChangesHandler")),
    private val fileChangesDetector: IFileChangeMonitor = IdeaFileChangeMonitor(project, pathManager.projectDir),
    private val deployHistoryManager: IDeployHistoryManager = DeployHistoryManager(pathManager, fileChangesHandler, JuggLogger.getInstance(project, "DeployHistoryManager")),
    private val deployTargetManager: IDeployTargetManager = DeployTargetManager(project),
    private val deployStateManager: IDeployStateManager = DeployStateManager(deployTargetManager, deployHistoryManager, IdeaHostDeployStateResolver(project), JuggLogger.getInstance(project, "DeployStateManager")),
    private val hostTaskExecutor: HostTaskExecutor = HostTaskExecutor(project),
    private val taskRunnerManager: TaskRunnerManager = TaskRunnerManager(logger, deployStateManager, juggServer, hostTaskExecutor, pathManager, "idea", juggServer.version, coroutineScope),
    private val juggHotUpdateDownloader: JuggHotUpdateDownloader = JuggHotUpdateDownloader(juggServer, taskRunnerManager, logger),
    private val deploymentService: JuggDeploymentService = JuggDeploymentService(pathManager, JuggDeploymentCacheStore(pathManager.deploymentCacheDbFile, taskRunnerManager)),
    private val customCompilerManager: CustomCompilerManager = CustomCompilerManager(pathManager.projectDir, pathManager.customCompilerDir, juggServer, logger),
    private val deployFileManager: DeployFileManager = DeployFileManager(pathManager, taskRunnerManager, JuggLogger.getInstance(project, "DeployFileManager")),
    private val compileEnvironmentSource: IdeaCompileEnvironmentSource = IdeaCompileEnvironmentSource(project),
    private val projectModelSource: IdeaProjectModelSource = IdeaProjectModelSource(project, pathManager, logger = JuggLogger.getInstance(project, "IdeaProjectModelSource")),
    private val compileContextManager: CompileContextManager = CompileContextManager(pathManager, projectModelSource, deployFileManager, deployHistoryManager, customCompilerManager, compileEnvironmentSource, ICompileContext.Scene.IDE, JuggLogger.getInstance(project, "CompileContextManager")),
    private val juggRunningTaskStatusManager: IJuggRunningTaskStatusManager = JuggRunningTaskStatusManager(),
    private val dependencyChangeManager: IDependencyChangeManager = IDependencyChangeManager.create(JuggLogger.getInstance(project, "DependencyChangeManager")),
    private val gradleProjectInfoLocalFetchManager: GradleProjectInfoLocalFetchManager = GradleProjectInfoLocalFetchManager(pathManager, compileContextManager, taskRunnerManager, dependencyChangeManager, deployHistoryManager, compileEnvironmentSource, logger),
    private val gitFileChangesDetector: GitFileChangesDetector = GitFileChangesDetector(deployHistoryManager, deployFileManager, taskRunnerManager, logger),
    private val fileChangeManager: FileChangeManager = FileChangeManager(fileChangesHandler, deployFileManager, dependencyChangeManager, gitFileChangesDetector, deployStateManager, taskRunnerManager, JuggLogger.getInstance(project, "FileChangeManager")),
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
        deploymentService = deploymentService,
    ),
    private val juggCompilerHelper: JuggCompilerHelper = JuggCompilerHelper(project, pathManager, juggServer, deployTargetManager, deployStateManager, deployFileManager, deployHistoryManager, juggRunningTaskStatusManager, compileContextManager, fileChangesHandler, dependencyChangeManager, gradleProjectInfoLocalFetchManager, gitFileChangesDetector, taskRunnerManager),
    private val projectCustomConfigManager: ProjectCustomConfigManager = ProjectCustomConfigManager(pathManager.configDir, JuggLogger.getInstance(project, "ProjectCustomConfigManager"), juggServer, fileChangesHandler, deployHistoryManager, compileContextManager, customCompilerManager),
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
    )
    private val mcpInvoker: McpToolInvoker = McpToolInvoker(pathManager.projectDir.absolutePath,
        IdeaMcpRuntime(logger.getInstance("McpRuntime"), project, deployTargetManager, deployStateManager, forceGradleCompileHelper, juggConfigurationRunner, deployFileManager, juggCompilerHelper, gitFileChangesDetector),
        eventModel = controlPanelController.model,
    )
    private val copyGeneratedSourceHelper = CopyGeneratedSourceHelper(taskRunnerManager, logger)
    private val runConfigurationLock = Any()

    constructor(
        project2: Project,
        pathManager: JuggPathManager,
    ): this(project = project2, pathManager)

    override fun init() {
        Disposer.register(this, juggCompilerHelper)
        runTaskSafe("Init Jugg", {
            JuggSettings.migrateLegacyJuggSettings(PropertiesComponent.getInstance())
            juggServer.initialize()
            initializeRuntime()
            tryCreateRunConfigurations(isSyncFinished = false)

            // init project info async
            runTaskSafe("Init project info", ::recoverDeployContext)
            // init deployment service async
            deploymentService.preInit(logger)

            logger.debug("Checking updates...")
            juggServer.checkUpdate {
                val checkUpdateHandler = CheckUpdateHandler(project, juggServer.version, projectCustomConfigManager, JuggLogger.getInstance(project, "CheckUpdateHandler"))
                taskRunnerManager.runProjectWriteLocked("Apply server custom config") {
                    checkUpdateHandler.handle(it)
                }
                juggHotUpdateDownloader.init(project)
            }

            taskRunnerManager.runBackgroundSafe("Auto update Jugg CLI", delayMs = 10_000, isGlobalWrite = true) {
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

    private fun initializeRuntime() {
        projectCustomConfigManager.refresh()
        AsDeployerCompat.init(logger)
        IAsDeployerCompat.updateMinApi(JuggSettings.finalIsEnableCompatibleDeploymentMode)
        ProjectInfoReader(project, logger).printInfo()
        deployHistoryManager.checkProjectDirChanged()
        clearLegacySystemJuggDir()
        logger.info("Start jugg finished.")
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
            rebindCompileContext()
            dependencyChangeManager.onEndSyncing(isFromIde = true, true, compileContextManager.compileContext)
            if (!juggConfigurationRunner.isCompiling) {
                warmUpCompile()
            }
        }

        // check dependency again to avoid missing dependency(in ide little chance)
        if (isAfterSync) {
            taskRunnerManager.runBackgroundSafe("Check Project Info Delay", delayMs = 5000L, isProjectWrite = true) {
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
        handleFileChangeResult(fileChangeManager.processFileChanges(deployContextRecoverInfo.changedFiles, emptyList(), FileChangeSource.RECOVER))

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
                isRemoteCompile, projectInfo, hostTaskExecutor.currentIndicator, coroutineScope)
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
        createMoreOptionsManager().resetJuggCache()
    }

    fun forceReInstallNextTime() {
        juggConfigurationRunner.forceReInstallNextTime()
    }

    override fun getMoreOptions(options: JuggRunConfigurationOptions): ActionGroup {
        return createMoreOptionsManager().createOptions(options)
    }

    override fun installSkills() {
        InstallJuggSkillsDialog.installJuggMcpAndSkills(project, pathManager.projectDir, taskRunnerManager, logger)
    }

    override fun checkUpdates() {
        createMoreOptionsManager().checkUpdates()
    }

    private fun createMoreOptionsManager(): MoreOptionsManager {
        return MoreOptionsManager(
            this, pathManager, taskRunnerManager, hostTaskExecutor,
            deployHistoryManager, deployTargetManager, dependencyChangeManager,
            juggCompilerHelper, juggServer, juggHotUpdateDownloader, logger,
        )
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
                    showReportIssueDialog(builder, candidates)
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
    ) {
        val dialog = ReportIssueDialog(candidates)
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
                uploadIssueReport(bundle)
            }
        }
    }

    private fun uploadIssueReport(bundle: IssueReportBundle) {
        SwingUtilities.invokeLater {
            val progressDialog = ReportIssueProgressDialog("Uploading logs...")
            taskRunnerManager.runBackgroundSafe("Upload issue report") {
                val uploadResult = IssueReportUploader().upload(bundle, IssueReportUploader.JUGG_REPORT_URL)
                SwingUtilities.invokeLater {
                    progressDialog.close(DialogWrapper.OK_EXIT_CODE)
                    ReportIssueResultDialog(uploadResult) {
                        uploadIssueReport(bundle)
                    }.show()
                }
            }
            progressDialog.show()
        }
    }

    override fun invokeMcp(request: McpJsonRpcRequest): McpJsonRpcResponse {
        return mcpInvoker.invokeMcp(request)
    }

    private fun rebindCompileContext() {
        val context = compileContextManager.compileContext
        deployFileManager.updateModuleInfos(context.modules, context.mappingFile)
        val juggCompiler = JuggCompiler(context, this)
        juggCompilerHelper.juggCompiler = juggCompiler
        fileChangesHandler.init(context)
        fileChangeManager.init(pathManager.projectDir, context.modules)
        customCompilerManager.init(context)
    }

    private fun initCompile(
        compileContextInfo: CompileContextInfo,
        deployedFiles: List<CompileOutput>,
        startCompileTime: Long?,
    ) {
        logger.info("Init compile...")

        deployStateManager.isBuildFileChanged = false

        var finalApkInfos = compileContextInfo.apkInfos
        logger.debug("hasEmbeddedApks: ${projectCustomConfigManager.hasEmbeddedApks()}")
        if (projectCustomConfigManager.hasEmbeddedApks()) {
            finalApkInfos = projectCustomConfigManager.fillApkInfosWithEmbeddedApks(
                finalApkInfos,
                pathManager.localClasspathStoragePathManager.embeddedApkDir,
            )
        }

        val costTime = measureTimeMillis {
            compileContextManager.setCompileContext(compileContextInfo)
            deployFileManager.init(finalApkInfos, deployedFiles, startCompileTime)
            dependencyChangeManager.init(pathManager.projectInfosDir, compileContextManager.compileContext)
            rebindCompileContext()
        }
        logger.debug("Init compile cost ${costTime}ms")
        startFileMonitoring()
    }

    private fun startFileMonitoring() {
        fileChangeManager.start(fileChangesDetector, ::handleFileChangeResult)
        logger.info("Jugg init complete, start listening file changes.")

        if (JuggSettings.isEnableWarmUp) {
            warmUpCompile()
        }
    }

    private fun handleFileChangeResult(result: FileChangeResult) {
        if (result.hasChanges && JuggSettings.compileOnSave) {
            runTaskSafe("Compile Changes", ::compileChanges)
        }
        controlPanelController.refresh()
    }

    private fun warmUpCompile() {
        runTaskSafe("Warm Up Compile", {
            juggCompilerHelper.warmUp()
        })
    }

    /** Applies the persisted compat deploy switch to the IDE deployer and connected devices. */
    fun updateCompatibleDeploymentMode() {
        IAsDeployerCompat.updateMinApi(JuggSettings.finalIsEnableCompatibleDeploymentMode)
        runTaskSafe("Remove Jugg JVMTI agents", {
            deployTargetManager.getSelectedDevices().forEach {
                val result = JuggJvmtiAgentManager(IdeaDeviceAdb(it, logger), logger).removeAllAgents()
                logger.debug("Remove Jugg JVMTI agents result: $result, device: $it")
            }
        })
    }

    private fun prepareRun() {
        taskRunnerManager.runProjectWriteLocked("Refresh custom config") {
            projectCustomConfigManager.refresh()
        }
    }

    private fun runTaskSafe(jobName: String, action: Runnable, isNeedShowIndicator: Boolean = true) {
        taskRunnerManager.runTaskSafe(jobName, action, isNeedShowIndicator)
    }

    override fun dispose() {
        logger.debug("project ${pathManager.projectDir} dispose")
        controlPanelController.clear()
        gradleProjectInfoLocalFetchManager.close()
        customCompilerManager.close()
        deployFileManager.dispose()
        taskRunnerManager.dispose()
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
                compileUiHandler, controlPanelController.model, taskRunnerManager, androidTestRunSpec,
                controlPanelController = controlPanelController,
            )

            // try reload custom config if changed
            prepareRun()
            ProgressManager.getInstance().run(task)

            return task
        }

    }

}
