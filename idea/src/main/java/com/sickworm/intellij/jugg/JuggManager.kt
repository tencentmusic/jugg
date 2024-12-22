package com.sickworm.intellij.jugg

import com.android.ddmlib.IDevice
import com.intellij.execution.RunManager
import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessOutputType
import com.intellij.openapi.Disposable
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.DumbProgressIndicator
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.io.FileUtil
import com.sickworm.intellij.jugg.compiler.*
import com.sickworm.intellij.jugg.project.data.ModuleBuildPathInfo
import com.sickworm.intellij.jugg.deploy.*
import com.sickworm.intellij.jugg.deploy.run.*
import com.sickworm.intellij.jugg.ide.*
import com.sickworm.intellij.jugg.ide.ui.SimpleProcessHandler
import com.sickworm.intellij.jugg.logger.JuggLogger
import com.sickworm.intellij.jugg.logger.TimeLogger
import com.sickworm.intellij.jugg.logger.getInstance
import com.sickworm.intellij.jugg.server.JuggServer
import com.sickworm.intellij.jugg.project.*
import com.sickworm.intellij.jugg.project.dependency.IDependencyChangeManager
import com.sickworm.intellij.jugg.project.dependency.create
import com.sickworm.intellij.jugg.project.dependency.GradleProjectInfoLocalFetchManager
import com.sickworm.intellij.jugg.ide.ui.CheckUpdateHandler
import kotlinx.coroutines.*
import org.jetbrains.annotations.TestOnly
import org.jetbrains.kotlin.utils.addToStdlib.measureTimeMillisWithResult
import java.io.File
import java.lang.Runnable
import kotlin.system.measureTimeMillis


class JuggManager @TestOnly constructor(
    val project: Project,
    val pathManager: JuggPathManager,
    private val coroutineScope: CoroutineScope = CoroutineScope(Dispatchers.IO),
    val logger: Logger = JuggLogger.getInstance(project, "JuggManager"),
    private val juggServer: JuggServer = JuggServer(project, pathManager),
    private val fileChangesHandler: IFileChangesHandler = FileChangesHandler(pathManager.projectDir, pathManager.juggRootDir, JuggLogger.getInstance(project, "FileChangesHandler")),
    private val fileChangesDetector: IFileChangesDetector = FileChangesDetector(project, pathManager.projectDir),
    private val deployHistoryManager: IDeployHistoryManager = DeployHistoryManager(
        pathManager,
        fileChangesHandler,
        JuggLogger.getInstance(project, "DeployHistoryManager")
    ),
    private val deployFileManager: DeployFileManager = DeployFileManager(
        JuggLogger.getInstance(project, "DeployFileManager"),
        pathManager.tmpDir,
        pathManager.databaseDir,
        coroutineScope,
    ),
    private val compileContextManager: CompileContextManager = CompileContextManager(project, pathManager, deployFileManager, deployHistoryManager),
    private val deployTargetManager: IDeployTargetManager = DeployTargetManager(project),
    private val deployStateManager: DeployStateManager = DeployStateManager(project, deployTargetManager, deployHistoryManager),
    private val juggRunningTaskStatusManager: IJuggRunningTaskStatusManager = JuggRunningTaskStatusManager(),
    private val dependencyChangeManager: IDependencyChangeManager = IDependencyChangeManager.create(JuggLogger.getInstance(project, "DependencyChangeManager")),
    private val taskRunnerManager: TaskRunnerManager = TaskRunnerManager(project, logger, deployStateManager, juggServer, coroutineScope),
    private val gradleProjectInfoLocalFetchManager: GradleProjectInfoLocalFetchManager = GradleProjectInfoLocalFetchManager(project, pathManager, compileContextManager, taskRunnerManager, dependencyChangeManager, logger),
    private val gitFileChangesDetector: GitFileChangesDetector = GitFileChangesDetector(deployHistoryManager, taskRunnerManager, coroutineScope, logger),
    private val juggDeployerHelper: JuggDeployerHelper = JuggDeployerHelper(project, deployTargetManager, deployFileManager, deployHistoryManager, deployStateManager, dependencyChangeManager, compileContextManager, juggServer, coroutineScope),
    private val juggCompilerHelper: JuggCompilerHelper = JuggCompilerHelper(project, pathManager, juggServer, deployTargetManager, deployStateManager, deployFileManager, deployHistoryManager, juggRunningTaskStatusManager, compileContextManager, fileChangesHandler, dependencyChangeManager, gradleProjectInfoLocalFetchManager),
    private val customConfigManager: CustomConfigManager = CustomConfigManager(pathManager.configDir, JuggLogger.getInstance(project, "CustomConfigManager")),
    ): Disposable, CoroutineScope by coroutineScope {

    constructor(
        project2: Project,
        pathManager: JuggPathManager,
    ): this(project = project2, pathManager)

    fun init() {
        Disposer.register(this, juggCompilerHelper)
        Disposer.register(this, gradleProjectInfoLocalFetchManager)
        runTaskSafe("Init Jugg", {
            AsDeployerCompat.init(JuggLogger.getInstance(project, "AsDeployerCompat"))
            loadCustomConfig()
            tryCreateRunConfigurations(isSyncFinished = false)
            IAsDeployerCompat.updateMinApi(JuggSettings.finalIsEnableCompatibleDeploymentMode)
            ProjectInfoReader(project, logger.getInstance("ProjectInfoReader")).printInfo()
            deployHistoryManager.checkProjectDirChanged()
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
                config.buildFileRules.let {
                    fileChangesHandler.updateBuildFileRules(it)
                }
            }
        } catch (e: Exception) {
            // maybe structure is updated
            logger.info("loadCustomConfig failed", e)
        }
    }

    private fun updateProjectInfo() {
        // gradle sync finished, reset hasRun flag to avoid "No file changes" fallback
        juggRunningTaskStatusManager.resetHasRun()

        val isSuccess = compileContextManager.updateCompileContextAfterSync()
        if (isSuccess) {
            reInitOnCompileContextUpdate()
            dependencyChangeManager.onEndSyncing(isFromIde = true, true, compileContextManager.compileContext)
            warmUpCompile(isNeedWarmUpDeploy = false)
            launch {
                // do it async to let warmUpCompile run
                dependencyChangeManager.tryShowChangeConfirmDialog(isRunCompileLater = true)
            }
        }
    }

    fun onSyncEvent(syncEvent: SyncEvent) {
        logger.debug("onSyncEvent: $syncEvent")
        when (syncEvent) {
            SyncEvent.SUCCEEDED -> {
                tryCreateRunConfigurations(isSyncFinished = true)
                runTaskSafe("Update project info", ::updateProjectInfo)
                gradleProjectInfoLocalFetchManager.runUpdateIfNeeded()
            }
            SyncEvent.SKIPPED -> {
                tryCreateRunConfigurations(isSyncFinished = false)
                gradleProjectInfoLocalFetchManager.runUpdateIfNeeded()
            }
            SyncEvent.STARTED -> {
                dependencyChangeManager.onStartSyncing(isFromIde = true)
            }
            SyncEvent.FAILED -> {
                dependencyChangeManager.onEndSyncing(isFromIde = true, false, compileContextManager.compileContext)
            }
        }
    }

    @Synchronized
    private fun tryCreateRunConfigurations(isSyncFinished: Boolean, maxRetryCount: Int = 5) {
        TimeLogger.start("tryCreateDefaultRunConfiguration")
        val currentList = RunManager.getInstance(project).getConfigurationSettingsList(JuggConfigurationType::class.java)
        val currentListNames = currentList.map { it.name }
        logger.debug("JuggConfigurationType currentList: $currentListNames")

        val currentListNamesExceptDefault = currentList.filter { it.name != SuggestRunConfiguration.DEFAULT.runConfigName }
        if (currentListNamesExceptDefault.isNotEmpty() && !isSyncFinished) {
            logger.debug("Not sync finished and exits non-default configs is not empty, skip create default run configuration")
            return
        }

        val suggestRunConfiguration = AsDeployerCompat.getSuggestRunConfigurations(
            currentListNames, project,
            logger.getInstance("GetSuggestRunConfigurations"),
            isNeedDefaultRunConfig = maxRetryCount <= 0,
        )
        logger.debug("Suggest run configurations: $suggestRunConfiguration")
        if (suggestRunConfiguration.isEmpty()) {
            logger.debug("No suggest run configuration")
            if (currentListNamesExceptDefault.isEmpty() && isSyncFinished && maxRetryCount > 0) {
                logger.debug("No current run configuration, retry after 2s")
                launch {
                    delay(2000)
                    tryCreateRunConfigurations(isSyncFinished = true, maxRetryCount = maxRetryCount - 1)
                }
            }
            return
        }

        val settingsList = suggestRunConfiguration.map { suggest ->
            val factory: ConfigurationFactory = JuggConfigurationType.getInstance().configurationFactories[0]
            val settings = RunManager.getInstance(project).createConfiguration(suggest.runConfigName, factory)
            (settings.configuration as JuggRunConfiguration).state?.let {
                it.compileCommand = suggest.compileCommand
                it.outputApkName = suggest.outputApkPath
                it.setDefaultRemoteOption(JuggSettings.defaultCompileSettings)
            }
            settings.isActivateToolWindowBeforeRun = false
            settings
        }
        settingsList.forEach {
            RunManager.getInstance(project).addConfiguration(it)
        }

        // select if first created except default
        val settingsListExceptDefault = settingsList.filter { it.name != SuggestRunConfiguration.DEFAULT.runConfigName }
        if (currentListNamesExceptDefault.isEmpty() && settingsListExceptDefault.isNotEmpty()) {
            val settings = settingsListExceptDefault[0]
            RunManager.getInstance(project).selectedConfiguration = settings
        }
        TimeLogger.end("tryCreateDefaultRunConfiguration", logger)
    }

    @TestOnly
    fun recoverDeployContext() {
        logger.debug("Start recover deploy context")

        val deployContextRecoverInfo = deployHistoryManager.tryGetContextRecoverInfoFromDb()
        if (deployContextRecoverInfo == null) {
            logger.debug("Can not recover from deploy history, please run gradle compile first")
            return
        } else {
            logger.debug("Recover deploy context from history successfully:")
            logger.debug("$deployContextRecoverInfo")
        }

        // step 1: recover compile context
        initCompile(deployContextRecoverInfo.compileContextInfo, deployContextRecoverInfo.deployedFiles,
            isNeedWarmUpDeploy = false, startCompileTime = null
        )
        // step 2: recover deploy files
        logger.debug("Start recover deploy history...")
        deployTargetManager.setApks(deployContextRecoverInfo.compileContextInfo.apkInfos)
        // step 3: recover changed files
        processFileChanged(deployContextRecoverInfo.changedFiles, emptyList(), isFromRecover = true)

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
        isFromRecover: Boolean,
    ) {
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
                    it.name != ".DS_Store"
            }
            if (simpleFilterFiles.isNotEmpty()) {
                logger.debug("Detect file changed (before filter): ${simpleFilterFiles.map { it.path }}")
            }
        }

        val realChangedFiles = fileChangesHandler.filter(changedFiles)
        realChangedFiles.forEach {
            logger.debug("Detect file changed: [${it.type}]${it.file.path}")
        }
        if (realChangedFiles.isEmpty()) {
            return
        }

        deployFileManager.addChangedFile(realChangedFiles)

        val isBuildFileChanged = realChangedFiles.any { it.type == CompileFile.Type.BuildFile }
        if (isBuildFileChanged || isFromRecover) {
            val allBuildFiles = deployFileManager.getUndeployedFiles()
                .filter { it.type == CompileFile.Type.BuildFile }
                .map { it.file }
            dependencyChangeManager.onUpdateChangedBuildFiles(allBuildFiles)
        }

        if (!isFromRecover) {
            gitFileChangesDetector.onSourceFileChanged(realChangedFiles)
        }

        if (JuggSettings.compileOnSave) {
            runTaskSafe("Compile Changes", ::compileChanges)
        }
    }

    @Volatile
    private var currentTask: JuggRunningTask? = null

    fun cancelCurrentTask(processHandler: ProcessHandler, onFinish: () -> Unit) {
        val currentTask = currentTask
        if (currentTask == null) {
            logger.debug("Current task is null")
            onFinish()
            return
        }
        if (!currentTask.isRunning) {
            logger.debug("Current task is not running")
            onFinish()
            return
        }
        logger.warn("Canceling task...")
        processHandler.notifyTextAvailable("Waiting last task finishing... \n\n", ProcessOutputType.STDOUT)
        currentTask.cancel(onFinish)
    }

    fun createRunningTask(
        options: JuggGradleCompileOptions,
        processHandler: SimpleProcessHandler,
        isForceGradleCompile: Boolean = false,
    ): JuggRunningTask {
        logger.debug("Create running task: ${options.toSafeString()}")

        val startCompileTime = System.currentTimeMillis()
        val initIncrementalCompileTask = task@{
            // do it async
            fun action() {
                initIncrementalCompileAfterFullBuild(startCompileTime, options.isRemoteCompile)
            }
            runTaskSafe("Init Incremental Compile", ::action)
        }
        val task = JuggRunningTask(options, project, juggServer, deployTargetManager, dependencyChangeManager,
            juggRunningTaskStatusManager, deployHistoryManager, processHandler, juggCompilerHelper, juggDeployerHelper, initIncrementalCompileTask,
            isForceGradleCompile = isForceGradleCompile,
        )
        currentTask = task

        // try reload custom config if changed
        loadCustomConfig()

        return task
    }

    @TestOnly
    fun compileChanges() {
        juggCompilerHelper.incrementalCompile(CompileStatusHolder.DEFAULT)
    }

    @TestOnly
    fun initIncrementalCompileAfterFullBuild(startCompileTime: Long, isRemoteCompile: Boolean = false) {
        JuggLogger.resetLatestCompileLog(project)
        juggServer.afterFullCompile()
        pathManager.stagingDir.deleteRecursively()
        compileContextManager.compileContext.tempCompileDir.deleteRecursively()

        logger.debug("Init compile after full build, isRemoteCompile=$isRemoteCompile")
        if (!isRemoteCompile) {
            compileContextManager.updateCompileContextAfterLocalFetch()
        }

        var allModules = compileContextManager.getProjectInfo().modules
        val moduleBuildPathInfos = allModules.map { it.value.buildPathInfo }

        logger.info("Fetching classpath...")
        val (costTime2, classpathRootDir) = measureTimeMillisWithResult {
            val currentIndicator = taskRunnerManager.currentIndicator
            val originText = currentIndicator?.text
            currentIndicator?.text = "Jugg: Fetching classpath..."
            val result = juggCompilerHelper.fetchClasspathResult(isRemoteCompile, moduleBuildPathInfos)
            currentIndicator?.text = originText
            return@measureTimeMillisWithResult result
        }
        logger.debug("fetchClasspathResult cost ${costTime2}ms")
        logger.debug("fetchClasspathResult classpathRootDir = $classpathRootDir")
        if (classpathRootDir == null || !classpathRootDir.exists()) {
            logger.warn("Fetch classpath failed, please check log for details.")
            return
        }
        // wrap local CompileContextInfo to CompileContextInfo fetched from remote
        allModules = allModules.values
            .map {
                it.copy(buildPathInfo = ModuleBuildPathInfo(
                    classpathRootDir,
                    File(classpathRootDir, it.buildPathInfo.modulePathRelative.path),
                    it.buildVariant,
                )
                )
            }
            .associateBy { it.name }

        val (costTime: Long, compileContextInfo: CompileContextInfo) = measureTimeMillisWithResult {
            pathManager.compileRootDir.clearDir()
            val apkInfos = deployTargetManager.getApks()
            if (apkInfos.isEmpty()) {
                logger.warn("Init compile failed for no apk found")
                return
            }
            deployHistoryManager.reInitAfterFullCompiled(
                apkInfos,
                allModules,
                startCompileTime,
            )

        }
        logger.debug("reInitAfterFullCompiled cost ${costTime}ms")

        if (isRemoteCompile) {
            copyGeneratedSourceToLocal()
        }
        initCompile(compileContextInfo, emptyList(),
            isNeedWarmUpDeploy = JuggSettings.isEnableWarmUpDeploy,
            startCompileTime = startCompileTime,
        )
    }

    fun gradleCompile() {
        logger.debug("[action] gradleCompile")
        JuggRunProfileState.executeGradleCompile(project)
    }

    fun restartApp() {
        AsDeployerCompat.getDevices(project)?.forEach {
            deployTargetManager.restartApp(it)
        }
    }

    fun enableInjectGradleCompilation() {
        logger.info("[options] enableInjectGradleCompilation")
        deployHistoryManager.deleteDeployHistory()
        enableReadProjectFromGradle()
        enableCompatibleDeploymentMode()
        IAsDeployerCompat.updateMinApi(JuggSettings.finalIsEnableCompatibleDeploymentMode)
    }

    fun markAsSyncedAndReInitCompiler() {
        logger.info("[test options] markAsSyncedAndReInitCompiler")
        onSyncEvent(SyncEvent.SUCCEEDED)
    }

    fun enableReadProjectFromGradle() {
        logger.info("[options] enableReadProjectFromGradle")
        pathManager.gradleProjectInfoFile.delete()
        onSyncEvent(SyncEvent.SUCCEEDED)
    }

    fun setForceCompatDevice(adb: IdeaDeviceAdb) {
        logger.info("[options] setForceCompatDevice ${adb.displayName}")

        val compatDeployHelper = CompatDeployHelper(JuggInitializer.getManager(project)!!.logger)
        val isForceCompatDevice = compatDeployHelper.isForceCompatDevice(adb)
        if (isForceCompatDevice) {
            compatDeployHelper.clearCompatDeviceRecord(adb)
        } else {
            compatDeployHelper.recordCompatDeviceRecord(adb)
        }
        forceReInstallNextTime()
    }

    fun forceReInstallNextTime() {
        // clear lastDeployOverlayIds to force re-reinstall
        deployHistoryManager.isForceReinstall = true
        juggRunningTaskStatusManager.resetHasRun()
    }


    fun markAsGradleCompiledAndReInitCompiler(options: JuggRunConfigurationOptions) {
        logger.info("[test options] markAsGradleCompiledAndReInitCompiler")
        runTaskSafe("Mark as Gradle Compiled", {
            val compileOptions = options.toCompileOptions(pathManager)

            // login and get apks
            dependencyChangeManager.onStartBuilding()
            val result = juggCompilerHelper.gradleCompile(
                compileOptions,
                SimpleProcessHandler(),
                taskRunnerManager.currentIndicator ?: DumbProgressIndicator.INSTANCE,
                isOnlyFetchResult = true,
            )
            dependencyChangeManager.onEndBuilding(result.isSuccess, result.isCanceled)
            if (!result.isSuccess) {
                logger.warn("gradleCompile(isOnlyFetchResult) failed, please check log for details.")
                return@runTaskSafe
            }

            // re-init compiler and mark all compiled
            initIncrementalCompileAfterFullBuild(System.currentTimeMillis(), compileOptions.isRemoteCompile)
        })
    }

    fun copyGeneratedSourceToLocal() {
        logger.info("copyGeneratedSourceToLocal")
        taskRunnerManager.runTaskSafe("Copy Generated Source to local", {
            val modules = compileContextManager.compileContext.modules
            modules.values.forEach {
                val dirInClasspath = it.buildPathInfo.generatedSourcePath
                val dirInLocal = ModuleBuildPathInfo(it.projectRootDir, it.moduleRootDir, it.buildVariant).generatedSourcePath
                logger.debug("Copy generated source from $dirInClasspath to $dirInLocal")
                if (!dirInClasspath.exists()) {
                    logger.debug("Skip copy, $dirInClasspath not exists")
                    return@forEach
                }
                if (dirInClasspath.path.equals(dirInLocal.path)) {
                    logger.debug("Skip copy, source and target are the same")
                    return@forEach
                }
                if (dirInLocal.exists() && !dirInLocal.isDirectory) {
                    dirInLocal.delete()
                }
                dirInClasspath.copyRecursively(dirInLocal, overwrite = true)
            }
        }, isBlockIncrementalCompile = false)
    }

    fun setCustomServerUrl() {
        logger.info("[options] setNewServerUrl")
        juggServer.setCustomServer()
    }

    fun enableCompatibleDeploymentMode() {
        logger.info("[options] enableCompatibleDeploymentMode")
        IAsDeployerCompat.updateMinApi(JuggSettings.finalIsEnableCompatibleDeploymentMode)

        runTaskSafe("Remove Jugg JVMTI agents", {
            val devices = deployTargetManager.getDevices()
            devices.forEach {
                val result = JuggJvmtiAgentManager(IdeaDeviceAdb(it, logger), logger).removeAllAgents()
                logger.debug("Remove Jugg JVMTI agents result: $result, device: $it")
            }
        })
    }

    fun setEnableBackupClasspath() {
        logger.info("[options] setEnableBackupClasspath ${JuggSettings.isEnableBackupClasspath}")
        deployHistoryManager.deleteDeployHistory()
    }

    fun dumpLogcatErrorLogs(): String {
        return deployTargetManager.dumpErrorLogs()
    }

    fun getDeviceList(): List<IDevice> {
        return deployTargetManager.getDevices()
    }

    private fun reInitOnCompileContextUpdate() {
        deployFileManager.updateModuleInfos(compileContextManager.compileContext.modules)
        juggCompilerHelper.juggCompiler = JuggCompiler(compileContextManager.compileContext, this)
        fileChangesHandler.init(compileContextManager.compileContext)
        gitFileChangesDetector.init(pathManager.projectDir, compileContextManager.compileContext.modules)
    }

    private fun initCompile(
        compileContextInfo: CompileContextInfo,
        deployedFiles: List<CompileOutput>,
        isNeedWarmUpDeploy: Boolean,
        startCompileTime: Long?,
    ) {
        logger.info("Init compile...")

        deployStateManager.isBuildFileChanged = false

        val costTime = measureTimeMillis {
            compileContextManager.setCompileContext(compileContextInfo)
            deployFileManager.init(compileContextInfo.apkInfos, deployedFiles, startCompileTime)
            dependencyChangeManager.init(pathManager.projectInfosDir, compileContextManager.compileContext)
            reInitOnCompileContextUpdate()
        }
        logger.debug("Init compile cost ${costTime}ms")

        fileChangesDetector.startListen(object: FileChangesListener {
            override fun onFileChanges(changedFiles: List<File>, deletedFiles: List<File>) {
                processFileChanged(changedFiles, deletedFiles, isFromRecover = false)
            }
        })
        gitFileChangesDetector.startListen(object: FileChangesListener {
            override fun onFileChanges(changedFiles: List<File>, deletedFiles: List<File>) {
                processFileChanged(changedFiles, deletedFiles, isFromRecover = false)
            }
        })

        logger.info("Jugg init complete, start listening file changes.")

        if (JuggSettings.isEnableWarmUp) {
            warmUpCompile(isNeedWarmUpDeploy)
        }
    }

    private fun warmUpCompile(isNeedWarmUpDeploy: Boolean) {
        runTaskSafe("Warm Up Compile", {
            doWarmUpCompile(isNeedWarmUpDeploy)
        })
    }

    private fun doWarmUpCompile(isNeedWarmUpDeploy: Boolean) {
        runBlocking {
            launch(Dispatchers.IO) {
                juggCompilerHelper.warmUp()
            }
            if (isNeedWarmUpDeploy) {
                launch(Dispatchers.IO) {
                    val devices = deployTargetManager.getDevices()
                    devices.forEachIndexed { index, device ->
                        val isLastDevice = index == devices.size - 1
                        val result = juggDeployerHelper.deploy(device, isLastDevice, processHandler = null, isInstall = false, isWarmUp = true, retryReason = JuggDeployerHelper.DO_NOT_RETRY)
                        juggServer.report {
                            action = "warm_up_deploy"
                            isSuccess = result.isSuccess
                            costTime = result.costTime
                            detail = result.failedReason
                        }
                    }
                }
            } else {
                logger.debug("no need warm up deploy, skip.")
            }
        }
    }

    private fun runTaskSafe(jobName: String, action: Runnable, isNeedShowIndicator: Boolean = true) {
        taskRunnerManager.runTaskSafe(jobName, action, isNeedShowIndicator)
    }

    override fun dispose() {
        logger.debug("project ${pathManager.projectDir} dispose")
        coroutineScope.cancel()
    }

}