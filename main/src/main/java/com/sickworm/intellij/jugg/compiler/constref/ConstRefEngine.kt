package com.sickworm.intellij.jugg.compiler.constref

import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.jugg.compiler.listFilesRecursively
import com.sickworm.intellij.jugg.project.TaskRunnerManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.zip.CRC32
import kotlin.system.measureTimeMillis

/**
 * Coordinates const-ref analysis lifecycle and exposes readiness/impact APIs to deploy flow.
 */
class ConstRefEngine private constructor(
    private val analyzer: ConstRefAnalyzer,
    private val logger: Logger,
    private val taskRunnerManager: TaskRunnerManager,
    private val startupStabilizationDelayMs: Long = 10_000L,
    private val runtimeFactory: () -> ConstRefRuntime,
    initialRuntimeState: ConstRefRuntimeState,
) {
    constructor(
        analyzer: ConstRefAnalyzer,
        dbFile: File,
        repoFingerprintDbFile: File,
        logger: Logger,
        taskRunnerManager: TaskRunnerManager,
        startupStabilizationDelayMs: Long = 10_000L,
    ) : this(
        analyzer = analyzer,
        logger = logger,
        taskRunnerManager = taskRunnerManager,
        startupStabilizationDelayMs = startupStabilizationDelayMs,
        runtimeFactory = {
            var database: ConstRefCacheDatabase? = null
            try {
                database = ConstRefCacheDatabase(dbFile, logger)
                val repoSharedFingerprintStore = RepoSharedFingerprintStore(logger, repoFingerprintDbFile)
                ConstRefRuntime(
                    database = database,
                    repoSharedFingerprintStore = repoSharedFingerprintStore,
                    impactResolver = ConstRefImpactResolver(database),
                )
            } catch (t: Throwable) {
                try {
                    database?.close()
                } catch (_: Throwable) {
                }
                throw t
            }
        },
        initialRuntimeState = ConstRefRuntimeState.NotInitialized,
    )

    constructor(
        analyzer: ConstRefAnalyzer,
        database: ConstRefCacheDatabase,
        logger: Logger,
        taskRunnerManager: TaskRunnerManager,
        repoSharedFingerprintStore: RepoSharedFingerprintStore,
        startupStabilizationDelayMs: Long = 10_000L,
    ) : this(
        analyzer = analyzer,
        logger = logger,
        taskRunnerManager = taskRunnerManager,
        startupStabilizationDelayMs = startupStabilizationDelayMs,
        runtimeFactory = {
            ConstRefRuntime(
                database = database,
                repoSharedFingerprintStore = repoSharedFingerprintStore,
                impactResolver = ConstRefImpactResolver(database),
            )
        },
        initialRuntimeState = ConstRefRuntimeState.Ready(
            ConstRefRuntime(
                database = database,
                repoSharedFingerprintStore = repoSharedFingerprintStore,
                impactResolver = ConstRefImpactResolver(database),
            )
        ),
    )

    private val maxAnalyzedHistory = 4096
    private val analysisMutex = Mutex()
    private val sceneTaskScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @OptIn(ExperimentalCoroutinesApi::class)
    private val fullScanDispatcher = Dispatchers.IO.limitedParallelism(1)
    private val runtimeLock = Any()
    private var runtimeState = initialRuntimeState
    private val database: ConstRefCacheDatabase
        get() = requireRuntime("database").database
    private val repoSharedFingerprintStore: RepoSharedFingerprintStore
        get() = requireRuntime("repoSharedFingerprintStore").repoSharedFingerprintStore
    private val impactResolver: ConstRefImpactResolver
        get() = requireRuntime("impactResolver").impactResolver
    private val stateLock = Any()
    private val changeTracker = ConstRefChangeTracker()
    private val sessionCache = ConstRefSessionCache(
        fileCacheMaxFiles = readPositiveIntProperty(SESSION_FILE_CACHE_MAX_PROPERTY, DEFAULT_SESSION_FILE_CACHE_MAX),
        lookupCacheMaxKeys = readPositiveIntProperty(SESSION_LOOKUP_CACHE_MAX_PROPERTY, DEFAULT_SESSION_LOOKUP_CACHE_MAX),
        ttlMs = readNonNegativeLongProperty(SESSION_CACHE_TTL_MS_PROPERTY, DEFAULT_SESSION_CACHE_TTL_MS),
    )
    private val pendingAnalyzeFiles = linkedSetOf<String>()
    private val pendingDeleteCleanupPaths = linkedSetOf<String>()
    private val pendingDeleteRequeuePaths = linkedSetOf<String>()
    private var deleteCleanupJob: Job? = null
    private var cacheCleanupJob: Job? = null
    private var currentEditingFile: String? = null
    private val analyzedAt = mutableMapOf<String, Long>()
    private val trackedSourceDirs = mutableListOf<String>()
    private val fullScanReadySourceDirs = mutableSetOf<String>()
    private val pendingAckChangedPaths = linkedSetOf<String>()
    private var delayedInitialFullScanJob: Job? = null
    private var hasLaunchedInitialFullScan = false
    private val cacheCleaner = ConstRefCacheCleaner(logger)
    private val sceneTaskStates = mutableMapOf(
        AnalyzeScene.FULL_SCAN to SceneTaskState(),
        AnalyzeScene.FILE_CHANGE to SceneTaskState(),
        AnalyzeScene.PRE_COMPILE to SceneTaskState(),
    )
    private val fullScanIoThrottleSleepMs: Long =
        readSceneNonNegativeLongProperty(FULL_SCAN_IO_THROTTLE_MS_PROPERTY, DEFAULT_FULL_SCAN_IO_THROTTLE_MS)
    private val fullScanIoThrottleEveryNFiles: Int =
        readScenePositiveIntProperty(FULL_SCAN_IO_THROTTLE_EVERY_PROPERTY, DEFAULT_FULL_SCAN_IO_THROTTLE_EVERY)
    private val fileChangeIoThrottleSleepMs: Long =
        readSceneNonNegativeLongProperty(FILE_CHANGE_IO_THROTTLE_MS_PROPERTY, DEFAULT_FILE_CHANGE_IO_THROTTLE_MS)
    private val fileChangeIoThrottleEveryNFiles: Int =
        readScenePositiveIntProperty(FILE_CHANGE_IO_THROTTLE_EVERY_PROPERTY, DEFAULT_FILE_CHANGE_IO_THROTTLE_EVERY)
    private val preCompileIoThrottleSleepMs: Long =
        readSceneNonNegativeLongProperty(PRE_COMPILE_IO_THROTTLE_MS_PROPERTY, DEFAULT_PRE_COMPILE_IO_THROTTLE_MS)
    private val preCompileIoThrottleEveryNFiles: Int =
        readScenePositiveIntProperty(PRE_COMPILE_IO_THROTTLE_EVERY_PROPERTY, DEFAULT_PRE_COMPILE_IO_THROTTLE_EVERY)
    private val onDemandIoThrottleSleepMs: Long =
        readSceneNonNegativeLongProperty(ON_DEMAND_IO_THROTTLE_MS_PROPERTY, DEFAULT_ON_DEMAND_IO_THROTTLE_MS)
    private val onDemandIoThrottleEveryNFiles: Int =
        readScenePositiveIntProperty(ON_DEMAND_IO_THROTTLE_EVERY_PROPERTY, DEFAULT_ON_DEMAND_IO_THROTTLE_EVERY)
    private val analyzeFilesBatchSize: Int =
        readPositiveIntProperty(BATCH_SIZE_PROPERTY, DEFAULT_BATCH_SIZE)
    private val fullScanLogIntervalMs: Long =
        readNonNegativeLongProperty(FULL_SCAN_LOG_INTERVAL_MS_PROPERTY, DEFAULT_FULL_SCAN_LOG_INTERVAL_MS)

    init {
        if (hasEnabledIoThrottle()) {
            logger.info(
                "ConstRefEngine io throttle enabled, " +
                    "fullScan=${formatThrottle(fullScanIoThrottleSleepMs, fullScanIoThrottleEveryNFiles)}, " +
                    "fileChange=${formatThrottle(fileChangeIoThrottleSleepMs, fileChangeIoThrottleEveryNFiles)}, " +
                    "preCompile=${formatThrottle(preCompileIoThrottleSleepMs, preCompileIoThrottleEveryNFiles)}, " +
                    "onDemand=${formatThrottle(onDemandIoThrottleSleepMs, onDemandIoThrottleEveryNFiles)}"
            )
        }
    }

    fun onFileSaved(filePath: String) {
        val stdPath = File(filePath).toStdPath()
        if (!isSourceFile(stdPath)) {
            return
        }
        synchronized(stateLock) {
            movePreviousEditingFileToPendingLocked(stdPath)
            currentEditingFile = stdPath
        }
    }

    fun onFileDeleted(filePath: String) {
        val stdPath = File(filePath).toStdPath()
        val shouldCleanup = synchronized(stateLock) {
            mayAffectConstRefIndexLocked(stdPath)
        }
        if (!shouldCleanup) {
            logger.debug("ConstRefEngine skip delete cleanup for non-source path=$stdPath")
            return
        }
        synchronized(stateLock) {
            pendingAnalyzeFiles.removeIf { it == stdPath || it.startsWith("$stdPath/") }
            if (currentEditingFile == stdPath || currentEditingFile?.startsWith("$stdPath/") == true) {
                currentEditingFile = null
            }
            analyzedAt.keys.removeIf { it == stdPath || it.startsWith("$stdPath/") }
            notifyStateChangedLocked()
        }
        changeTracker.onFileDeleted(stdPath)
        sessionCache.removeFile(stdPath)
        sessionCache.removeFilesByPrefix("$stdPath/")
        enqueueDeleteCleanup(stdPath)
    }

    fun awaitAnalysis(filePaths: List<String>, timeoutMs: Long = 5000L): AnalysisReadiness {
        val targetPaths = filePaths
            .map { File(it).toStdPath() }
            .filter { isSourceFile(it) }
            .distinct()
        if (targetPaths.isEmpty()) {
            return AnalysisReadiness.READY
        }
        if (getRuntime("awaitAnalysis") == null) {
            return AnalysisReadiness.READY
        }
        val startAt = System.currentTimeMillis()
        val deadlineAt = startAt + timeoutMs
        synchronized(stateLock) {
            flushCurrentEditingFileLocked()
            targetPaths.forEach { path ->
                if (File(path).exists()) {
                    pendingAnalyzeFiles.add(path)
                    // Clear stale analyzedAt so awaitAnalysis always waits for the analysis
                    // triggered by this call. Without this, a prior analysis completing at a
                    // timestamp >= startAt (e.g. the A→B analysis in an A→B→A scenario)
                    // could cause awaitAnalysis to return before the B→A analysis runs.
                    analyzedAt.remove(path)
                } else {
                    analyzedAt[path] = startAt
                }
            }
            trimAnalyzedAtLocked()
        }
        triggerPreCompileAnalyze()
        var readiness = AnalysisReadiness.READY
        synchronized(stateLock) {
            val relatedSourceDirs = resolveRelatedSourceDirsLocked(targetPaths)
            while (true) {
                val fileReady = targetPaths.all { path ->
                    !File(path).exists() || (analyzedAt[path] ?: 0L) >= startAt
                }
                val sourceReady = relatedSourceDirs.all { dir ->
                    fullScanReadySourceDirs.contains(dir) || shouldSkipFullScanRequirement(dir)
                }
                if (fileReady && sourceReady) {
                    break
                }

                val remainMs = deadlineAt - System.currentTimeMillis()
                if (remainMs <= 0L) {
                    val unreadyPaths = targetPaths.filter { path ->
                        File(path).exists() && (analyzedAt[path] ?: 0L) < startAt
                    }
                    val pendingSourceDirs = relatedSourceDirs.filterNot {
                        fullScanReadySourceDirs.contains(it) || shouldSkipFullScanRequirement(it)
                    }
                    readiness = AnalysisReadiness(
                        isReady = false,
                        unreadyPaths = unreadyPaths,
                        pendingSourceDirs = pendingSourceDirs,
                    )
                    logger.debug(
                        "ConstRefEngine.awaitAnalysis timeout(${timeoutMs}ms), " +
                            "targetPaths=$targetPaths, unreadyPaths=$unreadyPaths, pendingSourceDirs=$pendingSourceDirs"
                    )
                    logger.warn(
                        "ConstRefEngine.awaitAnalysis timeout(${timeoutMs}ms), " +
                            "targetPathCount=${targetPaths.size}, " +
                            "unreadyPathCount=${unreadyPaths.size}, " +
                            "pendingSourceDirCount=${pendingSourceDirs.size}"
                    )
                    break
                }
                try {
                    waitStateChangedLocked(remainMs.coerceAtMost(200L))
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    val unreadyPaths = targetPaths.filter { path ->
                        File(path).exists() && (analyzedAt[path] ?: 0L) < startAt
                    }
                    val pendingSourceDirs = relatedSourceDirs.filterNot {
                        fullScanReadySourceDirs.contains(it) || shouldSkipFullScanRequirement(it)
                    }
                    readiness = AnalysisReadiness(
                        isReady = false,
                        unreadyPaths = unreadyPaths,
                        pendingSourceDirs = pendingSourceDirs,
                    )
                    logger.debug("ConstRefEngine.awaitAnalysis interrupted, targetPaths=$targetPaths")
                    logger.warn(
                        "ConstRefEngine.awaitAnalysis interrupted, " +
                            "targetPathCount=${targetPaths.size}, " +
                            "unreadyPathCount=${unreadyPaths.size}, " +
                            "pendingSourceDirCount=${pendingSourceDirs.size}"
                    )
                    break
                }
            }
        }
        return readiness
    }

    fun ensureReadyForRecompile(filePaths: Collection<String>, timeoutMs: Long = 5000L): AnalysisReadiness {
        return awaitAnalysis(filePaths.toList(), timeoutMs)
    }

    /**
     * Analyze target files synchronously with best-effort cache reuse.
     * Existing analysis is reused through checksum-based paths inside [analyzeFiles].
     */
    fun analyzeOnDemand(filePaths: Collection<String>): AnalysisReadiness {
        val targetPaths = filePaths
            .map { File(it).toStdPath() }
            .filter { isSourceFile(it) }
            .distinct()
        if (targetPaths.isEmpty()) {
            return AnalysisReadiness.READY
        }

        val analyzePaths = synchronized(stateLock) {
            flushCurrentEditingFileLocked()
            targetPaths.forEach { path ->
                pendingAnalyzeFiles += path
            }
            val paths = pendingAnalyzeFiles
                .filter { isSourceFile(it) }
                .distinct()
                .toList()
            pendingAnalyzeFiles.clear()
            paths
        }

        var timedOut = false
        var analysisFailed = false
        if (analyzePaths.isNotEmpty()) {
            val timeoutMs = analyzePaths.size * PER_FILE_ANALYZE_TIMEOUT_MS
            val costMs = measureTimeMillis {
                val executor = Executors.newSingleThreadExecutor()
                val future = executor.submit {
                    runBlocking {
                        analyzeFilesForScene(analyzePaths.map(::File), AnalyzeScene.ON_DEMAND)
                    }
                }
                try {
                    future.get(timeoutMs, TimeUnit.MILLISECONDS)
                } catch (e: TimeoutException) {
                    timedOut = true
                    future.cancel(true)
                    logger.warn(
                        "ConstRefEngine analyzeOnDemand timed out, " +
                            "fileCount=${analyzePaths.size}, timeoutMs=$timeoutMs"
                    )
                } catch (e: ExecutionException) {
                    analysisFailed = true
                    logger.warn("ConstRefEngine analyzeOnDemand failed, degrade to no-op for this analysis", e.cause ?: e)
                } finally {
                    executor.shutdownNow()
                }
            }
            val analyzeFileNames = if (analyzePaths.size <= DETAILED_LOG_FILE_THRESHOLD) {
                analyzePaths.map { File(it).name }.toString()
            } else {
                "${analyzePaths.size} files"
            }
            if (!timedOut) {
                if (costMs > SLOW_PHASE_THRESHOLD_MS) {
                    logger.debug(
                        "ConstRefEngine analyzeOnDemand finished (slow), " +
                            "targetPathCount=${targetPaths.size}, analyzedPathCount=${analyzePaths.size}, " +
                            "files=$analyzeFileNames, cost=${costMs}ms"
                    )
                } else {
                    logger.debug(
                        "ConstRefEngine analyzeOnDemand finished, " +
                            "targetPathCount=${targetPaths.size}, analyzedPathCount=${analyzePaths.size}, cost=${costMs}ms"
                    )
                }
            }
        }

        return synchronized(stateLock) {
            if (timedOut) {
                val unreadyPaths = targetPaths.filter { path ->
                    File(path).exists() && (analyzedAt[path] ?: 0L) <= 0L
                }
                return@synchronized if (unreadyPaths.isEmpty()) {
                    AnalysisReadiness.READY
                } else {
                    AnalysisReadiness(
                        isReady = false,
                        unreadyPaths = unreadyPaths,
                    )
                }
            }
            val unreadyPaths = targetPaths.filter { path ->
                File(path).exists() && (analyzedAt[path] ?: 0L) <= 0L
            }
            if (unreadyPaths.isEmpty()) {
                AnalysisReadiness.READY
            } else if (analysisFailed) {
                logger.warn(
                    "ConstRefEngine analyzeOnDemand degraded to ready after failure, " +
                        "unreadyPathCount=${unreadyPaths.size}"
                )
                unreadyPaths.forEach { markAnalyzed(it) }
                AnalysisReadiness.READY
            } else {
                AnalysisReadiness(
                    isReady = false,
                    unreadyPaths = unreadyPaths,
                )
            }
        }
    }

    fun initializeFullScan(sourceDirs: List<File>) {
        val normalizedSourceDirs = sourceDirs
            .asSequence()
            .filter { it.exists() }
            .map { it.toStdPath() }
            .distinct()
            .toList()
        runCatching {
            database.registerPathHints(normalizedSourceDirs)
        }.onFailure {
            logger.debug("ConstRefEngine initializeFullScan failed, skip full scan for this round, reason=${it.message}")
            return
        }
        synchronized(stateLock) {
            trackedSourceDirs.clear()
            trackedSourceDirs += normalizedSourceDirs
            fullScanReadySourceDirs.clear()
            delayedInitialFullScanJob?.cancel()
            delayedInitialFullScanJob = null
            if (isSceneActiveLocked(AnalyzeScene.FULL_SCAN)) {
                return
            }
            val startupDelayMs = if (hasLaunchedInitialFullScan) 0L else startupStabilizationDelayMs
            if (startupDelayMs > 0L) {
                scheduleDelayedInitialFullScanLocked(normalizedSourceDirs, startupDelayMs)
            } else {
                launchFullScanLocked(normalizedSourceDirs)
            }
        }
    }

    fun getEffectedFiles(changedFilePaths: Collection<String>): List<EffectedConstRef> {
        return runCatching {
            val changedPaths = changedFilePaths
                .map { File(it).toStdPath() }
                .filter { isSourceFile(it) }
                .distinct()
            if (changedPaths.isEmpty()) {
                return emptyList()
            }
            val (changedKeys, removedKeys) = changeTracker.peekDefinitionDiff(changedPaths)
            val (changedChanges, removedChanges) = changeTracker.peekDefinitionChanges(changedPaths)
            if (changedKeys.isNotEmpty() || removedKeys.isNotEmpty()) {
                logger.debug(
                    "ConstRefEngine effected definition changes, " +
                        "changed=${changedChanges.toLogString()}, " +
                        "removed=${removedChanges.toLogString()}, " +
                        "changedPathCount=${changedPaths.size}"
                )
                synchronized(stateLock) {
                    pendingAckChangedPaths += changedPaths
                }
            }
            impactResolver.getEffectedFiles(
                changedPaths = changedPaths,
                changedDefinitionKeys = changedKeys,
                removedDefinitionKeys = removedKeys,
            )
        }.getOrElse {
            logger.warn("ConstRefEngine getEffectedFiles failed, return empty effected files", it)
            emptyList()
        }
    }

    private fun Set<ConstDefinitionChange>.toLogString(): String {
        return if (isEmpty()) {
            "[]"
        } else {
            joinToString(prefix = "[", postfix = "]") { it.toLogString() }
        }
    }

    fun acknowledgeEffectedFilesAfterDeployCommit() {
        val changedPaths = synchronized(stateLock) {
            pendingAckChangedPaths.toList().also {
                pendingAckChangedPaths.clear()
            }
        }
        if (changedPaths.isEmpty()) {
            return
        }
        changeTracker.consumeDefinitionDiff(changedPaths)
    }

    private fun scheduleDelayedInitialFullScanLocked(normalizedSourceDirs: List<String>, delayMs: Long) {
        logger.info(
            "ConstRefEngine defer initial full scan until startup stabilizes, " +
                "delayMs=$delayMs, sourceDirCount=${normalizedSourceDirs.size}"
        )
        var scheduledJob: Job? = null
        scheduledJob = taskRunnerManager.runBackgroundSafe(
            jobName = "ConstRefEngine#deferInitialFullScan",
            delayMs = delayMs,
            isNeedLog = false,
        ) {
            synchronized(stateLock) {
                if (delayedInitialFullScanJob == scheduledJob && !isSceneActiveLocked(AnalyzeScene.FULL_SCAN)) {
                    delayedInitialFullScanJob = null
                    launchFullScanLocked(normalizedSourceDirs)
                }
            }
        }
        delayedInitialFullScanJob = scheduledJob
    }

    private fun launchFullScanLocked(normalizedSourceDirs: List<String>) {
        hasLaunchedInitialFullScan = true
        launchSceneTaskLocked(AnalyzeScene.FULL_SCAN) {
            val progressLogger = FullScanProgressLogger(logger, fullScanLogIntervalMs)
            normalizedSourceDirs.forEach { sourceDirPath ->
                val sourceDir = File(sourceDirPath)
                val sourceFiles = sourceDir
                    .listFilesRecursively()
                    .asSequence()
                    .filter { file -> file.isFile && isSourceFile(file.name) }
                    .distinctBy { it.toStdPath() }
                    .toList()
                if (sourceFiles.isNotEmpty()) {
                    val reusablePaths = database.findReusablePathsByLastModified(sourceFiles)
                    reusablePaths.forEach { path ->
                        markAnalyzed(path)
                    }
                    val filesToAnalyze = if (reusablePaths.isEmpty()) {
                        sourceFiles
                    } else {
                        sourceFiles.filter { it.toStdPath() !in reusablePaths }
                    }
                    val startTime = System.currentTimeMillis()
                    analyzeFilesForScene(filesToAnalyze, AnalyzeScene.FULL_SCAN)
                    val costTime = System.currentTimeMillis() - startTime
                    progressLogger.onSourceDirFinished(
                        sourceDirPath = sourceDirPath,
                        sourceFileCount = sourceFiles.size,
                        reusedFileCount = reusablePaths.size,
                        analyzedFileCount = filesToAnalyze.size,
                        costMs = costTime,
                    )
                }
                synchronized(stateLock) {
                    fullScanReadySourceDirs += sourceDirPath
                    notifyStateChangedLocked()
                }
            }
            progressLogger.flush()
        }
    }

    fun dispose() {
        val runtime = synchronized(runtimeLock) {
            val state = runtimeState
            runtimeState = ConstRefRuntimeState.Disabled("disposed")
            (state as? ConstRefRuntimeState.Ready)?.runtime
        }
        synchronized(stateLock) {
            delayedInitialFullScanJob?.cancel()
            delayedInitialFullScanJob = null
            sceneTaskStates.values.forEach {
                it.scheduledJob?.cancel()
                it.runningJob?.cancel()
            }
            cacheCleanupJob?.cancel()
            cacheCleanupJob = null
        }
        sceneTaskScope.cancel()
        analyzer.dispose()
        runtime?.database?.close()
    }

    private fun scheduleCacheCleanup(runtime: ConstRefRuntime) {
        cacheCleanupJob = taskRunnerManager.runBackgroundSafe(
            jobName = "ConstRefEngine#cacheCleanup",
            delayMs = CACHE_CLEANUP_DELAY_MS,
            isNeedLog = false,
        ) {
            runCatching {
                cacheCleaner.cleanupIfNeeded(runtime.database, runtime.repoSharedFingerprintStore)
            }.onFailure {
                logger.debug("ConstRefEngine cacheCleanup failed, reason=${it.message}")
            }
        }
    }

    private fun requireRuntime(actionName: String): ConstRefRuntime {
        return getRuntime(actionName) ?: throw ConstRefRuntimeUnavailableException(actionName)
    }

    private fun getRuntime(actionName: String): ConstRefRuntime? {
        val runtime = synchronized(runtimeLock) {
            when (val state = runtimeState) {
                is ConstRefRuntimeState.Ready -> state.runtime
                is ConstRefRuntimeState.Disabled -> null
                ConstRefRuntimeState.NotInitialized -> {
                    runCatching {
                        runtimeFactory()
                    }.onSuccess {
                        runtimeState = ConstRefRuntimeState.Ready(it)
                        logger.debug("ConstRefEngine runtime initialized by $actionName")
                    }.onFailure {
                        disableRuntimeLocked("runtime init failed by $actionName", it)
                    }.getOrNull()
                }
            }
        }
        if (runtime != null && actionName != "cacheCleanup") {
            scheduleCacheCleanupOnce(runtime)
        }
        return runtime
    }

    private var hasScheduledCacheCleanup = false

    private fun scheduleCacheCleanupOnce(runtime: ConstRefRuntime) {
        synchronized(runtimeLock) {
            if (hasScheduledCacheCleanup || runtimeState !is ConstRefRuntimeState.Ready) {
                return
            }
            hasScheduledCacheCleanup = true
        }
        scheduleCacheCleanup(runtime)
    }

    private fun disableRuntimeLocked(message: String, throwable: Throwable) {
        val runtime = (runtimeState as? ConstRefRuntimeState.Ready)?.runtime
        runtimeState = ConstRefRuntimeState.Disabled(message)
        runCatching {
            runtime?.database?.close()
        }.onFailure {
            logger.debug("ConstRefEngine runtime close after failure failed, reason=${it.message}")
        }
        if (throwable !is ConstRefRuntimeUnavailableException) {
            logRuntimeFailure(message, throwable)
        }
    }

    private fun schedulePendingLocked() {
        launchSceneTaskLocked(AnalyzeScene.FILE_CHANGE) {
            analyzePending(AnalyzeScene.FILE_CHANGE)
        }
    }

    private suspend fun analyzePending(scene: AnalyzeScene) {
        val toAnalyze = synchronized(stateLock) {
            if (pendingAnalyzeFiles.isEmpty()) {
                return
            }
            val files = pendingAnalyzeFiles.toList()
            pendingAnalyzeFiles.clear()
            files
        }
        try {
            analyzeFilesForScene(toAnalyze.map(::File), scene)
        } finally {
            synchronized(stateLock) {
                if (pendingAnalyzeFiles.isNotEmpty()) {
                    schedulePendingLocked()
                }
            }
        }
    }

    private suspend fun analyzeFiles(files: List<File>) {
        analyzeFilesForScene(files, AnalyzeScene.ON_DEMAND)
    }

    private suspend fun analyzeFilesForScene(files: List<File>, scene: AnalyzeScene) {
        if (files.isEmpty()) {
            return
        }
        database.registerPathHints(files.map { it.toStdPath() })
        val existingFiles = mutableListOf<File>()
        files.distinctBy { it.toStdPath() }.forEach { file ->
            val path = file.toStdPath()
            if (!file.exists()) {
                analysisMutex.withLock {
                    database.removeFile(path)
                    removeDefinitionsFromLookupStateLocked(path)
                    changeTracker.onFileDeleted(path)
                }
                markAnalyzed(path)
            } else if (isSourceFile(path)) {
                existingFiles += file
            }
        }
        if (existingFiles.isEmpty()) {
            return
        }

        val checksumMap = mutableMapOf<String, Long>()
        val changedFiles = mutableListOf<File>()
        var mtimeHitCount = 0
        var fingerprintHitCount = 0
        var crcMissCount = 0
        var analysisReuseHitCount = 0
        var checksumPhaseMs = 0L
        existingFiles.forEachIndexed { index, file ->
            val stepMs = measureTimeMillis {
                analysisMutex.withLock {
                    val path = file.toStdPath()
                    val fileLastModified = file.lastModified()

                    val checksum = resolveChecksum(
                        file = file,
                        fileLastModified = fileLastModified,
                        onMtimeHit = { mtimeHitCount++ },
                        onFingerprintHit = { fingerprintHitCount++ },
                        onCrcMiss = { crcMissCount++ },
                    )
                    val previousMtimeChecksum = database.getMtimeMapChecksum(path)
                    if (database.touchFileAnalysis(path, fileLastModified, checksum)) {
                        analysisReuseHitCount++
                        // Reuse hit means file content matches a previously analysed checksum.
                        // We still need to update changeTracker when the reused definitions
                        // differ from the in-memory "previous" snapshot (e.g. A→B→A where B
                        // was the last analysed version but A's cached checksum is now restored).
                        // When definitions are identical (same content re-saved), we must NOT
                        // overwrite existing unconsumed changedKeys in changeTracker.
                        val previousDefinitions = loadPreviousDefinitionsLocked(path, previousMtimeChecksum)
                        val reusedDefinitions = database.getDefinitionsByFileAndChecksum(path, checksum)
                        if (!areSameDefinitions(previousDefinitions, reusedDefinitions)) {
                            changeTracker.updateDefinitionDiff(
                                filePath = path,
                                previousDefinitions = previousDefinitions,
                                currentDefinitions = reusedDefinitions,
                            )
                            updatePreviousDefinitionsLocked(path, reusedDefinitions)
                        }
                        markAnalyzed(path)
                    } else {
                        checksumMap[path] = checksum
                        changedFiles += file
                    }
                }
            }
            checksumPhaseMs += stepMs
            maybeThrottleIo(scene, index + 1)
        }
        if (mtimeHitCount > 0 || fingerprintHitCount > 0 || crcMissCount > 0 || analysisReuseHitCount > 0) {
            logger.debug(
                "ConstRefEngine checksum resolve stats, " +
                    "mtimeHit=$mtimeHitCount, fingerprintHit=$fingerprintHitCount, " +
                    "crcMiss=$crcMissCount, analysisReuseHit=$analysisReuseHitCount"
            )
        }
        if (changedFiles.isEmpty()) {
            if (checksumPhaseMs > SLOW_PHASE_THRESHOLD_MS) {
                logger.debug(
                    "ConstRefEngine analyzeFiles phase breakdown (all reused), " +
                        "fileCount=${existingFiles.size}, checksumMs=$checksumPhaseMs"
                )
            }
            return
        }

        // Phase 1: parse definitions in batches and write to DB without file_analysis_head.
        // Each batch is released from memory after the DB write, avoiding full-list residency.
        // Also pre-load "previous definitions" into sessionCache so Phase 2 can compute diffs
        // without needing getMtimeMapChecksum (which excludes Phase 1 sentinel rows).
        //
        // Lock strategy: read shared state per-file under lock, parse AST without lock (CPU-heavy,
        // no shared state dependency), then batch-write results under lock. This minimizes mutex
        // hold time so concurrent analyzeOnDemand calls are not blocked by full-scan batches.
        var phase1ProcessedCount = 0
        var phase1ParseMs = 0L
        var phase1DbWriteMs = 0L
        var phase1LockWaitMs = 0L
        changedFiles.chunked(analyzeFilesBatchSize).forEach { batch ->
            data class Phase1FileState(
                val path: String,
                val file: File,
                val previousDefinitions: List<ConstDefinition>,
                val checksum: Long,
            )
            // Step 1: read previous definitions under lock (fast per-file DB/cache read).
            val pendingFiles = mutableListOf<Phase1FileState>()
            batch.forEach { file ->
                val lockWaitStart = System.nanoTime()
                val state = analysisMutex.withLock {
                    val lockAcquiredAt = System.nanoTime()
                    phase1LockWaitMs += (lockAcquiredAt - lockWaitStart) / 1_000_000
                    val path = file.toStdPath()
                    val previousDefinitions = loadPreviousDefinitionsLocked(path)
                    Phase1FileState(path, file, previousDefinitions, checksumMap[path] ?: calculateChecksum(file))
                }
                pendingFiles += state
            }
            // Step 2: parse definitions WITHOUT lock (CPU-intensive AST parsing, no shared state).
            val definitionsBatch = mutableListOf<ConstRefCacheDatabase.FileDefinitionsEntry>()
            var batchParseMs = 0L
            pendingFiles.forEach { state ->
                val parseMs = measureTimeMillis {
                    val definitions = runCatching {
                        analyzer.parseDefinitions(listOf(state.file))[state.path].orEmpty()
                    }.getOrElse { error ->
                        val message = "ConstRefEngine failed to parse definitions, " +
                            "file=${state.file.name}, reason=${error.message}"
                        logSceneWarningOrDebug(
                            scene = scene,
                            throwable = error,
                            message = message,
                        )
                        emptyList()
                    }
                    definitionsBatch += ConstRefCacheDatabase.FileDefinitionsEntry(
                        filePath = state.path,
                        lastModified = state.file.lastModified(),
                        checksum = state.checksum,
                        definitions = definitions,
                    )
                }
                batchParseMs += parseMs
            }
            phase1ParseMs += batchParseMs
            // Step 3: batch-write results under lock (fast DB upsert + cache update).
            val dbWriteMs = measureTimeMillis {
                analysisMutex.withLock {
                    database.upsertBatchDefinitions(definitionsBatch)
                    pendingFiles.forEach { state ->
                        updatePreviousDefinitionsLocked(state.path, state.previousDefinitions)
                    }
                }
            }
            phase1DbWriteMs += dbWriteMs
            // Reset KotlinCoreEnvironment after each batch to release the string-intern table
            // that grows ~200 KB per parsed file. Without this, full-scan peak resident heap
            // scales with total file count; with this, it is bounded by batch size.
            analyzer.resetEnvironment()
            phase1ProcessedCount += batch.size
            maybeThrottleIo(scene, phase1ProcessedCount)
        }

        // Phase 2: parse syntax-only reference candidates per batch. Candidate parsing does not
        // depend on DB-stored definitions, so scan order cannot hide earlier references.
        //
        // Lock strategy: split per-file work into three steps to keep AST parsing outside the lock.
        // Step 1 (locked, fast): read previousDefinitions + definitions from DB/cache.
        // Step 2 (unlocked): AST parse via collectHintsAndParseReferences - CPU-intensive, no shared state.
        // Step 3 (batch locked, fast): flush analysis to DB and update shared state.
        // This lets analyzeOnDemand callers acquire analysisMutex between any two files during full scan.
        var phase2ProcessedCount = 0
        var phase2RefParseMs = 0L
        var phase2DbLookupMs = 0L
        var phase2DbWriteMs = 0L
        var phase2LockWaitMs = 0L
        changedFiles.chunked(analyzeFilesBatchSize).forEach { batch ->
            val analysisBatch = mutableListOf<ConstRefCacheDatabase.FileAnalysisEntry>()
            data class FilePendingState(
                val path: String,
                val file: File,
                val previousDefinitions: List<ConstDefinition>,
                val definitions: List<ConstDefinition>,
            )
            val pendingStates = mutableListOf<FilePendingState>()
            // Per-file: Step 1 locked (read state), Step 2 unlocked (AST parse).
            batch.forEach { file ->
                // Step 1: read shared state under lock (fast DB/cache reads only).
                data class Phase2ReadState(
                    val path: String,
                    val previousDefinitions: List<ConstDefinition>,
                    val definitions: List<ConstDefinition>,
                    val checksum: Long,
                )
                val lockWaitStart = System.nanoTime()
                val readState = analysisMutex.withLock {
                    val lockAcquiredAt = System.nanoTime()
                    phase2LockWaitMs += (lockAcquiredAt - lockWaitStart) / 1_000_000
                    val path = file.toStdPath()
                    val previousDefinitions = loadPreviousDefinitionsLocked(path)
                    val checksum = checksumMap[path] ?: calculateChecksum(file)
                    val dbLookupStart = System.nanoTime()
                    val definitions = database.getDefinitionsByFileAndChecksum(path, checksum)
                    phase2DbLookupMs += (System.nanoTime() - dbLookupStart) / 1_000_000
                    sessionCache.clearLookupCache()
                    Phase2ReadState(path, previousDefinitions, definitions, checksum)
                }
                // Step 2: parse reference candidates WITHOUT lock (CPU-intensive AST parse, no shared state).
                val refParseMs = measureTimeMillis {
                    val referenceCandidates = runCatching {
                        analyzer.parseReferenceCandidates(listOf(file))[readState.path].orEmpty()
                    }.getOrElse { error ->
                        val message = "ConstRefEngine failed to parse reference candidates, " +
                            "file=${file.name}, reason=${error.message}"
                        logSceneWarningOrDebug(
                            scene = scene,
                            throwable = error,
                            message = message,
                        )
                        emptyList()
                    }
                    analysisBatch += ConstRefCacheDatabase.FileAnalysisEntry(
                        filePath = readState.path,
                        lastModified = file.lastModified(),
                        checksum = readState.checksum,
                        definitions = readState.definitions,
                        references = emptyList(),
                        referenceCandidates = referenceCandidates,
                    )
                    pendingStates += FilePendingState(
                        readState.path, file, readState.previousDefinitions, readState.definitions
                    )
                }
                phase2RefParseMs += refParseMs
            }
            // Batch lock: flush analysis to DB and update shared state atomically.
            // markAnalyzed is called here so awaitAnalysis observers see consistent data
            // (references written) when the analyzed timestamp appears.
            val dbWriteMs = measureTimeMillis {
                analysisMutex.withLock {
                    database.upsertBatchAnalysis(analysisBatch)
                    pendingStates.forEach { state ->
                        changeTracker.updateDefinitionDiff(
                            filePath = state.path,
                            previousDefinitions = state.previousDefinitions,
                            currentDefinitions = state.definitions,
                        )
                        sessionCache.putFileAnalysis(
                            filePath = state.path,
                            lastModified = state.file.lastModified(),
                            checksum = checksumMap[state.path] ?: 0L,
                            definitions = state.definitions,
                            references = emptyList(),
                        )
                        markAnalyzed(state.path)
                        sessionCache.clearLookupCache()
                    }
                }
            }
            phase2DbWriteMs += dbWriteMs
            // Reset KotlinCoreEnvironment after each batch to release string-intern caches.
            analyzer.resetEnvironment()
            phase2ProcessedCount += batch.size
            maybeThrottleIo(scene, phase2ProcessedCount)
        }

        val totalMs = checksumPhaseMs + phase1ParseMs + phase1DbWriteMs +
            phase2RefParseMs + phase2DbLookupMs + phase2DbWriteMs
        if (totalMs > SLOW_PHASE_THRESHOLD_MS || changedFiles.size <= DETAILED_LOG_FILE_THRESHOLD) {
            val changedFileNames = if (changedFiles.size <= DETAILED_LOG_FILE_THRESHOLD) {
                changedFiles.joinToString(", ") { it.name }
            } else {
                "${changedFiles.size} files"
            }
            logger.debug(
                "ConstRefEngine analyzeFiles phase breakdown, " +
                    "totalMs=$totalMs, changedFiles=[$changedFileNames], " +
                    "checksumMs=$checksumPhaseMs, " +
                    "phase1ParseMs=$phase1ParseMs, phase1DbWriteMs=$phase1DbWriteMs, " +
                    "phase1LockWaitMs=$phase1LockWaitMs, " +
                    "phase2RefMs=$phase2RefParseMs, phase2DbLookupMs=$phase2DbLookupMs, " +
                    "phase2DbWriteMs=$phase2DbWriteMs, phase2LockWaitMs=$phase2LockWaitMs, " +
                    "reuseCount=$analysisReuseHitCount, changedCount=${changedFiles.size}, " +
                    "existingCount=${existingFiles.size}"
            )
        }
    }

    private fun triggerPreCompileAnalyze() {
        synchronized(stateLock) {
            if (pendingAnalyzeFiles.isEmpty()) {
                return
            }
            sceneTaskStates[AnalyzeScene.FILE_CHANGE]?.scheduledJob?.cancel()
            sceneTaskStates[AnalyzeScene.FILE_CHANGE]?.scheduledJob = null
            launchSceneTaskLocked(AnalyzeScene.PRE_COMPILE) {
                analyzePending(AnalyzeScene.PRE_COMPILE)
            }
        }
    }

    private fun movePreviousEditingFileToPendingLocked(nextEditingFile: String) {
        val previousEditingFile = currentEditingFile ?: return
        if (previousEditingFile == nextEditingFile) {
            return
        }
        pendingAnalyzeFiles += previousEditingFile
        schedulePendingLocked()
    }

    private fun flushCurrentEditingFileLocked() {
        val editingFile = currentEditingFile ?: return
        pendingAnalyzeFiles += editingFile
        currentEditingFile = null
    }

    private fun resolveChecksum(
        file: File,
        fileLastModified: Long,
        onMtimeHit: () -> Unit,
        onFingerprintHit: () -> Unit,
        onCrcMiss: () -> Unit,
    ): Long {
        val path = file.toStdPath()
        database.getChecksumByLastModified(path, fileLastModified)?.let { mtimeChecksum ->
            val fingerprintChecksum = queryChecksumFromSharedFingerprint(file)
            if (fingerprintChecksum != null) {
                if (fingerprintChecksum == mtimeChecksum) {
                    onMtimeHit()
                    return mtimeChecksum
                }
                logger.debug(
                    "ConstRefEngine detected mtime checksum mismatch, " +
                        "path=$path, mtimeChecksum=$mtimeChecksum, fingerprintChecksum=$fingerprintChecksum"
                )
                onFingerprintHit()
                return fingerprintChecksum
            }

            // Compatibility path for older fingerprint DB records: fallback to real checksum for safety.
            onCrcMiss()
            val checksum = calculateChecksum(file)
            saveChecksumToSharedFingerprint(file, checksum)
            return checksum
        }

        queryChecksumFromSharedFingerprint(file)?.let { checksum ->
            onFingerprintHit()
            return checksum
        }

        onCrcMiss()
        val checksum = calculateChecksum(file)
        saveChecksumToSharedFingerprint(file, checksum)
        return checksum
    }

    private fun queryChecksumFromSharedFingerprint(file: File): Long? {
        return try {
            repoSharedFingerprintStore.findChecksum(file)
        } catch (t: Throwable) {
            logger.debug("queryChecksumFromSharedFingerprint failed, file=$file", t)
            null
        }
    }

    private fun saveChecksumToSharedFingerprint(file: File, checksum: Long) {
        try {
            repoSharedFingerprintStore.saveChecksum(file, checksum)
        } catch (t: Throwable) {
            logger.debug("saveChecksumToSharedFingerprint failed, file=$file", t)
        }
    }

    private fun calculateChecksum(file: File): Long {
        val crc32 = CRC32()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        file.inputStream().use { input ->
            while (true) {
                val readCount = input.read(buffer)
                if (readCount <= 0) {
                    break
                }
                crc32.update(buffer, 0, readCount)
            }
        }
        return crc32.value
    }

    private suspend fun maybeThrottleIo(scene: AnalyzeScene, processedCount: Int) {
        val sleepMs = ioThrottleSleepMs(scene)
        if (sleepMs <= 0L) {
            return
        }
        if (processedCount % ioThrottleEveryNFiles(scene) != 0) {
            return
        }
        delay(sleepMs)
    }

    /**
     * Parses references for a single file using only DB-stored definitions (no in-memory overlay).
     * Phase 1 must have already written all definitions to DB before calling this.
     * Uses [ConstRefAnalyzer.collectHintsAndParseReferences] to parse the file only once.
     */
    private fun parseReferencesByDbOnly(file: File): List<ConstReference> {
        val stdPath = file.toStdPath()
        return analyzer.collectHintsAndParseReferences(file) { hints ->
            val candidateQueryStart = System.nanoTime()
            val candidateDefinitions = queryCandidateDefinitionsForFile(
                filePath = stdPath,
                hints = hints,
            )
            val candidateQueryMs = (System.nanoTime() - candidateQueryStart) / 1_000_000
            if (candidateQueryMs > SLOW_PHASE_THRESHOLD_MS) {
                logger.debug(
                    "ConstRefEngine parseReferencesByDbOnly candidate query slow, " +
                        "file=${file.name}, candidateQueryMs=$candidateQueryMs, " +
                        "candidateCount=${candidateDefinitions.size}, " +
                        "hintsConstNames=${hints.constNames.size}, " +
                        "hintsClassConstKeys=${hints.classConstKeys.size}, " +
                        "hintsPackageConstKeys=${hints.packageConstKeys.size}, " +
                        "hintsSimpleClassNames=${hints.simpleClassNames.size}"
                )
            }
            if (candidateDefinitions.isEmpty()) {
                null
            } else {
                val allDefinitions = linkedMapOf<String, ConstDefinition>()
                candidateDefinitions.forEach { definition ->
                    allDefinitions[definition.uniqueDefinitionKey()] = definition
                }
                ConstDefinitionIndex(allDefinitions.values)
            }
        }
    }

    private fun parseReferencesByDbSessionMode(
        changedFiles: List<File>,
        parsedDefinitionsByPath: Map<String, List<ConstDefinition>>,
    ): Map<String, List<ConstReference>> {
        if (changedFiles.isEmpty()) {
            return emptyMap()
        }
        val hintsByPath = analyzer.collectReferenceLookupHints(changedFiles)
        val overlayDefinitionsByConstName = mutableMapOf<String, MutableList<ConstDefinition>>()
        val overlayDefinitionsByClassConst = mutableMapOf<Pair<String, String>, MutableList<ConstDefinition>>()
        val overlayDefinitionsByPackageConst = mutableMapOf<Pair<String, String>, MutableList<ConstDefinition>>()
        parsedDefinitionsByPath.values.flatten().forEach { definition ->
            overlayDefinitionsByConstName.getOrPut(definition.constName) { mutableListOf() } += definition
            overlayDefinitionsByClassConst.getOrPut(definition.fqClassName to definition.constName) { mutableListOf() } += definition
            overlayDefinitionsByPackageConst.getOrPut(definition.packageName to definition.constName) { mutableListOf() } += definition
        }

        val referencesByPath = mutableMapOf<String, List<ConstReference>>()
        changedFiles.forEach { file ->
            val stdPath = file.toStdPath()
            val hints = hintsByPath[stdPath] ?: ConstReferenceLookupHints.EMPTY
            if (hints.isEmpty()) {
                referencesByPath[stdPath] = emptyList()
                return@forEach
            }
            val candidateDefinitions = queryCandidateDefinitionsForFile(
                filePath = stdPath,
                hints = hints,
            )
            val allDefinitions = linkedMapOf<String, ConstDefinition>()
            candidateDefinitions.forEach { definition ->
                allDefinitions[definition.uniqueDefinitionKey()] = definition
            }
            collectOverlayDefinitions(
                hints = hints,
                overlayDefinitionsByConstName = overlayDefinitionsByConstName,
                overlayDefinitionsByClassConst = overlayDefinitionsByClassConst,
                overlayDefinitionsByPackageConst = overlayDefinitionsByPackageConst,
            ).forEach { definition ->
                allDefinitions[definition.uniqueDefinitionKey()] = definition
            }
            if (allDefinitions.isEmpty()) {
                referencesByPath[stdPath] = emptyList()
                return@forEach
            }
            val definitionIndex = ConstDefinitionIndex(allDefinitions.values)
            val references = analyzer.parseReferences(listOf(file), definitionIndex)[stdPath].orEmpty()
            referencesByPath[stdPath] = references
        }
        return referencesByPath
    }

    private fun queryCandidateDefinitionsForFile(
        filePath: String,
        hints: ConstReferenceLookupHints,
    ): List<ConstDefinition> {
        if (hints.isEmpty()) {
            return emptyList()
        }
        val candidates = linkedMapOf<String, ConstDefinition>()

        resolveDefinitionsByConstNamesWithCache(
            constNames = hints.constNames,
            scopeFilePath = filePath,
        ).forEach { definition ->
            candidates[definition.uniqueDefinitionKey()] = definition
        }
        resolveDefinitionsByClassConstKeysWithCache(
            classConstKeys = hints.classConstKeys,
            scopeFilePath = filePath,
        ).forEach { definition ->
            candidates[definition.uniqueDefinitionKey()] = definition
        }
        resolveDefinitionsByPackageConstKeysWithCache(
            packageConstKeys = hints.packageConstKeys,
            scopeFilePath = filePath,
        ).forEach { definition ->
            candidates[definition.uniqueDefinitionKey()] = definition
        }

        if (hints.simpleClassConstKeys.isNotEmpty()) {
            // Optimized path: use precise (simpleName, constName) pairs from AST traversal
            // instead of the cartesian product simpleClassNames × constNames.
            val simpleNames = hints.simpleClassConstKeys.map { it.first }.toSet()
            val classNameMap = resolveClassesBySimpleNamesWithCache(
                simpleClassNames = simpleNames,
                scopeFilePath = filePath,
            )
            val classConstKeys = linkedSetOf<Pair<String, String>>()
            hints.simpleClassConstKeys.forEach { (simpleName, constName) ->
                classNameMap[simpleName]?.forEach { fqClassName ->
                    classConstKeys += fqClassName to constName
                }
            }
            // Exclude keys already queried via hints.classConstKeys to avoid duplicate work.
            val newClassConstKeys = classConstKeys - hints.classConstKeys
            if (newClassConstKeys.isNotEmpty()) {
                resolveDefinitionsByClassConstKeysWithCache(
                    classConstKeys = newClassConstKeys,
                    scopeFilePath = filePath,
                ).forEach { definition ->
                    candidates[definition.uniqueDefinitionKey()] = definition
                }
            }
        } else if (hints.simpleClassNames.isNotEmpty() && hints.constNames.isNotEmpty()) {
            // Fallback for callers that don't populate simpleClassConstKeys (e.g. old code paths).
            val classNames = resolveClassesBySimpleNamesWithCache(
                simpleClassNames = hints.simpleClassNames,
                scopeFilePath = filePath,
            ).values
                .flatten()
                .toSet()
            if (classNames.isNotEmpty()) {
                val classConstKeys = linkedSetOf<Pair<String, String>>()
                classNames.forEach { fqClassName ->
                    hints.constNames.forEach { constName ->
                        classConstKeys += fqClassName to constName
                    }
                }
                resolveDefinitionsByClassConstKeysWithCache(
                    classConstKeys = classConstKeys,
                    scopeFilePath = filePath,
                ).forEach { definition ->
                    candidates[definition.uniqueDefinitionKey()] = definition
                }
            }
        }
        return candidates.values.toList()
    }

    private fun resolveDefinitionsByConstNamesWithCache(
        constNames: Set<String>,
        scopeFilePath: String,
    ): List<ConstDefinition> {
        val normalizedNames = constNames
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSet()
        if (normalizedNames.isEmpty()) {
            return emptyList()
        }
        val definitions = linkedMapOf<String, ConstDefinition>()
        val missingNames = linkedSetOf<String>()
        normalizedNames.forEach { constName ->
            val cached = sessionCache.getConstNameLookup(constName)
            if (cached != null) {
                cached.forEach { definition ->
                    definitions[definition.uniqueDefinitionKey()] = definition
                }
            } else {
                missingNames += constName
            }
        }
        if (missingNames.isNotEmpty()) {
            val queriedDefinitions = database.queryDefinitionsByConstNames(
                constNames = missingNames,
                scopeFilePaths = listOf(scopeFilePath),
            )
            val queriedByConst = queriedDefinitions.groupBy { it.constName }
            missingNames.forEach { constName ->
                sessionCache.putConstNameLookup(constName, queriedByConst[constName].orEmpty())
            }
            queriedDefinitions.forEach { definition ->
                definitions[definition.uniqueDefinitionKey()] = definition
            }
        }
        return definitions.values.toList()
    }

    private fun resolveDefinitionsByClassConstKeysWithCache(
        classConstKeys: Set<Pair<String, String>>,
        scopeFilePath: String,
    ): List<ConstDefinition> {
        val normalizedKeys = classConstKeys
            .mapNotNull { (fqClassName, constName) ->
                val normalizedClass = fqClassName.trim()
                val normalizedName = constName.trim()
                if (normalizedClass.isBlank() || normalizedName.isBlank()) {
                    null
                } else {
                    normalizedClass to normalizedName
                }
            }
            .toSet()
        if (normalizedKeys.isEmpty()) {
            return emptyList()
        }
        val definitions = linkedMapOf<String, ConstDefinition>()
        val missingKeys = linkedSetOf<Pair<String, String>>()
        normalizedKeys.forEach { (fqClassName, constName) ->
            val cached = sessionCache.getClassConstLookup(fqClassName, constName)
            if (cached != null) {
                cached.forEach { definition ->
                    definitions[definition.uniqueDefinitionKey()] = definition
                }
            } else {
                missingKeys += fqClassName to constName
            }
        }
        if (missingKeys.isNotEmpty()) {
            val queriedDefinitions = database.queryDefinitionsByClassConstKeys(
                classConstKeys = missingKeys,
                scopeFilePaths = listOf(scopeFilePath),
            )
            val queriedByKey = queriedDefinitions.groupBy { it.fqClassName to it.constName }
            missingKeys.forEach { (fqClassName, constName) ->
                sessionCache.putClassConstLookup(
                    fqClassName = fqClassName,
                    constName = constName,
                    definitions = queriedByKey[fqClassName to constName].orEmpty(),
                )
            }
            queriedDefinitions.forEach { definition ->
                definitions[definition.uniqueDefinitionKey()] = definition
            }
        }
        return definitions.values.toList()
    }

    private fun resolveDefinitionsByPackageConstKeysWithCache(
        packageConstKeys: Set<Pair<String, String>>,
        scopeFilePath: String,
    ): List<ConstDefinition> {
        val normalizedKeys = packageConstKeys
            .mapNotNull { (packageName, constName) ->
                val normalizedPackage = packageName.trim()
                val normalizedName = constName.trim()
                if (normalizedPackage.isBlank() || normalizedName.isBlank()) {
                    null
                } else {
                    normalizedPackage to normalizedName
                }
            }
            .toSet()
        if (normalizedKeys.isEmpty()) {
            return emptyList()
        }
        val definitions = linkedMapOf<String, ConstDefinition>()
        val missingKeys = linkedSetOf<Pair<String, String>>()
        normalizedKeys.forEach { (packageName, constName) ->
            val cached = sessionCache.getPackageConstLookup(packageName, constName)
            if (cached != null) {
                cached.forEach { definition ->
                    definitions[definition.uniqueDefinitionKey()] = definition
                }
            } else {
                missingKeys += packageName to constName
            }
        }
        if (missingKeys.isNotEmpty()) {
            val queriedDefinitions = database.queryDefinitionsByPackageConstKeys(
                packageConstKeys = missingKeys,
                scopeFilePaths = listOf(scopeFilePath),
            )
            val queriedByKey = queriedDefinitions.groupBy { it.packageName to it.constName }
            missingKeys.forEach { (packageName, constName) ->
                sessionCache.putPackageConstLookup(
                    packageName = packageName,
                    constName = constName,
                    definitions = queriedByKey[packageName to constName].orEmpty(),
                )
            }
            queriedDefinitions.forEach { definition ->
                definitions[definition.uniqueDefinitionKey()] = definition
            }
        }
        return definitions.values.toList()
    }

    private fun resolveClassesBySimpleNamesWithCache(
        simpleClassNames: Set<String>,
        scopeFilePath: String,
    ): Map<String, Set<String>> {
        val normalizedNames = simpleClassNames
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSet()
        if (normalizedNames.isEmpty()) {
            return emptyMap()
        }
        val classesBySimpleName = mutableMapOf<String, Set<String>>()
        val missingNames = linkedSetOf<String>()
        normalizedNames.forEach { simpleName ->
            val cached = sessionCache.getSimpleClassLookup(simpleName)
            if (cached != null) {
                classesBySimpleName[simpleName] = cached
            } else {
                missingNames += simpleName
            }
        }
        if (missingNames.isNotEmpty()) {
            val queried = database.queryClassesBySimpleNames(
                simpleNames = missingNames,
                scopeFilePaths = listOf(scopeFilePath),
            )
            missingNames.forEach { simpleName ->
                val classes = queried[simpleName].orEmpty()
                sessionCache.putSimpleClassLookup(simpleName, classes)
                classesBySimpleName[simpleName] = classes
            }
        }
        return classesBySimpleName
    }

    private fun collectOverlayDefinitions(
        hints: ConstReferenceLookupHints,
        overlayDefinitionsByConstName: Map<String, List<ConstDefinition>>,
        overlayDefinitionsByClassConst: Map<Pair<String, String>, List<ConstDefinition>>,
        overlayDefinitionsByPackageConst: Map<Pair<String, String>, List<ConstDefinition>>,
    ): List<ConstDefinition> {
        val definitions = linkedMapOf<String, ConstDefinition>()
        hints.constNames.forEach { constName ->
            overlayDefinitionsByConstName[constName].orEmpty().forEach { definition ->
                definitions[definition.uniqueDefinitionKey()] = definition
            }
        }
        hints.classConstKeys.forEach { classConstKey ->
            overlayDefinitionsByClassConst[classConstKey].orEmpty().forEach { definition ->
                definitions[definition.uniqueDefinitionKey()] = definition
            }
        }
        hints.packageConstKeys.forEach { packageConstKey ->
            overlayDefinitionsByPackageConst[packageConstKey].orEmpty().forEach { definition ->
                definitions[definition.uniqueDefinitionKey()] = definition
            }
        }
        return definitions.values.toList()
    }

    private fun loadPreviousDefinitionsLocked(
        filePath: String,
        mtimeChecksum: Long? = database.getMtimeMapChecksum(filePath),
    ): List<ConstDefinition> {
        val cachedDefinitions = sessionCache.getFileDefinitions(filePath)
        if (cachedDefinitions != null) {
            return cachedDefinitions
        }
        if (mtimeChecksum != null) {
            val definitions = database.getDefinitionsByFileAndChecksum(filePath, mtimeChecksum)
            updatePreviousDefinitionsLocked(filePath, definitions)
            return definitions
        }
        return emptyList()
    }

    /**
     * Updates the in-memory "previous definitions" snapshot used by [loadPreviousDefinitionsLocked]
     * so that the next diff is based on the correct baseline.
     */
    private fun updatePreviousDefinitionsLocked(filePath: String, definitions: List<ConstDefinition>) {
        sessionCache.putFileDefinitions(filePath, definitions)
    }

    private fun areSameDefinitions(a: List<ConstDefinition>, b: List<ConstDefinition>): Boolean {
        if (a.size != b.size) return false
        val signaturesA = a.map { "${it.fqClassName}|${it.constName}|${it.constType}:${it.constValue.orEmpty()}" }.toSet()
        val signaturesB = b.map { "${it.fqClassName}|${it.constName}|${it.constType}:${it.constValue.orEmpty()}" }.toSet()
        return signaturesA == signaturesB
    }

    private fun removeDefinitionsFromLookupStateLocked(filePath: String) {
        sessionCache.removeFile(filePath)
        sessionCache.clearLookupCache()
    }

    private fun ConstDefinition.uniqueDefinitionKey(): String {
        return "$filePath|$fqClassName|$constName|$constType|${constValue.orEmpty()}"
    }

    private fun markAnalyzed(path: String) {
        synchronized(stateLock) {
            analyzedAt[path] = System.currentTimeMillis()
            trimAnalyzedAtLocked()
            notifyStateChangedLocked()
        }
    }

    private fun trimAnalyzedAtLocked() {
        if (analyzedAt.size <= maxAnalyzedHistory) {
            return
        }
        val removeCount = analyzedAt.size - (maxAnalyzedHistory / 2)
        analyzedAt.entries
            .sortedBy { it.value }
            .take(removeCount)
            .forEach { analyzedAt.remove(it.key) }
    }

    private fun resolveRelatedSourceDirsLocked(targetPaths: List<String>): Set<String> {
        if (trackedSourceDirs.isEmpty()) {
            return emptySet()
        }
        return targetPaths.mapNotNull { path ->
            trackedSourceDirs
                .filter { sourceDir -> path == sourceDir || path.startsWith("$sourceDir/") }
                .maxByOrNull { it.length }
        }.toSet()
    }

    /**
     * Check if a source directory should skip full scan readiness requirement.
     * Always returns true because on-demand analysis is sufficient for awaitAnalysis scenarios.
     * Full scan runs in background for indexing but should not block compilation.
     */
    private fun shouldSkipFullScanRequirement(@Suppress("UNUSED_PARAMETER") sourceDir: String): Boolean {
        return true
    }

    private fun notifyStateChangedLocked() {
        @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
        (stateLock as java.lang.Object).notifyAll()
    }

    private fun waitStateChangedLocked(waitMs: Long) {
        @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
        (stateLock as java.lang.Object).wait(waitMs)
    }

    private fun launchSceneTaskLocked(scene: AnalyzeScene, action: suspend () -> Unit) {
        val sceneState = sceneTaskStates.getValue(scene)
        sceneState.scheduledJob?.cancel()
        val dispatcher = when (scene) {
            AnalyzeScene.FULL_SCAN -> fullScanDispatcher
            else -> taskRunnerManager.dispatcher
        }
        var scheduledJob: Job? = null
        scheduledJob = sceneTaskScope.launch(dispatcher, start = CoroutineStart.LAZY) {
            val shouldRun = synchronized(stateLock) {
                val state = sceneTaskStates.getValue(scene)
                if (state.scheduledJob == scheduledJob) {
                    state.scheduledJob = null
                }
                if (state.runningJob?.isActive == true) {
                    false
                } else {
                    state.runningJob = scheduledJob
                    true
                }
            }
            if (!shouldRun) {
                return@launch
            }
            try {
                action()
            } catch (_: CancellationException) {
                // Normal during scene rescheduling or engine disposal.
            } catch (t: Throwable) {
                logSceneWarningOrDebug(scene, "ConstRefEngine scene ${scene.name} failed", t)
            } finally {
                synchronized(stateLock) {
                    val state = sceneTaskStates.getValue(scene)
                    if (state.runningJob == scheduledJob) {
                        state.runningJob = null
                    }
                    notifyStateChangedLocked()
                }
            }
        }
        sceneState.scheduledJob = scheduledJob
        checkNotNull(scheduledJob).start()
    }

    private fun hasEnabledIoThrottle(): Boolean {
        return fullScanIoThrottleSleepMs > 0L ||
            fileChangeIoThrottleSleepMs > 0L ||
            preCompileIoThrottleSleepMs > 0L ||
            onDemandIoThrottleSleepMs > 0L
    }

    private fun formatThrottle(sleepMs: Long, everyNFiles: Int): String {
        return "${sleepMs}ms/${everyNFiles}files"
    }

    private fun ioThrottleSleepMs(scene: AnalyzeScene): Long {
        return when (scene) {
            AnalyzeScene.FULL_SCAN -> fullScanIoThrottleSleepMs
            AnalyzeScene.FILE_CHANGE -> fileChangeIoThrottleSleepMs
            AnalyzeScene.PRE_COMPILE -> preCompileIoThrottleSleepMs
            AnalyzeScene.ON_DEMAND -> onDemandIoThrottleSleepMs
        }
    }

    private fun ioThrottleEveryNFiles(scene: AnalyzeScene): Int {
        return when (scene) {
            AnalyzeScene.FULL_SCAN -> fullScanIoThrottleEveryNFiles
            AnalyzeScene.FILE_CHANGE -> fileChangeIoThrottleEveryNFiles
            AnalyzeScene.PRE_COMPILE -> preCompileIoThrottleEveryNFiles
            AnalyzeScene.ON_DEMAND -> onDemandIoThrottleEveryNFiles
        }
    }

    /**
     * Enqueues deleted paths and schedules asynchronous DB cleanup.
     * This avoids blocking caller threads (including EDT) on DB monitor contention.
     */
    private fun enqueueDeleteCleanup(path: String) {
        synchronized(stateLock) {
            pendingDeleteCleanupPaths += path
            if (deleteCleanupJob?.isActive == true) {
                return
            }
            deleteCleanupJob = sceneTaskScope.launch(Dispatchers.IO) {
                while (true) {
                    val paths = synchronized(stateLock) {
                        if (pendingDeleteCleanupPaths.isEmpty()) {
                            deleteCleanupJob = null
                            return@launch
                        }
                        val snapshot = pendingDeleteCleanupPaths.toList()
                        pendingDeleteCleanupPaths.clear()
                        snapshot
                    }
                    paths.forEach { deletedPath ->
                        val cleanupSucceeded = cleanupDeletedPathWithRetry(deletedPath)
                        if (!cleanupSucceeded) {
                            requeueDeleteCleanupLater(deletedPath)
                        }
                    }
                }
            }
        }
    }

    private suspend fun cleanupDeletedPathWithRetry(deletedPath: String): Boolean {
        var attempt = 1
        var delayMs = DELETE_CLEANUP_INITIAL_RETRY_DELAY_MS
        while (attempt <= DELETE_CLEANUP_MAX_ATTEMPTS) {
            val elapsedMs = measureTimeMillis {
                try {
                    if (isSourceFile(deletedPath)) {
                        database.removeFile(deletedPath)
                    }
                    database.removeFilesByPrefix("$deletedPath/")
                    logger.debug("ConstRefEngine delete cleanup finished, path=$deletedPath, attempt=$attempt")
                    return true
                } catch (t: Exception) {
                    if (!isSqliteBusy(t)) {
                        logger.debug("ConstRefEngine delete cleanup failed, path=$deletedPath, attempt=$attempt")
                        return true
                    }
                    if (attempt >= DELETE_CLEANUP_MAX_ATTEMPTS) {
                        logger.debug("ConstRefEngine delete cleanup busy, requeue path=$deletedPath, attempt=$attempt")
                        return false
                    }
                    logger.debug("ConstRefEngine delete cleanup busy, retry path=$deletedPath, attempt=$attempt, waitMs=$delayMs")
                }
            }
            logger.debug("ConstRefEngine delete cleanup attempt cost, path=$deletedPath, attempt=$attempt, costMs=$elapsedMs")
            delay(delayMs)
            delayMs = (delayMs * 2).coerceAtMost(DELETE_CLEANUP_MAX_RETRY_DELAY_MS)
            attempt++
        }
        return false
    }

    private fun requeueDeleteCleanupLater(path: String) {
        synchronized(stateLock) {
            if (!pendingDeleteRequeuePaths.add(path)) {
                return
            }
        }
        sceneTaskScope.launch(Dispatchers.IO) {
            delay(DELETE_CLEANUP_REQUEUE_DELAY_MS)
            synchronized(stateLock) {
                pendingDeleteRequeuePaths.remove(path)
            }
            enqueueDeleteCleanup(path)
        }
    }

    private fun mayAffectConstRefIndexLocked(path: String): Boolean {
        if (trackedSourceDirs.isEmpty()) {
            return isSourceFile(path) && !isIgnoredConstRefDeletePath(path)
        }
        val relatedSourceDir = trackedSourceDirs
            .filter { sourceDir -> path == sourceDir || path.startsWith("$sourceDir/") }
            .maxByOrNull { it.length }
            ?: return false
        if (isSourceFile(path)) {
            return true
        }
        return path == relatedSourceDir || isDirectoryPathCandidate(path)
    }

    private fun isIgnoredConstRefDeletePath(path: String): Boolean {
        val normalizedPath = path.replace('\\', '/')
        return normalizedPath.contains("/build/intermediates/") ||
            normalizedPath.contains("/build/tmp/") ||
            normalizedPath.contains("/.gradle/") ||
            normalizedPath.contains("/.idea/")
    }

    private fun isDirectoryPathCandidate(path: String): Boolean {
        val name = path.substringAfterLast('/')
        return !name.contains('.')
    }

    private fun isSqliteBusy(throwable: Throwable): Boolean {
        var current: Throwable? = throwable
        while (current != null) {
            val message = current.message.orEmpty().lowercase()
            if (message.contains("sqlite_busy") ||
                message.contains("database is locked") ||
                message.contains("database file is locked")
            ) {
                return true
            }
            current = current.cause
        }
        return false
    }

    private fun logSceneWarningOrDebug(scene: AnalyzeScene, message: String, throwable: Throwable? = null) {
        if (scene.isUserTriggered()) {
            if (throwable == null) {
                logger.warn(message)
            } else {
                logger.warn(message, throwable)
            }
            return
        }
        logger.debug(withThrowableReason(message, throwable))
    }

    private fun logRuntimeFailure(message: String, throwable: Throwable) {
        if (isUserTriggeredRuntimeAction(message)) {
            logger.warn("ConstRefEngine $message, fallback to no-op const-ref", throwable)
        } else {
            logger.debug(
                "ConstRefEngine $message, fallback to no-op const-ref, " +
                    "reason=${throwable.message}"
            )
        }
    }

    private fun isUserTriggeredRuntimeAction(message: String): Boolean {
        return message.contains("awaitAnalysis") ||
            message.contains("analyzeOnDemand") ||
            message.contains("getEffectedFiles")
    }

    private fun withThrowableReason(message: String, throwable: Throwable?): String {
        val reason = throwable?.message ?: return message
        return "$message, reason=$reason"
    }

    private fun isSceneActiveLocked(scene: AnalyzeScene): Boolean {
        val state = sceneTaskStates.getValue(scene)
        return state.runningJob?.isActive == true || state.scheduledJob?.isActive == true
    }

    private fun isSourceFile(path: String): Boolean {
        return path.endsWith(".java") || path.endsWith(".kt")
    }

    private class FullScanProgressLogger(
        private val logger: Logger,
        private val intervalMs: Long,
    ) {
        private var batchDirCount = 0
        private var batchSourceFileCount = 0
        private var batchReusedFileCount = 0
        private var batchAnalyzedFileCount = 0
        private var batchCostMs = 0L
        private var totalDirCount = 0
        private var totalSourceFileCount = 0
        private var totalReusedFileCount = 0
        private var totalAnalyzedFileCount = 0
        private var totalCostMs = 0L
        private var lastSourceDirPath: String? = null
        private var lastLogTimestampMs = System.currentTimeMillis()

        fun onSourceDirFinished(
            sourceDirPath: String,
            sourceFileCount: Int,
            reusedFileCount: Int,
            analyzedFileCount: Int,
            costMs: Long,
        ) {
            batchDirCount++
            batchSourceFileCount += sourceFileCount
            batchReusedFileCount += reusedFileCount
            batchAnalyzedFileCount += analyzedFileCount
            batchCostMs += costMs
            totalDirCount++
            totalSourceFileCount += sourceFileCount
            totalReusedFileCount += reusedFileCount
            totalAnalyzedFileCount += analyzedFileCount
            totalCostMs += costMs
            lastSourceDirPath = sourceDirPath

            val now = System.currentTimeMillis()
            if (now - lastLogTimestampMs >= intervalMs) {
                emitProgressLog(isFinal = false)
                lastLogTimestampMs = now
            }
        }

        fun flush() {
            emitProgressLog(isFinal = true)
            lastLogTimestampMs = System.currentTimeMillis()
        }

        private fun emitProgressLog(isFinal: Boolean) {
            if (batchDirCount <= 0 && !(isFinal && totalDirCount > 0)) {
                return
            }
            logger.debug(
                "ConstRefEngine full scan progress, final=$isFinal, " +
                    "batchDirs=$batchDirCount, batchFiles=$batchSourceFileCount, " +
                    "batchReused=$batchReusedFileCount, batchAnalyzed=$batchAnalyzedFileCount, " +
                    "batchCost=${batchCostMs}ms, totalDirs=$totalDirCount, totalFiles=$totalSourceFileCount, " +
                    "totalReused=$totalReusedFileCount, totalAnalyzed=$totalAnalyzedFileCount, " +
                    "totalCost=${totalCostMs}ms, lastSourceDir=$lastSourceDirPath"
            )
            batchDirCount = 0
            batchSourceFileCount = 0
            batchReusedFileCount = 0
            batchAnalyzedFileCount = 0
            batchCostMs = 0L
        }
    }

    data class AnalysisReadiness(
        val isReady: Boolean,
        val unreadyPaths: List<String> = emptyList(),
        val pendingSourceDirs: List<String> = emptyList(),
    ) {
        companion object {
            val READY = AnalysisReadiness(isReady = true)
        }
    }

    private enum class AnalyzeScene {
        FULL_SCAN,
        FILE_CHANGE,
        PRE_COMPILE,
        ON_DEMAND,
        ;

        fun isUserTriggered(): Boolean {
            return this == PRE_COMPILE || this == ON_DEMAND
        }
    }

    private data class SceneTaskState(
        var scheduledJob: Job? = null,
        var runningJob: Job? = null,
    )

    private data class ConstRefRuntime(
        val database: ConstRefCacheDatabase,
        val repoSharedFingerprintStore: RepoSharedFingerprintStore,
        val impactResolver: ConstRefImpactResolver,
    )

    private sealed class ConstRefRuntimeState {
        object NotInitialized : ConstRefRuntimeState()
        data class Ready(val runtime: ConstRefRuntime) : ConstRefRuntimeState()
        data class Disabled(val reason: String) : ConstRefRuntimeState()
    }

    private class ConstRefRuntimeUnavailableException(actionName: String) :
        IllegalStateException("ConstRef runtime is unavailable for $actionName")

    companion object {
        private const val IO_THROTTLE_MS_PROPERTY = "jugg.constref.io.throttle.ms"
        private const val IO_THROTTLE_EVERY_PROPERTY = "jugg.constref.io.throttle.every"
        private const val FULL_SCAN_IO_THROTTLE_MS_PROPERTY = "jugg.constref.fullscan.io.throttle.ms"
        private const val FULL_SCAN_IO_THROTTLE_EVERY_PROPERTY = "jugg.constref.fullscan.io.throttle.every"
        private const val FILE_CHANGE_IO_THROTTLE_MS_PROPERTY = "jugg.constref.filechange.io.throttle.ms"
        private const val FILE_CHANGE_IO_THROTTLE_EVERY_PROPERTY = "jugg.constref.filechange.io.throttle.every"
        private const val PRE_COMPILE_IO_THROTTLE_MS_PROPERTY = "jugg.constref.precompile.io.throttle.ms"
        private const val PRE_COMPILE_IO_THROTTLE_EVERY_PROPERTY = "jugg.constref.precompile.io.throttle.every"
        private const val ON_DEMAND_IO_THROTTLE_MS_PROPERTY = "jugg.constref.ondemand.io.throttle.ms"
        private const val ON_DEMAND_IO_THROTTLE_EVERY_PROPERTY = "jugg.constref.ondemand.io.throttle.every"
        private const val FULL_SCAN_LOG_INTERVAL_MS_PROPERTY = "jugg.constref.full.scan.log.interval.ms"
        private const val SESSION_FILE_CACHE_MAX_PROPERTY = "jugg.constref.session.file.cache.max"
        private const val SESSION_LOOKUP_CACHE_MAX_PROPERTY = "jugg.constref.session.lookup.cache.max"
        private const val SESSION_CACHE_TTL_MS_PROPERTY = "jugg.constref.session.cache.ttl.ms"
        private const val BATCH_SIZE_PROPERTY = "jugg.constref.batch.size"
        private const val DEFAULT_FULL_SCAN_IO_THROTTLE_MS = 3000L
        private const val DEFAULT_FULL_SCAN_IO_THROTTLE_EVERY = 50
        private const val DEFAULT_FILE_CHANGE_IO_THROTTLE_MS = 500L
        private const val DEFAULT_FILE_CHANGE_IO_THROTTLE_EVERY = 200
        private const val DEFAULT_PRE_COMPILE_IO_THROTTLE_MS = 0L
        private const val DEFAULT_PRE_COMPILE_IO_THROTTLE_EVERY = 1
        private const val DEFAULT_ON_DEMAND_IO_THROTTLE_MS = 0L
        private const val DEFAULT_ON_DEMAND_IO_THROTTLE_EVERY = 1
        private const val DEFAULT_FULL_SCAN_LOG_INTERVAL_MS = 5000L
        private const val DEFAULT_SESSION_FILE_CACHE_MAX = 500
        private const val DEFAULT_SESSION_LOOKUP_CACHE_MAX = 4000
        private const val DEFAULT_SESSION_CACHE_TTL_MS = 15L * 60L * 1000L
        private const val DEFAULT_BATCH_SIZE = 50
        private const val CACHE_CLEANUP_DELAY_MS = 120_000L
        private const val SLOW_PHASE_THRESHOLD_MS = 500L
        private const val DETAILED_LOG_FILE_THRESHOLD = 5
        private const val PER_FILE_ANALYZE_TIMEOUT_MS = 5_000L
        private const val DELETE_CLEANUP_INITIAL_RETRY_DELAY_MS = 200L
        private const val DELETE_CLEANUP_MAX_RETRY_DELAY_MS = 5_000L
        private const val DELETE_CLEANUP_MAX_ATTEMPTS = 3
        private const val DELETE_CLEANUP_REQUEUE_DELAY_MS = 30_000L

        private fun readNonNegativeLongProperty(property: String, defaultValue: Long): Long {
            return System.getProperty(property)?.toLongOrNull()?.coerceAtLeast(0L) ?: defaultValue
        }

        private fun readPositiveIntProperty(property: String, defaultValue: Int): Int {
            return System.getProperty(property)?.toIntOrNull()?.coerceAtLeast(1) ?: defaultValue
        }

        private fun readSceneNonNegativeLongProperty(property: String, defaultValue: Long): Long {
            return System.getProperty(property)?.toLongOrNull()?.coerceAtLeast(0L)
                ?: System.getProperty(IO_THROTTLE_MS_PROPERTY)?.toLongOrNull()?.coerceAtLeast(0L)
                ?: defaultValue
        }

        private fun readScenePositiveIntProperty(property: String, defaultValue: Int): Int {
            return System.getProperty(property)?.toIntOrNull()?.coerceAtLeast(1)
                ?: System.getProperty(IO_THROTTLE_EVERY_PROPERTY)?.toIntOrNull()?.coerceAtLeast(1)
                ?: defaultValue
        }
    }
}
