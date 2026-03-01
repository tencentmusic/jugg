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
    private val analysisMutex = ReentrantLock()
    private val stateLock = Any()
    private val changeTracker = ConstRefChangeTracker()
    private val impactResolver = ConstRefImpactResolver(database)
    private val lookupMode = LookupMode.fromSystemProperty()
    private val sessionCache = ConstRefSessionCache(
        fileCacheMaxFiles = readPositiveIntProperty(SESSION_FILE_CACHE_MAX_PROPERTY, DEFAULT_SESSION_FILE_CACHE_MAX),
        lookupCacheMaxKeys = readPositiveIntProperty(SESSION_LOOKUP_CACHE_MAX_PROPERTY, DEFAULT_SESSION_LOOKUP_CACHE_MAX),
        ttlMs = readNonNegativeLongProperty(SESSION_CACHE_TTL_MS_PROPERTY, DEFAULT_SESSION_CACHE_TTL_MS),
    )
    private val pendingAnalyzeFiles = linkedSetOf<String>()
    private var currentEditingFile: String? = null
    private val analyzedAt = mutableMapOf<String, Long>()
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
        logger.info("ConstRefEngine lookup mode=$lookupMode")
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
        analysisMutex.withLock {
            if (lookupMode == LookupMode.LEGACY && definitionIndexInitialized) {
                removeDefinitionsFromIndexLocked(stdPath)
                removeDefinitionsByPrefixFromIndexLocked("$stdPath/")
            }
            sessionCache.removeFile(stdPath)
            sessionCache.removeFilesByPrefix("$stdPath/")
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
                        "ConstRefEngine.awaitAnalysis timeout(${timeoutMs}ms), " +
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
                    logger.warn("ConstRefEngine.awaitAnalysis interrupted, targetPaths=$targetPaths")
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
        analyzer.dispose()
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
            if (lookupMode == LookupMode.LEGACY) {
                ensureDefinitionIndexInitializedLocked()
            }
            val existingFiles = mutableListOf<File>()
            files.distinctBy { it.toStdPath() }.forEach { file ->
                val path = file.toStdPath()
                if (!file.exists()) {
                    database.removeFile(path)
                    removeDefinitionsFromLookupStateLocked(path)
                    changeTracker.onFileDeleted(path)
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
                    "ConstRefEngine checksum resolve stats, " +
                        "mtimeHit=$mtimeHitCount, fingerprintHit=$fingerprintHitCount, " +
                        "crcMiss=$crcMissCount, analysisReuseHit=$analysisReuseHitCount"
                )
            }
            if (changedFiles.isEmpty()) {
                return
            }

            val previousDefinitionsByPath = changedFiles.associate { file ->
                val path = file.toStdPath()
                path to loadPreviousDefinitionsLocked(path)
            }
            val parsedDefinitionsByPath = analyzer.parseDefinitions(changedFiles)
            if (lookupMode == LookupMode.LEGACY) {
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
            } else {
                sessionCache.clearLookupCache()
            }
            val parsedReferencesByPath = if (lookupMode == LookupMode.LEGACY) {
                analyzer.parseReferences(changedFiles, definitionIndex)
            } else {
                parseReferencesByDbSessionMode(changedFiles, parsedDefinitionsByPath)
            }
            changedFiles.forEach { file ->
                val path = file.toStdPath()
                val definitions = parsedDefinitionsByPath[path].orEmpty()
                val references = parsedReferencesByPath[path].orEmpty()
                database.upsertFileAnalysis(
                    filePath = path,
                    lastModified = file.lastModified(),
                    checksum = checksumMap[path] ?: calculateChecksum(file),
                    definitions = definitions,
                    references = references,
                )
                changeTracker.updateDefinitionDiff(
                    filePath = path,
                    previousDefinitions = previousDefinitionsByPath[path].orEmpty(),
                    currentDefinitions = definitions,
                )
                if (lookupMode == LookupMode.DB_SESSION) {
                    sessionCache.putFileAnalysis(
                        filePath = path,
                        lastModified = file.lastModified(),
                        checksum = checksumMap[path] ?: 0L,
                        definitions = definitions,
                        references = references,
                    )
                }
                markAnalyzed(path)
            }
            if (lookupMode == LookupMode.DB_SESSION) {
                sessionCache.clearLookupCache()
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
            logger.warn("ConstRefEngine.forceAnalyzePendingNow failed", t)
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

    private fun loadPreviousDefinitionsLocked(filePath: String): List<ConstDefinition> {
        if (lookupMode == LookupMode.LEGACY) {
            return cachedDefinitionsByFile[filePath].orEmpty()
        }
        val cachedDefinitions = sessionCache.getFileDefinitions(filePath)
        if (cachedDefinitions != null) {
            return cachedDefinitions
        }
        val definitions = database.getLatestDefinitionsByFile(filePath)
        if (definitions.isNotEmpty()) {
            sessionCache.putFileDefinitions(filePath, definitions)
        }
        return definitions
    }

    private fun removeDefinitionsFromLookupStateLocked(filePath: String) {
        if (lookupMode == LookupMode.LEGACY && definitionIndexInitialized) {
            removeDefinitionsFromIndexLocked(filePath)
        }
        sessionCache.removeFile(filePath)
        sessionCache.clearLookupCache()
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
        var scheduledJob: Job? = null
        scheduledJob = backgroundTaskRunner.runBackgroundSafe("ConstRefEngine#$scene") {
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

    private enum class LookupMode {
        LEGACY,
        DB_SESSION;

        companion object {
            fun fromSystemProperty(): LookupMode {
                return when (System.getProperty(ConstRefEngine.LOOKUP_MODE_PROPERTY)?.trim()?.lowercase()) {
                    "db_session" -> DB_SESSION
                    else -> LEGACY
                }
            }
        }
    }

    companion object {
        private const val LOOKUP_MODE_PROPERTY = "jugg.constref.lookup.mode"
        private const val IO_THROTTLE_MS_PROPERTY = "jugg.constref.io.throttle.ms"
        private const val IO_THROTTLE_EVERY_PROPERTY = "jugg.constref.io.throttle.every"
        private const val FULL_SCAN_LOG_INTERVAL_MS_PROPERTY = "jugg.constref.full.scan.log.interval.ms"
        private const val SESSION_FILE_CACHE_MAX_PROPERTY = "jugg.constref.session.file.cache.max"
        private const val SESSION_LOOKUP_CACHE_MAX_PROPERTY = "jugg.constref.session.lookup.cache.max"
        private const val SESSION_CACHE_TTL_MS_PROPERTY = "jugg.constref.session.cache.ttl.ms"
        private const val DEFAULT_FULL_SCAN_LOG_INTERVAL_MS = 5000L
        private const val DEFAULT_SESSION_FILE_CACHE_MAX = 500
        private const val DEFAULT_SESSION_LOOKUP_CACHE_MAX = 4000
        private const val DEFAULT_SESSION_CACHE_TTL_MS = 15L * 60L * 1000L

        private fun readNonNegativeLongProperty(property: String, defaultValue: Long): Long {
            return System.getProperty(property)?.toLongOrNull()?.coerceAtLeast(0L) ?: defaultValue
        }

        private fun readPositiveIntProperty(property: String, defaultValue: Int): Int {
            return System.getProperty(property)?.toIntOrNull()?.coerceAtLeast(1) ?: defaultValue
        }
    }
}
