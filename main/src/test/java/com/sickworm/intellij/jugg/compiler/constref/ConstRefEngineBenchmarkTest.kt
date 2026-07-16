package com.sickworm.intellij.jugg.compiler.constref

import com.intellij.openapi.diagnostic.DefaultLogger
import com.sickworm.intellij.jugg.project.runtime.createTestTaskRunnerManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.lang.management.ManagementFactory
import java.util.Collections
import com.sun.management.OperatingSystemMXBean
import kotlin.reflect.full.callSuspend
import kotlin.reflect.full.declaredFunctions
import kotlin.reflect.jvm.isAccessible

/**
 * Benchmarks cold and warm FULL_SCAN performance for a real external project.
 *
 * Usage:
 * -Dbenchmark.project.dir=/abs/project/path
 * -Dbenchmark.output.dir=/abs/output/dir (optional)
 * -Dbenchmark.constref.cache.dir=/abs/cache/dir (optional)
 */
class ConstRefEngineBenchmarkTest {

    @Test
    fun benchmarkFirstAndIncrementalFullScan() {
        val projectDirPath = System.getProperty(PROP_PROJECT_DIR)?.trim().orEmpty()
        assumeTrue("benchmark.project.dir is required", projectDirPath.isNotEmpty())

        val projectDir = File(projectDirPath)
        assertTrue("project directory not found: $projectDirPath", projectDir.isDirectory)

        val outputDir = resolveOutputDir()
        outputDir.mkdirs()

        val sourceRoots = collectSourceRoots(projectDir)
        assertTrue("no source roots found under $projectDirPath", sourceRoots.isNotEmpty())
        val sourceFiles = collectSourceFiles(sourceRoots)
        assertTrue("no source files found under $projectDirPath", sourceFiles.isNotEmpty())

        val cacheDir = resolveCacheDir()
        cacheDir.mkdirs()
        val sharedDb = File(cacheDir, SHARED_DB_NAME)
        val repoFingerprintDb = File(cacheDir, REPO_FINGERPRINT_DB_NAME)

        val first = runSingleBenchmark(
            projectDir = projectDir,
            sourceFiles = sourceFiles,
            sharedDb = sharedDb,
            repoFingerprintDb = repoFingerprintDb,
            sourceFileCount = sourceFiles.size,
            sourceTotalBytes = sourceFiles.sumOf { it.length() },
            cleanSharedDb = true,
            scenario = "first_scan",
        )

        val incremental = runSingleBenchmark(
            projectDir = projectDir,
            sourceFiles = sourceFiles,
            sharedDb = sharedDb,
            repoFingerprintDb = repoFingerprintDb,
            sourceFileCount = sourceFiles.size,
            sourceTotalBytes = sourceFiles.sumOf { it.length() },
            cleanSharedDb = false,
            scenario = "incremental_scan",
        )

        writeJson(File(outputDir, "benchmark_first_scan.json"), first.toJson())
        writeJson(File(outputDir, "benchmark_incremental_scan.json"), incremental.toJson())
        writeJson(
            File(outputDir, "benchmark_compare.json"),
            buildComparisonJson(first, incremental),
        )

        println("[BENCH] outputDir=${outputDir.absolutePath}")
        println("[BENCH] first=${first.summaryLine()}")
        println("[BENCH] incremental=${incremental.summaryLine()}")
    }

    /** Runs one FULL_SCAN benchmark and captures duration/cpu/memory/log-derived metrics. */
    private fun runSingleBenchmark(
        projectDir: File,
        sourceFiles: List<File>,
        sharedDb: File,
        repoFingerprintDb: File,
        sourceFileCount: Int,
        sourceTotalBytes: Long,
        cleanSharedDb: Boolean,
        scenario: String,
    ): BenchmarkResult {
        if (cleanSharedDb) {
            deleteSharedDbFiles(sharedDb)
        }

        val logger = CapturingLogger("ConstRefBenchmark-$scenario")
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val engine = ConstRefEngine(
            analyzer = ConstRefAnalyzer(logger),
            database = ConstRefCacheDatabase(sharedDb, logger),
            logger = logger,
            taskRunnerManager = createTestTaskRunnerManager(scope),
            repoSharedFingerprintStore = RepoSharedFingerprintStore(logger, repoFingerprintDb),
        )

        val sampler = ProcessSampler.start()
        val startMs = System.currentTimeMillis()
        var readiness: ConstRefEngine.AnalysisReadiness? = null
        var failure: Throwable? = null
        var endMs = startMs
        var samples: List<Sample> = emptyList()
        try {
            runAnalyzeFiles(engine, sourceFiles)
            readiness = ConstRefEngine.AnalysisReadiness.READY
        } catch (t: Throwable) {
            failure = t
        } finally {
            endMs = System.currentTimeMillis()
            samples = sampler.stop()
            engine.dispose()
            scope.cancel()
        }
        failure?.let { throw it }
        val parsed = parseConstRefLogs(logger.snapshot())
        return buildResult(
            scenario = scenario,
            projectDir = projectDir,
            readiness = checkNotNull(readiness),
            durationMs = endMs - startMs,
            samples = samples,
            parsedLog = parsed,
            sharedDb = sharedDb,
            sourceFileCount = sourceFileCount,
            sourceTotalBytes = sourceTotalBytes,
        )
    }

    private fun buildResult(
        scenario: String,
        projectDir: File,
        readiness: ConstRefEngine.AnalysisReadiness,
        durationMs: Long,
        samples: List<Sample>,
        parsedLog: ParsedLog,
        sharedDb: File,
        sourceFileCount: Int,
        sourceTotalBytes: Long,
    ): BenchmarkResult {
        assertTrue("FULL_SCAN readiness timeout in scenario=$scenario", readiness.isReady)
        return BenchmarkResult(
            scenario = scenario,
            projectDir = projectDir.absolutePath,
            durationMs = durationMs,
            sourceFileCount = sourceFileCount,
            sourceTotalBytes = sourceTotalBytes,
            totalFilesFromLog = parsedLog.totalFiles,
            totalDirsFromLog = parsedLog.totalDirs,
            totalAnalyzedFromLog = parsedLog.totalAnalyzed,
            totalReusedFromLog = parsedLog.totalReused,
            checksumMtimeHit = parsedLog.mtimeHit,
            checksumFingerprintHit = parsedLog.fingerprintHit,
            checksumCrcMiss = parsedLog.crcMiss,
            checksumAnalysisReuseHit = parsedLog.analysisReuseHit,
            analysisReuseRate = safeRate(parsedLog.analysisReuseHit, parsedLog.analysisReuseHit + parsedLog.crcMiss),
            cpuP50 = percentile(samples.map { it.cpu }, 0.5),
            cpuP95 = percentile(samples.map { it.cpu }, 0.95),
            heapBeforeMb = samples.firstOrNull()?.heapMb ?: 0.0,
            heapPeakMb = samples.maxOfOrNull { it.heapMb } ?: 0.0,
            heapAfterMb = samples.lastOrNull()?.heapMb ?: 0.0,
            sampleCount = samples.size,
            dbSizeBytes = totalDbBytes(sharedDb),
        )
    }

    private fun collectSourceRoots(projectDir: File): List<File> {
        return projectDir.walkTopDown()
            .filter { it.isDirectory }
            .filter { dir ->
                SOURCE_ROOT_REGEX.containsMatchIn(dir.absolutePath.replace(File.separatorChar, '/'))
            }
            .distinctBy { it.absolutePath }
            .toList()
    }

    private fun collectSourceFiles(sourceRoots: List<File>): List<File> {
        return sourceRoots.asSequence()
            .flatMap { root -> root.walkTopDown() }
            .filter { it.isFile && (it.name.endsWith(".kt") || it.name.endsWith(".java")) }
            .toList()
    }

    /** Invokes private suspend analyzeFiles(List<File>) directly for deterministic benchmark execution. */
    private fun runAnalyzeFiles(engine: ConstRefEngine, files: List<File>) {
        val function = ConstRefEngine::class.declaredFunctions.first { it.name == "analyzeFiles" }
        function.isAccessible = true
        runBlocking {
            function.callSuspend(engine, files)
        }
    }

    private fun resolveOutputDir(): File {
        val outputPath = System.getProperty(PROP_OUTPUT_DIR)?.trim().takeUnless { it.isNullOrBlank() }
            ?: "$DEFAULT_OUTPUT_ROOT/${System.currentTimeMillis()}"
        return File(outputPath)
    }

    private fun resolveCacheDir(): File {
        val overrideDir = System.getProperty(PROP_CACHE_DIR)?.trim()
        if (!overrideDir.isNullOrBlank()) {
            return File(overrideDir)
        }
        val userHome = System.getProperty("user.home")
        return File("$userHome/$DEFAULT_CACHE_RELATIVE_DIR")
    }

    private fun deleteSharedDbFiles(sharedDb: File) {
        File(sharedDb.absolutePath).delete()
        File(sharedDb.absolutePath + "-wal").delete()
        File(sharedDb.absolutePath + "-shm").delete()
    }

    private fun parseConstRefLogs(logs: List<String>): ParsedLog {
        val fullScanLine = logs.lastOrNull { it.contains("ConstRefEngine full scan progress") && it.contains("final=true") }
        val checksumLine = logs.lastOrNull { it.contains("ConstRefEngine checksum resolve stats") }
        return ParsedLog(
            totalDirs = extractInt(fullScanLine, "totalDirs"),
            totalFiles = extractInt(fullScanLine, "totalFiles"),
            totalReused = extractInt(fullScanLine, "totalReused"),
            totalAnalyzed = extractInt(fullScanLine, "totalAnalyzed"),
            mtimeHit = extractInt(checksumLine, "mtimeHit"),
            fingerprintHit = extractInt(checksumLine, "fingerprintHit"),
            crcMiss = extractInt(checksumLine, "crcMiss"),
            analysisReuseHit = extractInt(checksumLine, "analysisReuseHit"),
        )
    }

    private fun extractInt(line: String?, key: String): Int {
        if (line.isNullOrBlank()) {
            return 0
        }
        val regex = Regex("$key=([0-9]+)")
        val match = regex.find(line) ?: return 0
        return match.groupValues[1].toIntOrNull() ?: 0
    }

    private fun percentile(values: List<Double>, p: Double): Double {
        if (values.isEmpty()) {
            return 0.0
        }
        val sorted = values.sorted()
        val idx = ((sorted.size - 1) * p).toInt().coerceIn(0, sorted.size - 1)
        return sorted[idx]
    }

    private fun safeRate(hit: Int, total: Int): Double {
        if (total <= 0) {
            return 0.0
        }
        return hit.toDouble() / total.toDouble()
    }

    private fun totalDbBytes(sharedDb: File): Long {
        val base = File(sharedDb.absolutePath)
        val wal = File(sharedDb.absolutePath + "-wal")
        val shm = File(sharedDb.absolutePath + "-shm")
        return listOf(base, wal, shm).sumOf { if (it.exists()) it.length() else 0L }
    }

    private fun writeJson(file: File, json: String) {
        file.parentFile?.mkdirs()
        file.writeText(json)
    }

    private fun buildComparisonJson(first: BenchmarkResult, incremental: BenchmarkResult): String {
        return """
            {
              "projectDir": "${escape(first.projectDir)}",
              "first": ${first.toJson()},
              "incremental": ${incremental.toJson()},
              "delta": {
                "durationMs": ${incremental.durationMs - first.durationMs},
                "cpuP95": ${formatDouble(incremental.cpuP95 - first.cpuP95)},
                "heapPeakMb": ${formatDouble(incremental.heapPeakMb - first.heapPeakMb)},
                "dbSizeBytes": ${incremental.dbSizeBytes - first.dbSizeBytes}
              }
            }
        """.trimIndent()
    }

    private data class ParsedLog(
        val totalDirs: Int,
        val totalFiles: Int,
        val totalReused: Int,
        val totalAnalyzed: Int,
        val mtimeHit: Int,
        val fingerprintHit: Int,
        val crcMiss: Int,
        val analysisReuseHit: Int,
    )

    private data class Sample(
        val cpu: Double,
        val heapMb: Double,
    )

    private data class BenchmarkResult(
        val scenario: String,
        val projectDir: String,
        val durationMs: Long,
        val sourceFileCount: Int,
        val sourceTotalBytes: Long,
        val totalFilesFromLog: Int,
        val totalDirsFromLog: Int,
        val totalAnalyzedFromLog: Int,
        val totalReusedFromLog: Int,
        val checksumMtimeHit: Int,
        val checksumFingerprintHit: Int,
        val checksumCrcMiss: Int,
        val checksumAnalysisReuseHit: Int,
        val analysisReuseRate: Double,
        val cpuP50: Double,
        val cpuP95: Double,
        val heapBeforeMb: Double,
        val heapPeakMb: Double,
        val heapAfterMb: Double,
        val sampleCount: Int,
        val dbSizeBytes: Long,
    ) {
        fun summaryLine(): String {
            return "scenario=$scenario, durationMs=$durationMs, cpuP95=${"%.2f".format(cpuP95)}, " +
                "heapPeakMb=${"%.2f".format(heapPeakMb)}, reused=$totalReusedFromLog/$totalFilesFromLog"
        }

        fun toJson(): String {
            return """
                {
                  "scenario": "${escape(scenario)}",
                  "projectDir": "${escape(projectDir)}",
                  "durationMs": $durationMs,
                  "sourceFileCount": $sourceFileCount,
                  "sourceTotalBytes": $sourceTotalBytes,
                  "totalFilesFromLog": $totalFilesFromLog,
                  "totalDirsFromLog": $totalDirsFromLog,
                  "totalAnalyzedFromLog": $totalAnalyzedFromLog,
                  "totalReusedFromLog": $totalReusedFromLog,
                  "checksum": {
                    "mtimeHit": $checksumMtimeHit,
                    "fingerprintHit": $checksumFingerprintHit,
                    "crcMiss": $checksumCrcMiss,
                    "analysisReuseHit": $checksumAnalysisReuseHit,
                    "analysisReuseRate": ${formatDouble(analysisReuseRate)}
                  },
                  "cpu": {
                    "p50": ${formatDouble(cpuP50)},
                    "p95": ${formatDouble(cpuP95)},
                    "samples": $sampleCount
                  },
                  "heapMb": {
                    "before": ${formatDouble(heapBeforeMb)},
                    "peak": ${formatDouble(heapPeakMb)},
                    "after": ${formatDouble(heapAfterMb)}
                  },
                  "ioProxy": {
                    "dbSizeBytes": $dbSizeBytes,
                    "totalAnalyzedFromLog": $totalAnalyzedFromLog,
                    "totalReusedFromLog": $totalReusedFromLog
                  }
                }
            """.trimIndent()
        }
    }

    private class ProcessSampler private constructor(
        private val osBean: OperatingSystemMXBean?,
    ) {
        private val samples = Collections.synchronizedList(mutableListOf<Sample>())
        @Volatile
        private var running = true
        private val thread = Thread {
            while (running) {
                val cpu = ((osBean?.processCpuLoad ?: -1.0).coerceAtLeast(0.0) * 100.0)
                val heapUsage = ManagementFactory.getMemoryMXBean().heapMemoryUsage
                val heapMb = heapUsage.used.toDouble() / (1024.0 * 1024.0)
                samples += Sample(cpu = cpu, heapMb = heapMb)
                Thread.sleep(SAMPLE_INTERVAL_MS)
            }
        }

        fun stop(): List<Sample> {
            running = false
            thread.join(5_000L)
            return samples.toList()
        }

        companion object {
            fun start(): ProcessSampler {
                val bean = ManagementFactory.getPlatformMXBean(OperatingSystemMXBean::class.java)
                val sampler = ProcessSampler(bean)
                sampler.thread.isDaemon = true
                sampler.thread.start()
                return sampler
            }
        }
    }

    private class CapturingLogger(category: String) : DefaultLogger(category) {
        private val lines = Collections.synchronizedList(mutableListOf<String>())

        override fun isTraceEnabled(): Boolean = false
        override fun trace(message: String?) = Unit
        override fun trace(t: Throwable?) = Unit
        override fun isDebugEnabled(): Boolean = true

        override fun debug(message: String?) {
            record(message)
        }

        override fun debug(t: Throwable?) {
            record(t?.message)
        }

        override fun debug(message: String?, t: Throwable?) {
            record(message)
            record(t?.message)
        }

        override fun info(message: String?) {
            record(message)
        }

        override fun info(message: String?, t: Throwable?) {
            record(message)
            record(t?.message)
        }

        override fun warn(message: String?, t: Throwable?) {
            record(message)
            record(t?.message)
        }

        override fun error(message: String?, t: Throwable?, vararg details: String?) {
            record(message)
            record(t?.message)
        }

        fun snapshot(): List<String> = lines.toList()

        private fun record(message: String?) {
            if (message.isNullOrBlank()) {
                return
            }
            if (!message.contains("ConstRefEngine full scan progress")
                && !message.contains("ConstRefEngine checksum resolve stats")
            ) {
                return
            }
            synchronized(lines) {
                if (lines.size >= MAX_CAPTURE_LINES) {
                    lines.removeAt(0)
                }
                lines += message
            }
        }
    }

    /**
     * Measures heap growth at each phase of analyzeFiles for a batch of real source files.
     * Reports heap at: before engine init, after Phase 1, after Phase 2, and after dispose.
     *
     * Requires -Dbenchmark.project.dir.
     */
    @Test
    fun diagnoseKotlinParserHeapRetention() {
        val projectDirPath = System.getProperty(PROP_PROJECT_DIR)?.trim().orEmpty()
        assumeTrue("benchmark.project.dir is required", projectDirPath.isNotEmpty())

        val projectDir = File(projectDirPath)
        val sourceRoots = collectSourceRoots(projectDir)
        val allFiles = collectSourceFiles(sourceRoots).take(500)
        assumeTrue("no source files found", allFiles.isNotEmpty())

        fun heapUsedMb(): Double {
            repeat(3) { System.gc() }
            Thread.sleep(300)
            val rt = Runtime.getRuntime()
            return (rt.totalMemory() - rt.freeMemory()).toDouble() / (1024 * 1024)
        }

        val cacheDir = File(System.getProperty("java.io.tmpdir"), "diag_constref_cache")
        cacheDir.mkdirs()
        val sharedDb = File(cacheDir, "diag_shared.db").also { it.delete() }
        val repoFingerprintDb = File(cacheDir, "diag_fingerprint.db").also { it.delete() }

        val logger = object : com.intellij.openapi.diagnostic.DefaultLogger("diag") {
            override fun isDebugEnabled() = false
        }
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

        val heapBefore = heapUsedMb()
        val engine = ConstRefEngine(
            analyzer = ConstRefAnalyzer(logger),
            database = ConstRefCacheDatabase(sharedDb, logger),
            logger = logger,
            taskRunnerManager = createTestTaskRunnerManager(scope),
            repoSharedFingerprintStore = RepoSharedFingerprintStore(logger, repoFingerprintDb),
        )
        val heapAfterInit = heapUsedMb()
        println("[DIAG] before=%.1fMB, after engine init=%.1fMB, engine_overhead=%.1fMB".format(
            heapBefore, heapAfterInit, heapAfterInit - heapBefore))

        val analyzeFilesFn = ConstRefEngine::class.declaredFunctions
            .first { it.name == "analyzeFiles" }
            .also { it.isAccessible = true }

        runBlocking { analyzeFilesFn.callSuspend(engine, allFiles) }
        val heapAfterAnalyze = heapUsedMb()
        println("[DIAG] after analyzeFiles(%d files): %.1fMB (delta from init: %.1fMB, per-file: %.2fMB)".format(
            allFiles.size, heapAfterAnalyze, heapAfterAnalyze - heapAfterInit,
            (heapAfterAnalyze - heapAfterInit) / allFiles.size))

        engine.dispose()
        scope.cancel()
        repeat(5) { System.gc() }
        Thread.sleep(500)
        val heapAfterDispose = heapUsedMb()
        println("[DIAG] after dispose+gc: %.1fMB (released from peak: %.1fMB)".format(
            heapAfterDispose, heapAfterAnalyze - heapAfterDispose))
    }

    /**
     * Measures the maximum resident (live-set) heap during analyzeFiles by running a parallel
     * GC+sample thread while analysis executes. Forcing GC periodically flushes short-lived
     * objects so each sample reflects the true live set at that moment. The maximum of these
     * samples is the peak resident memory during analysis.
     *
     * Requires -Dbenchmark.project.dir.
     */
    @Test
    fun diagnoseAnalysisResidentMemory() {
        val projectDirPath = System.getProperty(PROP_PROJECT_DIR)?.trim().orEmpty()
        assumeTrue("benchmark.project.dir is required", projectDirPath.isNotEmpty())

        val projectDir = File(projectDirPath)
        val sourceRoots = collectSourceRoots(projectDir)
        val allFiles = collectSourceFiles(sourceRoots)
        assumeTrue("no source files found", allFiles.isNotEmpty())

        val cacheDir = File(System.getProperty("java.io.tmpdir"), "diag_resident_cache")
        cacheDir.mkdirs()
        val sharedDb = File(cacheDir, "diag_resident_shared.db").also { it.delete() }
        val repoFingerprintDb = File(cacheDir, "diag_resident_fingerprint.db").also { it.delete() }

        val logger = object : com.intellij.openapi.diagnostic.DefaultLogger("diag-resident") {
            override fun isDebugEnabled() = false
        }
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val engine = ConstRefEngine(
            analyzer = ConstRefAnalyzer(logger),
            database = ConstRefCacheDatabase(sharedDb, logger),
            logger = logger,
            taskRunnerManager = createTestTaskRunnerManager(scope),
            repoSharedFingerprintStore = RepoSharedFingerprintStore(logger, repoFingerprintDb),
        )

        fun forceGcHeapMb(): Double {
            repeat(3) { System.gc() }
            Thread.sleep(200)
            val rt = Runtime.getRuntime()
            return (rt.totalMemory() - rt.freeMemory()).toDouble() / (1024 * 1024)
        }

        // Parallel GC+sampler: forces GC every interval and records heap after collection.
        val residentSamples = Collections.synchronizedList(mutableListOf<Double>())
        val samplerRunning = java.util.concurrent.atomic.AtomicBoolean(true)
        val analysisStartMs = System.currentTimeMillis()
        val samplerThread = Thread {
            while (samplerRunning.get()) {
                val mb = forceGcHeapMb()
                val elapsedS = (System.currentTimeMillis() - analysisStartMs) / 1000
                residentSamples += mb
                println("[DIAG-RESIDENT] sample t+${elapsedS}s: %.1fMB".format(mb))
                Thread.sleep(RESIDENT_SAMPLE_INTERVAL_MS)
            }
        }.also {
            it.isDaemon = true
            it.start()
        }

        val heapBefore = forceGcHeapMb()
        println("[DIAG-RESIDENT] before analyzeFiles: %.1fMB, fileCount=%d".format(heapBefore, allFiles.size))

        val analyzeFilesFn = ConstRefEngine::class.declaredFunctions
            .first { it.name == "analyzeFiles" }
            .also { it.isAccessible = true }

        try {
            runBlocking { analyzeFilesFn.callSuspend(engine, allFiles) }
        } finally {
            samplerRunning.set(false)
            samplerThread.join(5_000L)
            engine.dispose()
            scope.cancel()
        }

        val heapAfter = forceGcHeapMb()
        val peakResident = residentSamples.maxOrNull() ?: 0.0
        val minResident = residentSamples.minOrNull() ?: 0.0

        println("[DIAG-RESIDENT] after analyzeFiles+gc: %.1fMB".format(heapAfter))
        println("[DIAG-RESIDENT] sample count=%d, interval=%dms".format(residentSamples.size, RESIDENT_SAMPLE_INTERVAL_MS))
        println("[DIAG-RESIDENT] resident min=%.1fMB, peak=%.1fMB, delta-from-before=%.1fMB".format(
            minResident, peakResident, peakResident - heapBefore))
        println("[DIAG-RESIDENT] conclusion: max resident memory during analysis = %.1fMB".format(peakResident))
    }

    companion object {
        private const val PROP_PROJECT_DIR = "benchmark.project.dir"
        private const val PROP_OUTPUT_DIR = "benchmark.output.dir"
        private const val PROP_CACHE_DIR = "benchmark.constref.cache.dir"
        private const val FULL_SCAN_TIMEOUT_MS = 30 * 60 * 1000L
        private const val SAMPLE_INTERVAL_MS = 1000L

        private const val SHARED_DB_NAME = "const_ref_shared.db"
        private const val REPO_FINGERPRINT_DB_NAME = "repo_fingerprint.db"

        private const val DEFAULT_OUTPUT_ROOT = "/tmp/constref_benchmark"
        private const val DEFAULT_CACHE_RELATIVE_DIR = "Library/Caches/Google/AndroidStudio2025.3.1/jugg/const_ref"
        private val SOURCE_ROOT_REGEX = Regex(".*/src/[^/]+/(java|kotlin)$")
        private const val MAX_CAPTURE_LINES = 2048
        private const val RESIDENT_SAMPLE_INTERVAL_MS = 3000L

        private fun formatDouble(value: Double): String = "%.4f".format(value)
        private fun escape(value: String): String =
            value.replace("\\", "\\\\").replace("\"", "\\\"")
    }
}
