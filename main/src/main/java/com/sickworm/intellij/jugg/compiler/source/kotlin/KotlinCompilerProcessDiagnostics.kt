package com.sickworm.intellij.jugg.compiler.source.kotlin

import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.jugg.compiler.isWindows
import java.io.File
import java.io.PrintStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/** Collects best-effort compiler and host thread evidence without delaying compiler cleanup. */
internal class KotlinCompilerProcessDiagnostics(
    private val process: Process,
    private val javaHome: File,
    private val compilerThread: Thread,
    private val outputThread: Thread,
    private val logger: Logger,
) {
    private val pid = process.pid()
    private val startedAt = System.nanoTime()
    private val bytesRead = AtomicLong()
    private val bytesForwarded = AtomicLong()
    private val lastOutputAt = AtomicLong(startedAt)
    private val cleanupStarted = CountDownLatch(1)
    private val finished = CountDownLatch(1)
    private val descendants = CopyOnWriteArrayList<ProcessHandle>()
    private var lastSampleAt = startedAt
    @Volatile private var currentPhase = "running"

    /** Samples slow compilation twice and stalled cleanup once, outside the compilation thread. */
    fun start() {
        thread(name = "jugg-kotlin-diagnostics-$pid", isDaemon = true) {
            try {
                if (!cleanupStarted.await(2, TimeUnit.MINUTES)) {
                    snapshot("slow compiler progress", includeThreads = false)
                }
                if (!cleanupStarted.await(2, TimeUnit.MINUTES)) {
                    snapshot("slow compiler")
                    captureCompilerThreads()
                }
                if (!cleanupStarted.await(70, TimeUnit.SECONDS)) {
                    snapshot("compiler monitor stalled")
                } else if (!finished.await(10, TimeUnit.SECONDS)) {
                    snapshot("compiler cleanup stalled")
                }
            } catch (e: Exception) {
                logger.debug("isolated Kotlin compiler pid=$pid diagnostics failed", e)
                if (e is InterruptedException) Thread.currentThread().interrupt()
            }
        }
    }

    fun phase(value: String) {
        currentPhase = value
        cleanupStarted.countDown()
        logger.debug("isolated Kotlin compiler pid=$pid phase=$value, elapsedMs=${elapsedMillis()}")
    }

    fun finish() {
        finished.countDown()
        cleanupStarted.countDown()
    }

    fun trackDescendant(child: ProcessHandle) {
        descendants.add(child)
    }

    /** A returned termination request is not proof that the process has exited. */
    fun terminationResult(handle: ProcessHandle, result: String) {
        logger.debug("isolated Kotlin compiler pid=$pid termination result=$result, ${processState(handle)}")
    }

    fun copyOutput(outputStream: PrintStream) {
        try {
            process.inputStream.use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val size = input.read(buffer)
                    if (size < 0) break
                    bytesRead.addAndGet(size.toLong())
                    lastOutputAt.set(System.nanoTime())
                    outputStream.write(buffer, 0, size)
                    bytesForwarded.addAndGet(size.toLong())
                }
            }
        } catch (e: Exception) {
            logger.debug("isolated Kotlin compiler pid=$pid output reader failed", e)
            throw e
        } finally {
            logger.debug("isolated Kotlin compiler pid=$pid output reader stopped, " +
                    "bytesRead=${bytesRead.get()}, bytesForwarded=${bytesForwarded.get()}")
        }
    }

    /** Captures one child JVM thread dump with bounded tool execution and output size. */
    fun captureCompilerThreads() {
        if (!process.isAlive) {
            logger.debug("isolated Kotlin compiler pid=$pid thread dump skipped: compiler has exited")
            return
        }
        val jcmd = File(javaHome, if (isWindows) "bin/jcmd.exe" else "bin/jcmd")
        if (!jcmd.isFile) {
            logger.debug("isolated Kotlin compiler pid=$pid thread dump unavailable: $jcmd does not exist")
            return
        }
        var dumpFile: File? = null
        var collector: Process? = null
        try {
            val file = File.createTempFile("jugg-kotlinc-$pid-", ".threads")
            dumpFile = file
            logger.debug("isolated Kotlin compiler pid=$pid thread dump starting: $jcmd")
            collector = ProcessBuilder(jcmd.path, pid.toString(), "Thread.print", "-l")
                .redirectErrorStream(true)
                .redirectOutput(file)
                .start()
            val completed = collector.waitFor(5, TimeUnit.SECONDS)
            if (!completed) {
                logger.debug("isolated Kotlin compiler pid=$pid thread dump tool timed out after 5 seconds")
                collector.destroyForcibly()
            }
            val output = file.inputStream().use { it.readNBytes(MAX_DUMP_BYTES) }
            val exitCode = if (completed) collector.exitValue().toString() else "unavailable"
            logger.debug("isolated Kotlin compiler pid=$pid thread dump, exitCode=$exitCode, " +
                    "truncated=${file.length() > output.size}:\n${output.toString(Charsets.UTF_8)}")
        } catch (e: Exception) {
            logger.debug("isolated Kotlin compiler pid=$pid thread dump failed", e)
            if (e is InterruptedException) Thread.currentThread().interrupt()
        } finally {
            if (collector?.isAlive == true) collector.destroyForcibly()
            dumpFile?.let { if (!it.delete()) it.deleteOnExit() }
        }
    }

    private fun snapshot(reason: String, includeThreads: Boolean = true) {
        val sampledAt = System.nanoTime()
        val intervalMillis = TimeUnit.NANOSECONDS.toMillis(sampledAt - lastSampleAt)
        lastSampleAt = sampledAt
        val silenceMillis = TimeUnit.NANOSECONDS.toMillis(sampledAt - lastOutputAt.get())
        logger.debug("isolated Kotlin compiler pid=$pid $reason, phase=$currentPhase, " +
                "elapsedMs=${elapsedMillis()}, sampleIntervalMs=$intervalMillis, cpuTimeMs=${cpuTimeMillis()}, " +
                "alive=${process.isAlive}, bytesRead=${bytesRead.get()}, bytesForwarded=${bytesForwarded.get()}, " +
                "lastOutputAgeMs=$silenceMillis, knownDescendants=${descendants.map(::processState)}")
        if (!includeThreads) return
        listOf(compilerThread, outputThread).forEach { target ->
            logger.debug("isolated Kotlin compiler pid=$pid host thread=${target.name}, state=${target.state}:\n" +
                    target.stackTrace.joinToString("\n") { "\tat $it" })
        }
    }

    private fun cpuTimeMillis(): String = try {
        process.info().totalCpuDuration().map { it.toMillis().toString() }.orElse("unavailable")
    } catch (e: Exception) {
        logger.debug("isolated Kotlin compiler pid=$pid CPU time unavailable", e)
        "unavailable"
    }

    private fun processState(handle: ProcessHandle): String = try {
        "targetPid=${handle.pid()}, alive=${handle.isAlive}"
    } catch (e: Exception) {
        logger.debug("isolated Kotlin compiler pid=$pid process state unavailable", e)
        "targetPid=${handle.pid()}, alive=unavailable"
    }

    private fun elapsedMillis(): Long = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)

    companion object {
        private const val MAX_DUMP_BYTES = 256 * 1024
    }
}
