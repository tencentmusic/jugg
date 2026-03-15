package com.sickworm.intellij.jugg.compiler.constref

import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.jugg.compiler.listFilesRecursively
import com.sickworm.intellij.jugg.project.IBackgroundTaskRunner
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
import java.util.zip.CRC32
import kotlin.system.measureTimeMillis

/**
 * Coordinates const-ref analysis lifecycle and exposes readiness/impact APIs to deploy flow.
 */
class ConstRefEngine(
    private val analyzer: ConstRefAnalyzer,
    private val database: ConstRefCacheDatabase,
    private val logger: Logger,
    private var backgroundTaskRunner: IBackgroundTaskRunner,
    private val repoSharedFingerprintStore: RepoSharedFingerprintStore,
) {
    private val maxAnalyzedHistory = 4096
    private val analysisMutex = Mutex()
    private val sceneTaskScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @OptIn(ExperimentalCoroutinesApi::class)
    private val fullScanDispatcher = Dispatchers.IO.limitedParallelism(1)
    private val stateLock = Any()
    private val changeTracker = ConstRefChangeTracker()
    private val impactResolver = ConstRefImpactResolver(database)
    private val sessionCache = ConstRefSessionCache(
        fileCacheMaxFiles = readPositiveIntProperty(SESSION_FILE_CACHE_MAX_PROPERTY, DEFAULT_SESSION_FILE_CACHE_MAX),
        lookupCacheMaxKeys = readPositiveIntProperty(SESSION_LOOKUP_CACHE_MAX_PROPERTY, DEFAULT_SESSION_LOOKUP_CACHE_MAX),
        ttlMs = readNonNegativeLongProperty(SESSION_CACHE_TTL_MS_PROPERTY, DEFAULT_SESSION_CACHE_TTL_MS),
    )
    private val pendingAnalyzeFiles = linkedSetOf<String>()
    private val pendingDeleteCleanupPaths = linkedSetOf<String>()
    private var deleteCleanupJob: Job? = null
    private var currentEditingFile: String? = null
    private val analyzedAt = mutableMapOf<String, Long>()
    private val trackedSourceDirs = mutableListOf<String>()
    private val fullScanReadySourceDirs = mutableSetOf<String>()
    private val cacheCleaner = ConstRefCacheCleaner(logger)
    private val sceneTaskStates = mutableMapOf(
        AnalyzeScene.FULL_SCAN to SceneTaskState(),
        AnalyzeScene.FILE_CHANGE to SceneTaskState(),
        AnalyzeScene.PRE_COMPILE to SceneTaskState(),
    )
    private val ioThrottleSleepMs: Long =
        readNonNegativeLongProperty(IO_THROTTLE_MS_PROPERTY, DEFAULT_IO_THROTTLE_MS)
    private val ioThrottleEveryNFiles: Int =
        readPositiveIntProperty(IO_THROTTLE_EVERY_PROPERTY, DEFAULT_IO_THROTTLE_EVERY)
    private val analyzeFilesBatchSize: Int =
        readPositiveIntProperty(BATCH_SIZE_PROPERTY, DEFAULT_BATCH_SIZE)
    private val fullScanLogIntervalMs: Long =
        readNonNegativeLongProperty(FULL_SCAN_LOG_INTERVAL_MS_PROPERTY, DEFAULT_FULL_SCAN_LOG_INTERVAL_MS)

    init {
        if (ioThrottleSleepMs > 0L) {
            logger.info("ConstRefEngine io throttle enabled, " +
                    "sleepMs=$ioThrottleSleepMs, everyNFiles=$ioThrottleEveryNFiles")
        }
        scheduleCacheCleanup()
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
            schedulePendingLocked()
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

        if (analyzePaths.isNotEmpty()) {
            val costMs = measureTimeMillis {
                runBlocking {
                    analyzeFiles(analyzePaths.map(::File))
                }
            }
            logger.debug(
                "ConstRefEngine analyzeOnDemand finished, " +
                    "targetPathCount=${targetPaths.size}, analyzedPathCount=${analyzePaths.size}, cost=${costMs}ms"
            )
        }

        return synchronized(stateLock) {
            val unreadyPaths = targetPaths.filter { path ->
                File(path).exists() && (analyzedAt[path] ?: 0L) <= 0L
            }
            if (unreadyPaths.isEmpty()) {
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
        database.registerPathHints(normalizedSourceDirs)
        synchronized(stateLock) {
            trackedSourceDirs.clear()
            trackedSourceDirs += normalizedSourceDirs
            fullScanReadySourceDirs.clear()
            if (isSceneActiveLocked(AnalyzeScene.FULL_SCAN)) {
                return
            }
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
                        analyzeFiles(filesToAnalyze)
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
    }

    fun getEffectedFiles(changedFilePaths: Collection<String>): List<EffectedConstRef> {
        val changedPaths = changedFilePaths
            .map { File(it).toStdPath() }
            .filter { isSourceFile(it) }
            .distinct()
        if (changedPaths.isEmpty()) {
            return emptyList()
        }
        val (changedKeys, removedKeys) = changeTracker.consumeDefinitionDiff(changedPaths)
        return impactResolver.getEffectedFiles(
            changedPaths = changedPaths,
            changedDefinitionKeys = changedKeys,
            removedDefinitionKeys = removedKeys,
        )
    }

    fun setBackgroundTaskRunner(backgroundTaskRunner: IBackgroundTaskRunner) {
        this.backgroundTaskRunner = backgroundTaskRunner
        scheduleCacheCleanup()
    }

    fun dispose() {
        synchronized(stateLock) {
            sceneTaskStates.values.forEach {
                it.scheduledJob?.cancel()
                it.runningJob?.cancel()
            }
        }
        sceneTaskScope.cancel()
        analyzer.dispose()
        database.close()
    }

    private fun scheduleCacheCleanup() {
        backgroundTaskRunner.runBackgroundSafe("ConstRefEngine#cacheCleanup") {
            cacheCleaner.cleanupIfNeeded(database, repoSharedFingerprintStore)
        }
    }

    private fun schedulePendingLocked() {
        launchSceneTaskLocked(AnalyzeScene.FILE_CHANGE) {
            analyzePending()
        }
    }

    private suspend fun analyzePending() {
        val toAnalyze = synchronized(stateLock) {
            if (pendingAnalyzeFiles.isEmpty()) {
                return
            }
            val files = pendingAnalyzeFiles.toList()
            pendingAnalyzeFiles.clear()
            files
        }
        try {
            analyzeFiles(toAnalyze.map(::File))
        } finally {
            synchronized(stateLock) {
                if (pendingAnalyzeFiles.isNotEmpty()) {
                    schedulePendingLocked()
                }
            }
        }
    }

    private suspend fun analyzeFiles(files: List<File>) {
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
        existingFiles.forEachIndexed { index, file ->
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
            maybeThrottleIo(index + 1)
        }
        if (mtimeHitCount > 0 || fingerprintHitCount > 0 || crcMissCount > 0 || analysisReuseHitCount > 0) {
            logger.debug(
                "ConstRefEngine checksum resolve stats, " +
                    "mtimeHit=$mtimeHitCount, fingerprintHit=$fingerprintHitCount, " +
                    "crcMiss=$crcMissCount, analysisReuseHit=$analysisReuseHitCount"
            )
        }
        if (changedFiles.isEmpty()) {
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
                val state = analysisMutex.withLock {
                    val path = file.toStdPath()
                    val previousDefinitions = loadPreviousDefinitionsLocked(path)
                    Phase1FileState(path, file, previousDefinitions, checksumMap[path] ?: calculateChecksum(file))
                }
                pendingFiles += state
            }
            // Step 2: parse definitions WITHOUT lock (CPU-intensive AST parsing, no shared state).
            val definitionsBatch = mutableListOf<ConstRefCacheDatabase.FileDefinitionsEntry>()
            pendingFiles.forEach { state ->
                val definitions = analyzer.parseDefinitions(listOf(state.file))[state.path].orEmpty()
                definitionsBatch += ConstRefCacheDatabase.FileDefinitionsEntry(
                    filePath = state.path,
                    lastModified = state.file.lastModified(),
                    checksum = state.checksum,
                    definitions = definitions,
                )
            }
            // Step 3: batch-write results under lock (fast DB upsert + cache update).
            analysisMutex.withLock {
                database.upsertBatchDefinitions(definitionsBatch)
                pendingFiles.forEach { state ->
                    updatePreviousDefinitionsLocked(state.path, state.previousDefinitions)
                }
            }
            // Reset KotlinCoreEnvironment after each batch to release the string-intern table
            // that grows ~200 KB per parsed file. Without this, full-scan peak resident heap
            // scales with total file count; with this, it is bounded by batch size.
            analyzer.resetEnvironment()
            phase1ProcessedCount += batch.size
            maybeThrottleIo(phase1ProcessedCount)
        }

        // Phase 2: parse references per batch; DB now has all definitions from Phase 1,
        // so no in-memory overlay is needed. Each file's AST is parsed only once.
        //
        // Lock strategy: split per-file work into three steps to keep AST parsing outside the lock.
        // Step 1 (locked, fast): read previousDefinitions + definitions from DB/cache.
        // Step 2 (unlocked): AST parse via collectHintsAndParseReferences - CPU-intensive, no shared state.
        // Step 3 (batch locked, fast): flush analysis to DB and update shared state.
        // This lets analyzeOnDemand callers acquire analysisMutex between any two files during full scan.
        var phase2ProcessedCount = 0
        changedFiles.chunked(analyzeFilesBatchSize).forEach { batch ->
            val analysisBatch = mutableListOf<ConstRefCacheDatabase.FileAnalysisEntry>()
            data class FilePendingState(
                val path: String,
                val file: File,
                val previousDefinitions: List<ConstDefinition>,
                val definitions: List<ConstDefinition>,
                val references: List<ConstReference>,
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
                val readState = analysisMutex.withLock {
                    val path = file.toStdPath()
                    val previousDefinitions = loadPreviousDefinitionsLocked(path)
                    val checksum = checksumMap[path] ?: calculateChecksum(file)
                    val definitions = database.getDefinitionsByFileAndChecksum(path, checksum)
                    sessionCache.clearLookupCache()
                    Phase2ReadState(path, previousDefinitions, definitions, checksum)
                }
                // Step 2: parse references WITHOUT lock (CPU-intensive AST parse, no shared state).
                val references = parseReferencesByDbOnly(file)
                analysisBatch += ConstRefCacheDatabase.FileAnalysisEntry(
                    filePath = readState.path,
                    lastModified = file.lastModified(),
                    checksum = readState.checksum,
                    definitions = readState.definitions,
                    references = references,
                )
                pendingStates += FilePendingState(
                    readState.path, file, readState.previousDefinitions, readState.definitions, references
                )
            }
            // Batch lock: flush analysis to DB and update shared state atomically.
            // markAnalyzed is called here so awaitAnalysis observers see consistent data
            // (references written) when the analyzed timestamp appears.
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
                        references = state.references,
                    )
                    markAnalyzed(state.path)
                    sessionCache.clearLookupCache()
                }
            }
            // Reset KotlinCoreEnvironment after each batch to release string-intern caches.
            analyzer.resetEnvironment()
            phase2ProcessedCount += batch.size
            maybeThrottleIo(phase2ProcessedCount)
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
                analyzePending()
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

    private suspend fun maybeThrottleIo(processedCount: Int) {
        if (processedCount % ioThrottleEveryNFiles != 0) {
            return
        }
        delay(ioThrottleSleepMs)
    }

    /**
     * Parses references for a single file using only DB-stored definitions (no in-memory overlay).
     * Phase 1 must have already written all definitions to DB before calling this.
     * Uses [ConstRefAnalyzer.collectHintsAndParseReferences] to parse the file only once.
     */
    private fun parseReferencesByDbOnly(file: File): List<ConstReference> {
        val stdPath = file.toStdPath()
        return analyzer.collectHintsAndParseReferences(file) { hints ->
            val candidateDefinitions = queryCandidateDefinitionsForFile(
                filePath = stdPath,
                hints = hints,
            )
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

        if (hints.simpleClassNames.isNotEmpty() && hints.constNames.isNotEmpty()) {
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
    private fun shouldSkipFullScanRequirement(sourceDir: String): Boolean {
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
            else -> backgroundTaskRunner.dispatcher
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
                        try {
                            if (isSourceFile(deletedPath)) {
                                database.removeFile(deletedPath)
                            }
                            database.removeFilesByPrefix("$deletedPath/")
                        } catch (t: Exception) {
                            logger.warn("ConstRefEngine delete cleanup failed, path=$deletedPath", t)
                        }
                    }
                }
            }
        }
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
            if (batchDirCount <= 0) {
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
    }

    private data class SceneTaskState(
        var scheduledJob: Job? = null,
        var runningJob: Job? = null,
    )

    companion object {
        private const val IO_THROTTLE_MS_PROPERTY = "jugg.constref.io.throttle.ms"
        private const val IO_THROTTLE_EVERY_PROPERTY = "jugg.constref.io.throttle.every"
        private const val FULL_SCAN_LOG_INTERVAL_MS_PROPERTY = "jugg.constref.full.scan.log.interval.ms"
        private const val SESSION_FILE_CACHE_MAX_PROPERTY = "jugg.constref.session.file.cache.max"
        private const val SESSION_LOOKUP_CACHE_MAX_PROPERTY = "jugg.constref.session.lookup.cache.max"
        private const val SESSION_CACHE_TTL_MS_PROPERTY = "jugg.constref.session.cache.ttl.ms"
        private const val BATCH_SIZE_PROPERTY = "jugg.constref.batch.size"
        private const val DEFAULT_IO_THROTTLE_MS = 10L
        private const val DEFAULT_IO_THROTTLE_EVERY = 50
        private const val DEFAULT_FULL_SCAN_LOG_INTERVAL_MS = 5000L
        private const val DEFAULT_SESSION_FILE_CACHE_MAX = 500
        private const val DEFAULT_SESSION_LOOKUP_CACHE_MAX = 4000
        private const val DEFAULT_SESSION_CACHE_TTL_MS = 15L * 60L * 1000L
        private const val DEFAULT_BATCH_SIZE = 50

        private fun readNonNegativeLongProperty(property: String, defaultValue: Long): Long {
            return System.getProperty(property)?.toLongOrNull()?.coerceAtLeast(0L) ?: defaultValue
        }

        private fun readPositiveIntProperty(property: String, defaultValue: Int): Int {
            return System.getProperty(property)?.toIntOrNull()?.coerceAtLeast(1) ?: defaultValue
        }
    }
}
