package com.sickworm.intellij.jugg.compiler.constref

import com.intellij.openapi.diagnostic.DefaultLogger
import com.sickworm.intellij.jugg.project.createTestTaskRunnerManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.lang.management.ManagementFactory
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import com.sun.management.OperatingSystemMXBean

/**
 * Runs ConstRef FULL_SCAN directly against a real project without starting the IDE.
 *
 * This benchmark is intentionally opt-in. Pass -Dbenchmark.project.dir to enable it.
 */
class ConstRefFullScanResourceBenchmarkTest {

    @Test
    fun benchmarkFullScanColdAndWarm() {
        val projectDirPath = System.getProperty(PROP_PROJECT)?.trim().orEmpty()
        assumeTrue("$PROP_PROJECT is required", projectDirPath.isNotEmpty())

        val projectDir = File(projectDirPath)
        assertTrue("project directory not found: $projectDir", projectDir.isDirectory)

        val outputDir = resolveOutputDir()
        val cacheDir = resolveCacheDir(outputDir)
        val resetCache = readBooleanProperty(PROP_RESET_CACHE, true)

        val sourceRoots = collectSourceRoots(projectDir)
        assertTrue("no source roots found under $projectDir", sourceRoots.isNotEmpty())
        val sourceFiles = collectSourceFiles(sourceRoots)
        assertTrue("no source files found under $projectDir", sourceFiles.isNotEmpty())
        val timeoutMs = resolveTimeoutMs(sourceFiles.size)

        ensureDirectory(outputDir, "benchmark output directory")
        ensureDirectory(cacheDir, "benchmark cache directory")
        if (resetCache) {
            cacheDir.deleteRecursively()
            ensureDirectory(cacheDir, "benchmark cache directory after reset")
        }
        println(
            "[CONSTREF_BENCH] project=${projectDir.absolutePath}, sourceFiles=${sourceFiles.size}, " +
                "timeoutMs=$timeoutMs, outputDir=${outputDir.absolutePath}"
        )

        val cold = runSingleRound(
            scenario = "cold",
            projectDir = projectDir,
            sourceRoots = sourceRoots,
            sourceFileCount = sourceFiles.size,
            sourceTotalBytes = sourceFiles.sumOf { it.length() },
            cacheDir = cacheDir,
            outputDir = outputDir,
            timeoutMs = timeoutMs,
        )
        writeText(File(outputDir, "constref_fullscan_cold.json"), cold.toJson())

        val warm = runSingleRound(
            scenario = "warm",
            projectDir = projectDir,
            sourceRoots = sourceRoots,
            sourceFileCount = sourceFiles.size,
            sourceTotalBytes = sourceFiles.sumOf { it.length() },
            cacheDir = cacheDir,
            outputDir = outputDir,
            timeoutMs = timeoutMs,
        )

        writeText(File(outputDir, "constref_fullscan_warm.json"), warm.toJson())
        writeText(File(outputDir, "constref_fullscan_summary.json"), summaryJson(projectDir, cacheDir, cold, warm))

        println("[CONSTREF_BENCH] outputDir=${outputDir.absolutePath}")
        println("[CONSTREF_BENCH] cold=${cold.summaryLine()}")
        println("[CONSTREF_BENCH] warm=${warm.summaryLine()}")
    }

    private fun runSingleRound(
        scenario: String,
        projectDir: File,
        sourceRoots: List<File>,
        sourceFileCount: Int,
        sourceTotalBytes: Long,
        cacheDir: File,
        outputDir: File,
        timeoutMs: Long,
    ): BenchmarkRound {
        val sharedDb = File(cacheDir, SHARED_DB_NAME)
        val repoFingerprintDb = File(cacheDir, REPO_FINGERPRINT_DB_NAME)
        val progressRecorder = ProgressRecorder(File(outputDir, "constref_fullscan_${scenario}_progress.log"))
        val logger = CapturingLogger(
            category = "ConstRefFullScanResourceBenchmark-$scenario",
            progressRecorder = progressRecorder,
        )
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val engine = ConstRefEngine(
            analyzer = ConstRefAnalyzer(logger),
            database = ConstRefCacheDatabase(sharedDb, logger),
            logger = logger,
            taskRunnerManager = createTestTaskRunnerManager(scope),
            repoSharedFingerprintStore = RepoSharedFingerprintStore(logger, repoFingerprintDb),
            startupStabilizationDelayMs = 0L,
        )
        val sampler = ProcessSampler.start()
        val osBean = ManagementFactory.getPlatformMXBean(OperatingSystemMXBean::class.java)
        val startCpuMs = processCpuMs(osBean)
        val startMs = System.currentTimeMillis()
        val heartbeat = ProgressHeartbeat(
            scenario = scenario,
            startMs = startMs,
            osBean = osBean,
            sharedDb = sharedDb,
            progressRecorder = progressRecorder,
            capturedLogCountProvider = { logger.snapshot().size },
        )
        try {
            progressRecorder.record("start scenario=$scenario, timeoutMs=$timeoutMs")
            heartbeat.start()
            engine.initializeFullScan(sourceRoots)
            val completed = logger.awaitFullScanFinal(timeoutMs)
            if (!completed) {
                progressRecorder.record("timeout scenario=$scenario, timeoutMs=$timeoutMs")
            }
            assertTrue(
                "FULL_SCAN did not finish in ${timeoutMs}ms, scenario=$scenario, " +
                    "progressLog=${progressRecorder.file.absolutePath}",
                completed,
            )
        } finally {
            heartbeat.stop()
            engine.dispose()
            scope.cancel()
        }
        val durationMs = System.currentTimeMillis() - startMs
        val cpuMs = processCpuMs(osBean) - startCpuMs
        val samples = sampler.stop()
        val parsed = ParsedLog.from(logger.snapshot())
        progressRecorder.record("finish scenario=$scenario, durationMs=$durationMs")
        return BenchmarkRound(
            scenario = scenario,
            projectDir = projectDir.absolutePath,
            sourceRootCount = sourceRoots.size,
            sourceFileCount = sourceFileCount,
            sourceTotalBytes = sourceTotalBytes,
            durationMs = durationMs,
            processCpuMs = cpuMs,
            processCpuToWallRatio = if (durationMs > 0L) cpuMs.toDouble() / durationMs.toDouble() else 0.0,
            cpuLoadP50 = percentile(samples.map { it.cpuLoad }, 0.50),
            cpuLoadP95 = percentile(samples.map { it.cpuLoad }, 0.95),
            heapPeakMb = samples.maxOfOrNull { it.heapMb } ?: 0.0,
            sampleCount = samples.size,
            totalDirs = parsed.totalDirs,
            totalFiles = parsed.totalFiles,
            totalReused = parsed.totalReused,
            totalAnalyzed = parsed.totalAnalyzed,
            totalCostMs = parsed.totalCostMs,
            phaseLogCount = parsed.phaseLogCount,
            phaseTotalMs = parsed.phaseTotalMs,
            checksumMs = parsed.checksumMs,
            phase1ParseMs = parsed.phase1ParseMs,
            phase2RefMs = parsed.phase2RefMs,
            dbMs = parsed.dbMs,
            throttle = parsed.throttle,
            dbSizeBytes = totalDbBytes(sharedDb),
        )
    }

    private fun collectSourceRoots(projectDir: File): List<File> {
        return projectDir.walkTopDown()
            .filter { it.isDirectory }
            .filter { SOURCE_ROOT_REGEX.containsMatchIn(it.absolutePath.replace(File.separatorChar, '/')) }
            .distinctBy { it.absolutePath }
            .toList()
    }

    private fun collectSourceFiles(sourceRoots: List<File>): List<File> {
        return sourceRoots.asSequence()
            .flatMap { it.walkTopDown() }
            .filter { it.isFile && (it.name.endsWith(".kt") || it.name.endsWith(".java")) }
            .toList()
    }

    private fun resolveOutputDir(): File {
        val outputPath = System.getProperty(PROP_OUTPUT_DIR)?.trim()
        if (!outputPath.isNullOrBlank()) {
            return normalizeOutputDir(File(outputPath))
        }
        return File(DEFAULT_OUTPUT_ROOT, System.currentTimeMillis().toString())
    }

    private fun normalizeOutputDir(outputDir: File): File {
        val profileName = outputDir.name
        if (outputDir.parentFile?.absolutePath == File.separator && profileName in LEGACY_ROOT_PROFILE_DIRS) {
            return File(DEFAULT_OUTPUT_ROOT, profileName)
        }
        return outputDir
    }

    private fun resolveCacheDir(outputDir: File): File {
        val cachePath = System.getProperty(PROP_CACHE_DIR)?.trim()
        if (!cachePath.isNullOrBlank()) {
            return File(cachePath)
        }
        return File(outputDir, "cache")
    }

    private fun processCpuMs(osBean: OperatingSystemMXBean): Long {
        return osBean.processCpuTime.coerceAtLeast(0L) / 1_000_000L
    }

    private fun percentile(values: List<Double>, percentile: Double): Double {
        if (values.isEmpty()) {
            return 0.0
        }
        val sorted = values.sorted()
        val index = ((sorted.size - 1) * percentile).toInt().coerceIn(0, sorted.size - 1)
        return sorted[index]
    }

    private fun totalDbBytes(sharedDb: File): Long {
        return listOf(
            sharedDb,
            File(sharedDb.absolutePath + "-wal"),
            File(sharedDb.absolutePath + "-shm"),
        ).sumOf { if (it.exists()) it.length() else 0L }
    }

    private fun summaryJson(projectDir: File, cacheDir: File, cold: BenchmarkRound, warm: BenchmarkRound): String {
        return """
            {
              "projectDir": "${escape(projectDir.absolutePath)}",
              "cacheDir": "${escape(cacheDir.absolutePath)}",
              "cold": ${cold.toJson()},
              "warm": ${warm.toJson()}
            }
        """.trimIndent()
    }

    private fun writeText(file: File, text: String) {
        file.parentFile?.mkdirs()
        file.writeText(text)
    }

    private fun ensureDirectory(directory: File, label: String) {
        assertTrue("cannot create $label: ${directory.absolutePath}", directory.mkdirs() || directory.isDirectory)
    }

    private fun resolveTimeoutMs(sourceFileCount: Int): Long {
        val explicitTimeoutMs = readOptionalLongProperty(PROP_TIMEOUT_MS)
        if (explicitTimeoutMs != null) {
            return explicitTimeoutMs
        }
        val throttleMs = readOptionalLongProperty(PROP_FULL_SCAN_THROTTLE_MS) ?: DEFAULT_FULL_SCAN_THROTTLE_MS
        val throttleEvery = readOptionalLongProperty(PROP_FULL_SCAN_THROTTLE_EVERY) ?: DEFAULT_FULL_SCAN_THROTTLE_EVERY
        val throttleFloorMs = estimateThrottleFloorMs(sourceFileCount, throttleMs, throttleEvery)
        return maxOf(DEFAULT_TIMEOUT_MS, throttleFloorMs + DEFAULT_PARSE_BUDGET_MS)
    }

    private fun estimateThrottleFloorMs(sourceFileCount: Int, throttleMs: Long, throttleEvery: Long): Long {
        if (sourceFileCount <= 0 || throttleMs <= 0L || throttleEvery <= 0L) {
            return 0L
        }
        val batches = (sourceFileCount + throttleEvery - 1L) / throttleEvery
        return batches * throttleMs
    }

    private data class BenchmarkRound(
        val scenario: String,
        val projectDir: String,
        val sourceRootCount: Int,
        val sourceFileCount: Int,
        val sourceTotalBytes: Long,
        val durationMs: Long,
        val processCpuMs: Long,
        val processCpuToWallRatio: Double,
        val cpuLoadP50: Double,
        val cpuLoadP95: Double,
        val heapPeakMb: Double,
        val sampleCount: Int,
        val totalDirs: Int,
        val totalFiles: Int,
        val totalReused: Int,
        val totalAnalyzed: Int,
        val totalCostMs: Long,
        val phaseLogCount: Int,
        val phaseTotalMs: Long,
        val checksumMs: Long,
        val phase1ParseMs: Long,
        val phase2RefMs: Long,
        val dbMs: Long,
        val throttle: String,
        val dbSizeBytes: Long,
    ) {
        fun summaryLine(): String {
            return "durationMs=$durationMs, processCpuRatio=${formatDouble(processCpuToWallRatio)}, " +
                "cpuLoadP95=${formatDouble(cpuLoadP95)}, files=$totalFiles, reused=$totalReused, " +
                "analyzed=$totalAnalyzed, throttle=$throttle"
        }

        fun toJson(): String {
            return """
                {
                  "scenario": "${escape(scenario)}",
                  "projectDir": "${escape(projectDir)}",
                  "sourceRootCount": $sourceRootCount,
                  "sourceFileCount": $sourceFileCount,
                  "sourceTotalBytes": $sourceTotalBytes,
                  "durationMs": $durationMs,
                  "processCpuMs": $processCpuMs,
                  "processCpuToWallRatio": ${formatDouble(processCpuToWallRatio)},
                  "cpuLoad": {
                    "p50": ${formatDouble(cpuLoadP50)},
                    "p95": ${formatDouble(cpuLoadP95)},
                    "sampleCount": $sampleCount
                  },
                  "heap": {
                    "peakMb": ${formatDouble(heapPeakMb)}
                  },
                  "fullScan": {
                    "totalDirs": $totalDirs,
                    "totalFiles": $totalFiles,
                    "totalReused": $totalReused,
                    "totalAnalyzed": $totalAnalyzed,
                    "totalCostMs": $totalCostMs,
                    "throttle": "${escape(throttle)}"
                  },
                  "phaseBreakdown": {
                    "logCount": $phaseLogCount,
                    "totalMs": $phaseTotalMs,
                    "checksumMs": $checksumMs,
                    "phase1ParseMs": $phase1ParseMs,
                    "phase2RefMs": $phase2RefMs,
                    "dbMs": $dbMs
                  },
                  "ioProxy": {
                    "dbSizeBytes": $dbSizeBytes,
                    "sourceTotalBytes": $sourceTotalBytes
                  }
                }
            """.trimIndent()
        }
    }

    private data class Sample(
        val cpuLoad: Double,
        val heapMb: Double,
    )

    private class ProgressRecorder(val file: File) {
        private val lock = Any()

        init {
            file.parentFile?.mkdirs()
            file.writeText("")
        }

        fun record(message: String) {
            val line = "[CONSTREF_BENCH] ${timestamp()} $message"
            synchronized(lock) {
                file.appendText(line + "\n")
            }
            println(line)
        }
    }

    private class ProgressHeartbeat(
        private val scenario: String,
        private val startMs: Long,
        private val osBean: OperatingSystemMXBean,
        private val sharedDb: File,
        private val progressRecorder: ProgressRecorder,
        private val capturedLogCountProvider: () -> Int,
    ) {
        @Volatile
        private var running = true
        private val thread = Thread {
            while (running) {
                try {
                    Thread.sleep(HEARTBEAT_INTERVAL_MS)
                } catch (_: InterruptedException) {
                    return@Thread
                }
                if (running) {
                    progressRecorder.record(heartbeatLine())
                }
            }
        }

        fun start() {
            thread.isDaemon = true
            thread.start()
        }

        fun stop() {
            running = false
            thread.interrupt()
            thread.join(5_000L)
        }

        private fun heartbeatLine(): String {
            val elapsedMs = System.currentTimeMillis() - startMs
            val heapUsage = ManagementFactory.getMemoryMXBean().heapMemoryUsage
            val heapMb = heapUsage.used.toDouble() / (1024.0 * 1024.0)
            return "heartbeat scenario=$scenario, elapsedMs=$elapsedMs, " +
                "processCpuMs=${osBean.processCpuTime.coerceAtLeast(0L) / 1_000_000L}, " +
                "heapMb=${formatDouble(heapMb)}, dbBytes=${dbBytes(sharedDb)}, " +
                "capturedLogs=${capturedLogCountProvider()}"
        }

        private fun dbBytes(sharedDb: File): Long {
            return listOf(
                sharedDb,
                File(sharedDb.absolutePath + "-wal"),
                File(sharedDb.absolutePath + "-shm"),
            ).sumOf { if (it.exists()) it.length() else 0L }
        }
    }

    private class ProcessSampler private constructor(
        private val osBean: OperatingSystemMXBean,
    ) {
        private val samples = Collections.synchronizedList(mutableListOf<Sample>())
        @Volatile
        private var running = true
        private val thread = Thread {
            while (running) {
                val heapUsage = ManagementFactory.getMemoryMXBean().heapMemoryUsage
                samples += Sample(
                    cpuLoad = osBean.processCpuLoad.coerceAtLeast(0.0) * 100.0,
                    heapMb = heapUsage.used.toDouble() / (1024.0 * 1024.0),
                )
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
                val sampler = ProcessSampler(ManagementFactory.getPlatformMXBean(OperatingSystemMXBean::class.java))
                sampler.thread.isDaemon = true
                sampler.thread.start()
                return sampler
            }
        }
    }

    private data class ParsedLog(
        val totalDirs: Int,
        val totalFiles: Int,
        val totalReused: Int,
        val totalAnalyzed: Int,
        val totalCostMs: Long,
        val phaseLogCount: Int,
        val phaseTotalMs: Long,
        val checksumMs: Long,
        val phase1ParseMs: Long,
        val phase2RefMs: Long,
        val dbMs: Long,
        val throttle: String,
    ) {
        companion object {
            fun from(logs: List<String>): ParsedLog {
                val finalLine = logs.lastOrNull {
                    it.contains("ConstRefEngine full scan progress") && it.contains("final=true")
                }
                val throttleLine = logs.lastOrNull { it.contains("ConstRefEngine io throttle enabled") }.orEmpty()
                var phaseLogCount = 0
                var phaseTotalMs = 0L
                var checksumMs = 0L
                var phase1ParseMs = 0L
                var phase2RefMs = 0L
                var dbMs = 0L
                logs.filter { it.contains("ConstRefEngine analyzeFiles phase breakdown") }.forEach { line ->
                    phaseLogCount++
                    phaseTotalMs += extractLong(line, "totalMs")
                    checksumMs += extractLong(line, "checksumMs")
                    phase1ParseMs += extractLong(line, "phase1ParseMs")
                    phase2RefMs += extractLong(line, "phase2RefMs")
                    dbMs += extractLong(line, "phase1DbWriteMs") +
                        extractLong(line, "phase2DbLookupMs") +
                        extractLong(line, "phase2DbWriteMs")
                }
                return ParsedLog(
                    totalDirs = extractInt(finalLine, "totalDirs"),
                    totalFiles = extractInt(finalLine, "totalFiles"),
                    totalReused = extractInt(finalLine, "totalReused"),
                    totalAnalyzed = extractInt(finalLine, "totalAnalyzed"),
                    totalCostMs = extractLong(finalLine, "totalCost"),
                    phaseLogCount = phaseLogCount,
                    phaseTotalMs = phaseTotalMs,
                    checksumMs = checksumMs,
                    phase1ParseMs = phase1ParseMs,
                    phase2RefMs = phase2RefMs,
                    dbMs = dbMs,
                    throttle = throttleLine.substringAfter("ConstRefEngine io throttle enabled, ", ""),
                )
            }
        }
    }

    private class CapturingLogger(
        category: String,
        private val progressRecorder: ProgressRecorder,
    ) : DefaultLogger(category) {
        private val fullScanFinalLatch = CountDownLatch(1)
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

        fun awaitFullScanFinal(timeoutMs: Long): Boolean {
            return fullScanFinalLatch.await(timeoutMs, TimeUnit.MILLISECONDS)
        }

        fun snapshot(): List<String> = lines.toList()

        private fun record(message: String?) {
            if (message.isNullOrBlank()) {
                return
            }
            if (!shouldCapture(message)) {
                return
            }
            synchronized(lines) {
                lines += message
            }
            progressRecorder.record(message)
            if (message.contains("ConstRefEngine full scan progress") && message.contains("final=true")) {
                fullScanFinalLatch.countDown()
            }
        }

        private fun shouldCapture(message: String): Boolean {
            return message.contains("ConstRefEngine io throttle enabled") ||
                message.contains("ConstRefEngine full scan progress") ||
                message.contains("ConstRefEngine analyzeFiles phase breakdown")
        }
    }

    companion object {
        private const val PROP_PROJECT = "benchmark.project.dir"
        private const val PROP_OUTPUT_DIR = "benchmark.output.dir"
        private const val PROP_CACHE_DIR = "benchmark.constref.cache.dir"
        private const val PROP_RESET_CACHE = "benchmark.constref.reset.cache"
        private const val PROP_TIMEOUT_MS = "benchmark.constref.timeout.ms"
        private const val PROP_FULL_SCAN_THROTTLE_MS = "jugg.constref.fullscan.io.throttle.ms"
        private const val PROP_FULL_SCAN_THROTTLE_EVERY = "jugg.constref.fullscan.io.throttle.every"
        private const val DEFAULT_TIMEOUT_MS = 90L * 60L * 1000L
        private const val DEFAULT_PARSE_BUDGET_MS = 60L * 60L * 1000L
        private const val DEFAULT_FULL_SCAN_THROTTLE_MS = 500L
        private const val DEFAULT_FULL_SCAN_THROTTLE_EVERY = 200L
        private const val DEFAULT_OUTPUT_ROOT = "/tmp/jugg-constref-benchmark"
        private const val SAMPLE_INTERVAL_MS = 1000L
        private const val HEARTBEAT_INTERVAL_MS = 60_000L
        private const val SHARED_DB_NAME = "const_ref_shared.db"
        private const val REPO_FINGERPRINT_DB_NAME = "repo_fingerprint.db"
        private val LEGACY_ROOT_PROFILE_DIRS = setOf("old-profile", "new-profile")
        private val SOURCE_ROOT_REGEX = Regex(".*/src/[^/]+/(java|kotlin)$")

        private fun readBooleanProperty(property: String, defaultValue: Boolean): Boolean {
            return System.getProperty(property)?.toBooleanStrictOrNull() ?: defaultValue
        }

        private fun readOptionalLongProperty(property: String): Long? {
            return System.getProperty(property)?.toLongOrNull()?.coerceAtLeast(1L)
        }

        private fun extractInt(line: String?, key: String): Int {
            return extractLong(line, key).toInt()
        }

        private fun extractLong(line: String?, key: String): Long {
            if (line.isNullOrBlank()) {
                return 0L
            }
            return Regex("$key=([0-9]+)").find(line)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
        }

        private fun formatDouble(value: Double): String = "%.4f".format(value)

        private fun escape(value: String): String {
            return value.replace("\\", "\\\\").replace("\"", "\\\"")
        }

        private fun timestamp(): String {
            return java.time.LocalDateTime.now().toString()
        }
    }
}
