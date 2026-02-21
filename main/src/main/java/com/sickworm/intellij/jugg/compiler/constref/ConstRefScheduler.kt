package com.sickworm.intellij.jugg.compiler.constref

import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.jugg.compiler.listFilesRecursively
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.zip.CRC32

class ConstRefScheduler(
    private val analyzer: ConstRefAnalyzer,
    private val database: ConstRefCacheDatabase,
    private val logger: Logger,
    private val coroutineScope: CoroutineScope,
) {
    private val debounceMs = 250L
    private val analysisMutex = Mutex()
    private val stateLock = Any()
    private val pendingFiles = linkedSetOf<String>()
    private val analyzedAt = mutableMapOf<String, Long>()
    private var scheduledJob: Job? = null
    private var runningJob: Job? = null
    private var fullScanJob: Job? = null

    fun onFileSaved(filePath: String) {
        val stdPath = File(filePath).toStdPath()
        if (!isSourceFile(stdPath)) {
            return
        }
        synchronized(stateLock) {
            pendingFiles += stdPath
            schedulePendingLocked(delayMs = debounceMs)
        }
    }

    fun onFileDeleted(filePath: String) {
        val stdPath = File(filePath).toStdPath()
        synchronized(stateLock) {
            pendingFiles.removeIf { it == stdPath || it.startsWith("$stdPath/") }
            analyzedAt.keys.removeIf { it == stdPath || it.startsWith("$stdPath/") }
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
        synchronized(stateLock) {
            targetPaths.forEach { path ->
                if (File(path).exists()) {
                    pendingFiles += path
                } else {
                    analyzedAt[path] = startAt
                }
            }
            schedulePendingLocked(delayMs = 0L)
        }

        runBlocking {
            val isTimeout = withTimeoutOrNull(timeoutMs) {
                while (true) {
                    val done = synchronized(stateLock) {
                        targetPaths.all { path ->
                            !File(path).exists() || (analyzedAt[path] ?: 0L) >= startAt
                        }
                    }
                    if (done) {
                        break
                    }
                    delay(20L)
                }
            } == null
            if (isTimeout) {
                logger.warn("ConstRefScheduler.awaitAnalysis timeout(${timeoutMs}ms), targetPaths=$targetPaths")
            }
        }
    }

    fun initializeFullScan(sourceDirs: List<File>) {
        synchronized(stateLock) {
            if (fullScanJob?.isActive == true) {
                return
            }
            fullScanJob = coroutineScope.launch {
                val sourceFiles = sourceDirs
                    .asSequence()
                    .filter { it.exists() }
                    .flatMap { dir ->
                        dir.listFilesRecursively().asSequence()
                    }
                    .filter { file -> file.isFile && isSourceFile(file.name) }
                    .distinctBy { it.toStdPath() }
                    .toList()
                if (sourceFiles.isEmpty()) {
                    return@launch
                }
                val startTime = System.currentTimeMillis()
                analyzeFiles(sourceFiles)
                val costTime = System.currentTimeMillis() - startTime
                logger.debug("ConstRefScheduler full scan finished, files=${sourceFiles.size}, cost=${costTime}ms")
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
        return database.getEffectedFiles(changedPaths)
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

    private fun schedulePendingLocked(delayMs: Long) {
        scheduledJob?.cancel()
        scheduledJob = coroutineScope.launch {
            if (delayMs > 0L) {
                delay(delayMs)
            }
            analyzePending()
        }
    }

    private suspend fun analyzePending() {
        val currentJob = kotlinx.coroutines.currentCoroutineContext()[Job]
        val toAnalyze = synchronized(stateLock) {
            scheduledJob = null
            if (pendingFiles.isEmpty()) {
                return
            }
            val files = pendingFiles.toList()
            pendingFiles.clear()
            runningJob = currentJob
            files
        }
        try {
            analyzeFiles(toAnalyze.map(::File))
        } finally {
            synchronized(stateLock) {
                runningJob = null
                if (pendingFiles.isNotEmpty()) {
                    schedulePendingLocked(0L)
                }
            }
        }
    }

    private suspend fun analyzeFiles(files: List<File>) {
        if (files.isEmpty()) {
            return
        }
        analysisMutex.withLock {
            val existingFiles = mutableListOf<File>()
            files.distinctBy { it.toStdPath() }.forEach { file ->
                val path = file.toStdPath()
                if (!file.exists()) {
                    database.removeFile(path)
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
                val checksum = calculateChecksum(file)
                checksumMap[path] = checksum
                val cacheEntry = database.getFileCache(path)
                if (cacheEntry != null && cacheEntry.lastModified == file.lastModified() && cacheEntry.checksum == checksum) {
                    markAnalyzed(path)
                    return@forEach
                }
                changedFiles += file
            }
            if (changedFiles.isEmpty()) {
                return
            }

            val changedPaths = changedFiles.map { it.toStdPath() }.toSet()
            val baseDefinitions = database.getAllDefinitions(excludeFilePaths = changedPaths)
            val parseResultMap = analyzer.analyze(changedFiles, baseDefinitions)
            changedFiles.forEach { file ->
                val path = file.toStdPath()
                val parseResult = parseResultMap[path] ?: FileConstParseResult.EMPTY
                database.upsertFileAnalysis(
                    filePath = path,
                    lastModified = file.lastModified(),
                    checksum = checksumMap[path] ?: calculateChecksum(file),
                    definitions = parseResult.definitions,
                    references = parseResult.references,
                )
                markAnalyzed(path)
            }
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

    private fun markAnalyzed(path: String) {
        synchronized(stateLock) {
            analyzedAt[path] = System.currentTimeMillis()
        }
    }

    private fun isSourceFile(path: String): Boolean {
        return path.endsWith(".java") || path.endsWith(".kt")
    }
}
