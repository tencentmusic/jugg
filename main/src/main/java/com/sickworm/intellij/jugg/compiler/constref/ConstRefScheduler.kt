package com.sickworm.intellij.jugg.compiler.constref

import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.jugg.compiler.listFilesRecursively
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.locks.ReentrantLock
import java.util.zip.CRC32
import kotlin.concurrent.withLock

class ConstRefScheduler(
    private val analyzer: ConstRefAnalyzer,
    private val database: ConstRefCacheDatabase,
    private val logger: Logger,
    private val coroutineScope: CoroutineScope,
) {
    private val maxAnalyzedHistory = 4096
    private val analysisMutex = ReentrantLock()
    private val stateLock = Any()
    private val pendingAnalyzeFiles = linkedSetOf<String>()
    private var currentEditingFile: String? = null
    private val analyzedAt = mutableMapOf<String, Long>()
    private val removedDefinitionKeys = mutableMapOf<String, Set<Pair<String, String>>>()
    private val cachedDefinitionsByFile = mutableMapOf<String, List<ConstDefinition>>()
    private val definitionIndex = ConstDefinitionIndex()
    private var definitionIndexInitialized = false
    private val trackedSourceDirs = mutableListOf<String>()
    private val fullScanReadySourceDirs = mutableSetOf<String>()
    private var scheduledJob: Job? = null
    private var runningJob: Job? = null
    private var fullScanJob: Job? = null

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

    fun awaitAnalysis(filePaths: List<String>, timeoutMs: Long = 5000L) {
        val targetPaths = filePaths
            .map { File(it).toStdPath() }
            .filter { isSourceFile(it) }
            .distinct()
        if (targetPaths.isEmpty()) {
            return
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
                    logger.warn("ConstRefScheduler.awaitAnalysis interrupted, targetPaths=$targetPaths")
                    break
                }
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
        synchronized(stateLock) {
            trackedSourceDirs.clear()
            trackedSourceDirs += normalizedSourceDirs
            fullScanReadySourceDirs.clear()
            if (fullScanJob?.isActive == true) {
                return
            }
            fullScanJob = coroutineScope.launch {
                try {
                    normalizedSourceDirs.forEach { sourceDirPath ->
                        val sourceDir = File(sourceDirPath)
                        val sourceFiles = sourceDir
                            .listFilesRecursively()
                            .asSequence()
                            .filter { file -> file.isFile && isSourceFile(file.name) }
                            .distinctBy { it.toStdPath() }
                            .toList()
                        if (sourceFiles.isNotEmpty()) {
                            val startTime = System.currentTimeMillis()
                            analyzeFiles(sourceFiles)
                            val costTime = System.currentTimeMillis() - startTime
                            logger.debug(
                                "ConstRefScheduler full scan sourceDir finished, sourceDir=$sourceDirPath, " +
                                    "files=${sourceFiles.size}, cost=${costTime}ms"
                            )
                        }
                        synchronized(stateLock) {
                            fullScanReadySourceDirs += sourceDirPath
                            notifyStateChangedLocked()
                        }
                    }
                } finally {
                    synchronized(stateLock) {
                        fullScanJob = null
                        notifyStateChangedLocked()
                    }
                }
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
        val changedSet = changedPaths.toSet()
        val removedKeys = synchronized(stateLock) {
            changedPaths.flatMap { removedDefinitionKeys[it].orEmpty() }.toSet()
        }
        val byCurrentDefinitions = database.getEffectedFiles(changedPaths)
        val byRemovedDefinitions = database.getEffectedFilesByDefinitionKeys(removedKeys)
        return (byCurrentDefinitions + byRemovedDefinitions)
            .filter { it.refFilePath !in changedSet && File(it.refFilePath).exists() }
            .distinctBy { "${it.refFilePath}|${it.defFqClassName}|${it.constName}" }
    }

    fun dispose() {
        synchronized(stateLock) {
            scheduledJob?.cancel()
            runningJob?.cancel()
            fullScanJob?.cancel()
        }
        analyzer.dispose()
    }

    private fun schedulePendingLocked() {
        scheduledJob?.cancel()
        scheduledJob = coroutineScope.launch {
            analyzePending()
        }
    }

    private fun analyzePending() {
        val toAnalyze = synchronized(stateLock) {
            scheduledJob = null
            if (pendingAnalyzeFiles.isEmpty()) {
                return
            }
            val files = pendingAnalyzeFiles.toList()
            pendingAnalyzeFiles.clear()
            runningJob = null
            files
        }
        try {
            analyzeFiles(toAnalyze.map(::File))
        } finally {
            synchronized(stateLock) {
                runningJob = null
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
            existingFiles.forEach { file ->
                val path = file.toStdPath()
                val cacheEntry = database.getFileCache(path)
                val fileLastModified = file.lastModified()
                if (cacheEntry != null && cacheEntry.lastModified == fileLastModified) {
                    clearRemovedDefinitionKeys(path)
                    markAnalyzed(path)
                    return@forEach
                }

                val checksum = calculateChecksum(file)
                if (cacheEntry != null && cacheEntry.checksum == checksum) {
                    database.updateFileLastModified(path, fileLastModified)
                    clearRemovedDefinitionKeys(path)
                    markAnalyzed(path)
                    return@forEach
                }
                checksumMap[path] = checksum
                changedFiles += file
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
                val removedKeys = buildRemovedDefinitionKeys(previousDefinitionsByPath[path].orEmpty(), definitions)
                database.upsertFileAnalysis(
                    filePath = path,
                    lastModified = file.lastModified(),
                    checksum = checksumMap[path] ?: calculateChecksum(file),
                    definitions = definitions,
                    references = references,
                )
                updateRemovedDefinitionKeys(path, removedKeys)
                markAnalyzed(path)
            }
        }
    }

    private fun forceAnalyzePendingNow() {
        val toAnalyze = synchronized(stateLock) {
            if (pendingAnalyzeFiles.isEmpty()) {
                return
            }
            scheduledJob?.cancel()
            scheduledJob = null
            val files = pendingAnalyzeFiles.toList()
            pendingAnalyzeFiles.clear()
            files
        }
        try {
            analyzeFiles(toAnalyze.map(::File))
        } catch (t: Throwable) {
            logger.warn("ConstRefScheduler.forceAnalyzePendingNow failed", t)
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

    private fun ensureDefinitionIndexInitializedLocked() {
        if (definitionIndexInitialized) {
            return
        }
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

    private fun isSourceFile(path: String): Boolean {
        return path.endsWith(".java") || path.endsWith(".kt")
    }
}
