package com.sickworm.intellij.jugg.compiler.constref

import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.jugg.compiler.listFilesRecursively
import com.sickworm.intellij.jugg.project.CoroutineBackgroundTaskRunner
import com.sickworm.intellij.jugg.project.IBackgroundTaskRunner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import java.io.File
import java.util.concurrent.locks.ReentrantLock
import java.util.zip.CRC32
import kotlin.concurrent.withLock

class ConstRefScheduler(
    private val analyzer: ConstRefAnalyzer,
    private val database: ConstRefCacheDatabase,
    private val logger: Logger,
    private var backgroundTaskRunner: IBackgroundTaskRunner,
    private val repoSharedFingerprintStore: RepoSharedFingerprintStore,
) {
    private val maxAnalyzedHistory = 4096
    private val analysisMutex = ReentrantLock()
    private val stateLock = Any()
    private val pendingAnalyzeFiles = linkedSetOf<String>()
    private var currentEditingFile: String? = null
    private val analyzedAt = mutableMapOf<String, Long>()
    private val changedDefinitionKeys = mutableMapOf<String, Set<Pair<String, String>>>()
    private val removedDefinitionKeys = mutableMapOf<String, Set<Pair<String, String>>>()
    private val cachedDefinitionsByFile = mutableMapOf<String, List<ConstDefinition>>()
    private val definitionIndex = ConstDefinitionIndex()
    private var definitionIndexInitialized = false
    private val trackedSourceDirs = mutableListOf<String>()
    private val fullScanReadySourceDirs = mutableSetOf<String>()
    private val cacheCleaner = ConstRefCacheCleaner(logger)
    private val sceneTaskStates = mutableMapOf(
        AnalyzeScene.FULL_SCAN to SceneTaskState(),
        AnalyzeScene.FILE_CHANGE to SceneTaskState(),
        AnalyzeScene.PRE_COMPILE to SceneTaskState(),
    )
    private val ioThrottleSleepMs: Long = readNonNegativeLongProperty(IO_THROTTLE_MS_PROPERTY, 0L)
    private val ioThrottleEveryNFiles: Int = readPositiveIntProperty(IO_THROTTLE_EVERY_PROPERTY, 1)
    private val fullScanLogIntervalMs: Long =
        readNonNegativeLongProperty(FULL_SCAN_LOG_INTERVAL_MS_PROPERTY, DEFAULT_FULL_SCAN_LOG_INTERVAL_MS)

    init {
        if (ioThrottleSleepMs > 0L) {
            logger.info("ConstRefScheduler io throttle enabled, " +
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
            changedDefinitionKeys.keys.removeIf { it == stdPath || it.startsWith("$stdPath/") }
            removedDefinitionKeys.keys.removeIf { it == stdPath || it.startsWith("$stdPath/") }
            notifyStateChangedLocked()
        }
        analysisMutex.withLock {
            if (definitionIndexInitialized) {
                removeDefinitionsFromIndexLocked(stdPath)
                removeDefinitionsByPrefixFromIndexLocked("$stdPath/")
            }
        }
        if (isSourceFile(stdPath)) {
            database.removeFile(stdPath)
        }
        database.removeFilesByPrefix("$stdPath/")
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
                    pendingAnalyzeFiles += path
                } else {
                    analyzedAt[path] = startAt
                }
            }
            trimAnalyzedAtLocked()
            schedulePendingLocked()
        }
        forceAnalyzePendingNow()
        var readiness = AnalysisReadiness.READY
        synchronized(stateLock) {
            val relatedSourceDirs = resolveRelatedSourceDirsLocked(targetPaths)
            while (true) {
                val fileReady = targetPaths.all { path ->
                    !File(path).exists() || (analyzedAt[path] ?: 0L) >= startAt
                }
                val sourceReady = relatedSourceDirs.all { dir ->
                    fullScanReadySourceDirs.contains(dir)
                }
                if (fileReady && sourceReady) {
                    break
                }

                val remainMs = deadlineAt - System.currentTimeMillis()
                if (remainMs <= 0L) {
                    val unreadyPaths = targetPaths.filter { path ->
                        File(path).exists() && (analyzedAt[path] ?: 0L) < startAt
                    }
                    val pendingSourceDirs = relatedSourceDirs.filterNot { fullScanReadySourceDirs.contains(it) }
                    readiness = AnalysisReadiness(
                        isReady = false,
                        unreadyPaths = unreadyPaths,
                        pendingSourceDirs = pendingSourceDirs,
                    )
                    logger.warn(
                        "ConstRefScheduler.awaitAnalysis timeout(${timeoutMs}ms), " +
                            "targetPaths=$targetPaths, unreadyPaths=$unreadyPaths, pendingSourceDirs=$pendingSourceDirs"
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
                    val pendingSourceDirs = relatedSourceDirs.filterNot { fullScanReadySourceDirs.contains(it) }
                    readiness = AnalysisReadiness(
                        isReady = false,
                        unreadyPaths = unreadyPaths,
                        pendingSourceDirs = pendingSourceDirs,
                    )
                    logger.warn("ConstRefScheduler.awaitAnalysis interrupted, targetPaths=$targetPaths")
                    break
                }
            }
        }
        return readiness
    }

    fun ensureReadyForRecompile(filePaths: Collection<String>, timeoutMs: Long = 5000L): AnalysisReadiness {
        return awaitAnalysis(filePaths.toList(), timeoutMs)
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
                        reusablePaths.forEach(::markAnalyzed)
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
        database.registerPathHints(changedPaths)
        val changedSet = changedPaths.toSet()
        val changedKeys = synchronized(stateLock) {
            changedPaths.flatMap { changedDefinitionKeys[it].orEmpty() }.toSet()
        }
        val removedKeys = synchronized(stateLock) {
            changedPaths.flatMap { removedDefinitionKeys[it].orEmpty() }.toSet()
        }
        val byChangedDefinitions = database.getEffectedFilesByDefinitionKeys(changedKeys, changedPaths)
        val byRemovedDefinitions = database.getEffectedFilesByDefinitionKeys(removedKeys, changedPaths)
        return (byChangedDefinitions + byRemovedDefinitions)
            .filter { it.refFilePath !in changedSet && File(it.refFilePath).exists() }
            .distinctBy { "${it.refFilePath}|${it.defFqClassName}|${it.constName}" }
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
        analyzer.dispose()
    }

    private fun scheduleCacheCleanup() {
        backgroundTaskRunner.runBackgroundSafe("ConstRefScheduler#cacheCleanup") {
            cacheCleaner.cleanupIfNeeded(database, repoSharedFingerprintStore)
        }
    }

    private fun schedulePendingLocked() {
        launchSceneTaskLocked(AnalyzeScene.FILE_CHANGE) {
            analyzePending()
        }
    }

    private fun analyzePending() {
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

    private fun analyzeFiles(files: List<File>) {
        if (files.isEmpty()) {
            return
        }
        analysisMutex.withLock {
            database.registerPathHints(files.map { it.toStdPath() })
            ensureDefinitionIndexInitializedLocked()
            val existingFiles = mutableListOf<File>()
            files.distinctBy { it.toStdPath() }.forEach { file ->
                val path = file.toStdPath()
                if (!file.exists()) {
                    database.removeFile(path)
                    removeDefinitionsFromIndexLocked(path)
                    clearRemovedDefinitionKeys(path)
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
            var ioProcessedCount = 0
            existingFiles.forEach { file ->
                ioProcessedCount++
                maybeThrottleIo(ioProcessedCount)
                val path = file.toStdPath()
                val fileLastModified = file.lastModified()

                val checksum = resolveChecksum(
                    file = file,
                    fileLastModified = fileLastModified,
                    onMtimeHit = { mtimeHitCount++ },
                    onFingerprintHit = { fingerprintHitCount++ },
                    onCrcMiss = { crcMissCount++ },
                )
                if (database.touchFileAnalysis(path, fileLastModified, checksum)) {
                    analysisReuseHitCount++
                    markAnalyzed(path)
                    return@forEach
                }
                checksumMap[path] = checksum
                changedFiles += file
            }
            if (mtimeHitCount > 0 || fingerprintHitCount > 0 || crcMissCount > 0 || analysisReuseHitCount > 0) {
                logger.debug(
                    "ConstRefScheduler checksum resolve stats, " +
                        "mtimeHit=$mtimeHitCount, fingerprintHit=$fingerprintHitCount, " +
                        "crcMiss=$crcMissCount, analysisReuseHit=$analysisReuseHitCount"
                )
            }
            if (changedFiles.isEmpty()) {
                return
            }

            val previousDefinitionsByPath = changedFiles.associate { file ->
                val path = file.toStdPath()
                path to cachedDefinitionsByFile[path].orEmpty()
            }
            val parsedDefinitionsByPath = analyzer.parseDefinitions(changedFiles)
            changedFiles.forEach { file ->
                val path = file.toStdPath()
                val definitions = parsedDefinitionsByPath[path].orEmpty()
                if (definitions.isEmpty()) {
                    cachedDefinitionsByFile.remove(path)
                } else {
                    cachedDefinitionsByFile[path] = definitions
                }
                definitionIndex.replaceFileDefinitions(path, definitions)
            }
            val parsedReferencesByPath = analyzer.parseReferences(changedFiles, definitionIndex)
            changedFiles.forEach { file ->
                val path = file.toStdPath()
                val definitions = parsedDefinitionsByPath[path].orEmpty()
                val references = parsedReferencesByPath[path].orEmpty()
                val changedKeys = buildChangedDefinitionKeys(previousDefinitionsByPath[path].orEmpty(), definitions)
                val removedKeys = buildRemovedDefinitionKeys(previousDefinitionsByPath[path].orEmpty(), definitions)
                database.upsertFileAnalysis(
                    filePath = path,
                    lastModified = file.lastModified(),
                    checksum = checksumMap[path] ?: calculateChecksum(file),
                    definitions = definitions,
                    references = references,
                )
                updateChangedDefinitionKeys(path, changedKeys)
                updateRemovedDefinitionKeys(path, removedKeys)
                markAnalyzed(path)
            }
        }
    }

    private fun forceAnalyzePendingNow() {
        if (!beginSyncScene(AnalyzeScene.PRE_COMPILE)) {
            return
        }
        val toAnalyze = synchronized(stateLock) {
            if (pendingAnalyzeFiles.isEmpty()) {
                endSyncScene(AnalyzeScene.PRE_COMPILE)
                return
            }
            sceneTaskStates[AnalyzeScene.FILE_CHANGE]?.scheduledJob?.cancel()
            sceneTaskStates[AnalyzeScene.FILE_CHANGE]?.scheduledJob = null
            val files = pendingAnalyzeFiles.toList()
            pendingAnalyzeFiles.clear()
            files
        }
        try {
            analyzeFiles(toAnalyze.map(::File))
        } catch (t: Throwable) {
            logger.warn("ConstRefScheduler.forceAnalyzePendingNow failed", t)
        } finally {
            endSyncScene(AnalyzeScene.PRE_COMPILE)
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
        database.getChecksumByLastModified(path, fileLastModified)?.let { checksum ->
            onMtimeHit()
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

    private fun maybeThrottleIo(processedCount: Int) {
        if (ioThrottleSleepMs <= 0L || processedCount % ioThrottleEveryNFiles != 0) {
            return
        }
        try {
            Thread.sleep(ioThrottleSleepMs)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private fun ensureDefinitionIndexInitializedLocked() {
        if (definitionIndexInitialized) {
            return
        }
        database.registerPathHints(trackedSourceDirs)
        cachedDefinitionsByFile.clear()
        database.getAllDefinitions()
            .groupBy { it.filePath }
            .forEach { (filePath, definitions) ->
                cachedDefinitionsByFile[filePath] = definitions
                definitionIndex.replaceFileDefinitions(filePath, definitions)
            }
        definitionIndexInitialized = true
    }

    private fun removeDefinitionsFromIndexLocked(filePath: String) {
        cachedDefinitionsByFile.remove(filePath)
        definitionIndex.removeFileDefinitions(filePath)
    }

    private fun removeDefinitionsByPrefixFromIndexLocked(prefixPath: String) {
        val pathsToRemove = cachedDefinitionsByFile.keys
            .filter { it.startsWith(prefixPath) }
            .toList()
        pathsToRemove.forEach { path ->
            removeDefinitionsFromIndexLocked(path)
        }
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

    private fun updateChangedDefinitionKeys(path: String, keys: Set<Pair<String, String>>) {
        synchronized(stateLock) {
            if (keys.isEmpty()) {
                changedDefinitionKeys.remove(path)
            } else {
                changedDefinitionKeys[path] = keys
            }
        }
    }

    private fun clearRemovedDefinitionKeys(path: String) {
        synchronized(stateLock) {
            removedDefinitionKeys.remove(path)
        }
    }

    private fun updateRemovedDefinitionKeys(path: String, keys: Set<Pair<String, String>>) {
        synchronized(stateLock) {
            if (keys.isEmpty()) {
                removedDefinitionKeys.remove(path)
            } else {
                removedDefinitionKeys[path] = keys
            }
        }
    }

    private fun buildRemovedDefinitionKeys(
        previousDefinitions: List<ConstDefinition>,
        currentDefinitions: List<ConstDefinition>,
    ): Set<Pair<String, String>> {
        if (previousDefinitions.isEmpty()) {
            return emptySet()
        }
        val oldKeys = previousDefinitions.map { it.fqClassName to it.constName }.toSet()
        val currentKeys = currentDefinitions.map { it.fqClassName to it.constName }.toSet()
        return oldKeys - currentKeys
    }

    private fun buildChangedDefinitionKeys(
        previousDefinitions: List<ConstDefinition>,
        currentDefinitions: List<ConstDefinition>,
    ): Set<Pair<String, String>> {
        if (previousDefinitions.isEmpty() && currentDefinitions.isEmpty()) {
            return emptySet()
        }
        val previousSignatureByKey = previousDefinitions
            .groupBy { it.fqClassName to it.constName }
            .mapValues { (_, defs) ->
                defs.map { "${it.constType}:${it.constValue.orEmpty()}" }.toSet()
            }
        val currentSignatureByKey = currentDefinitions
            .groupBy { it.fqClassName to it.constName }
            .mapValues { (_, defs) ->
                defs.map { "${it.constType}:${it.constValue.orEmpty()}" }.toSet()
            }
        return (previousSignatureByKey.keys + currentSignatureByKey.keys)
            .filterTo(linkedSetOf()) { key ->
                previousSignatureByKey[key] != currentSignatureByKey[key]
            }
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

    private fun notifyStateChangedLocked() {
        @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
        (stateLock as java.lang.Object).notifyAll()
    }

    private fun waitStateChangedLocked(waitMs: Long) {
        @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
        (stateLock as java.lang.Object).wait(waitMs)
    }

    private fun launchSceneTaskLocked(scene: AnalyzeScene, action: () -> Unit) {
        val sceneState = sceneTaskStates.getValue(scene)
        sceneState.scheduledJob?.cancel()
        lateinit var scheduledJob: Job
        scheduledJob = backgroundTaskRunner.runBackgroundSafe("ConstRefScheduler#$scene") {
            val shouldRun = synchronized(stateLock) {
                val state = sceneTaskStates.getValue(scene)
                if (state.scheduledJob == scheduledJob) {
                    state.scheduledJob = null
                }
                if (state.runningJob?.isActive == true) {
                    return@synchronized false
                }
                state.runningJob = scheduledJob
                true
            }
            if (!shouldRun) {
                return@runBackgroundSafe
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
    }

    private fun beginSyncScene(scene: AnalyzeScene): Boolean {
        synchronized(stateLock) {
            val state = sceneTaskStates.getValue(scene)
            if (state.runningJob?.isActive == true) {
                return false
            }
            state.runningJob = Job()
            return true
        }
    }

    private fun endSyncScene(scene: AnalyzeScene) {
        synchronized(stateLock) {
            val state = sceneTaskStates.getValue(scene)
            state.runningJob = null
            notifyStateChangedLocked()
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
                "ConstRefScheduler full scan progress, final=$isFinal, " +
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
        private const val DEFAULT_FULL_SCAN_LOG_INTERVAL_MS = 5000L

        private fun readNonNegativeLongProperty(property: String, defaultValue: Long): Long {
            return System.getProperty(property)?.toLongOrNull()?.coerceAtLeast(0L) ?: defaultValue
        }

        private fun readPositiveIntProperty(property: String, defaultValue: Int): Int {
            return System.getProperty(property)?.toIntOrNull()?.coerceAtLeast(1) ?: defaultValue
        }
    }
}
