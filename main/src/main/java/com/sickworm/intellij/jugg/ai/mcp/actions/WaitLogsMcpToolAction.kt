package com.sickworm.intellij.jugg.ai.mcp.actions

import com.sickworm.intellij.jugg.deploy.IDeviceAdb
import com.sickworm.intellij.jugg.ai.mcp.DeviceSelectionResolver
import com.sickworm.intellij.jugg.ai.mcp.DeviceSelectionResult
import com.sickworm.intellij.jugg.ai.mcp.IMcpRuntime
import com.sickworm.intellij.jugg.ai.mcp.McpArtifact
import com.sickworm.intellij.jugg.ai.mcp.McpErrorCode
import com.sickworm.intellij.jugg.ai.mcp.McpJsonSchemaObject
import com.sickworm.intellij.jugg.ai.mcp.McpJsonSchemaProperty
import com.sickworm.intellij.jugg.ai.mcp.McpToolDefinition
import com.sickworm.intellij.jugg.ai.mcp.McpToolResult
import com.sickworm.intellij.jugg.ai.mcp.McpToolStatus
import com.sickworm.intellij.jugg.ai.mcp.util.CrashDetector
import com.sickworm.intellij.jugg.ai.mcp.util.CrashSignal
import com.sickworm.intellij.jugg.ai.mcp.util.LastDeployTimestampRegistry
import com.sickworm.intellij.jugg.platform.PlatformApi
import com.sickworm.intellij.jugg.project.JuggPathManager
import java.io.File
import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException

/**
 * WaitLogsMcpToolAction implements MCP tool `wait-logs`.
 *
 * Blocks until a marker regex matches a log line from the target app process,
 * a crash signal is detected and the main process dies, or the timeout expires.
 * Returns the filtered log window and stop reason.
 */
class WaitLogsMcpToolAction(
    private val timestampRegistry: LastDeployTimestampRegistry = LastDeployTimestampRegistry.INSTANCE,
) : McpToolAction {

    override val toolName: String = McpToolActionRegistry.ToolNames.WAIT_LOGS

    override val definition: McpToolDefinition = McpToolDefinition(
        name = toolName,
        description = "Block until a marker appears in app logs, a crash occurs, or the timeout expires. " +
            "Uses the most-recent deploy/restart timestamp as the log start point.",
        inputSchema = McpJsonSchemaObject(
            properties = mapOf(
                "projectDir" to McpToolSchemas.projectDirProperty,
                "marker" to McpJsonSchemaProperty(
                    type = "string",
                    description = "Stop-condition regex (Java Pattern dialect) matched against log message part.",
                ),
                "tags" to McpJsonSchemaProperty(
                    type = "array",
                    items = McpJsonSchemaProperty(type = "string"),
                    description = "Tag whitelist (exact match). Empty = no tag filter.",
                ),
                "timeoutMs" to McpJsonSchemaProperty(
                    type = "integer",
                    description = "Hard timeout in milliseconds. Range [1000, 300000]. Default: 30000.",
                    minimum = 1000.0,
                    maximum = 300000.0,
                ),
            ),
            required = listOf("projectDir", "marker"),
            additionalProperties = false,
        ),
        outputSchema = McpToolSchemas.baseOutputSchema,
    )

    override fun execute(arguments: Map<String, Any?>, runtime: IMcpRuntime): McpToolResult {
        // --- param extraction ---
        val projectDir = arguments["projectDir"] as? String
            ?: return errorResult(McpErrorCode.INVALID_PARAMS, "projectDir is required")

        val markerStr = arguments["marker"] as? String
            ?: return errorResult(McpErrorCode.INVALID_PARAMS, "marker is required")

        @Suppress("UNCHECKED_CAST")
        val tags: Set<String> = (arguments["tags"] as? List<String>)?.toSet() ?: emptySet()

        val timeoutMs: Int = when (val raw = arguments["timeoutMs"]) {
            null -> DEFAULT_TIMEOUT_MS
            is Number -> raw.toInt()
            else -> return errorResult(McpErrorCode.INVALID_PARAMS, "timeoutMs must be an integer")
        }
        if (timeoutMs < 1000 || timeoutMs > 300000) {
            return errorResult(McpErrorCode.INVALID_PARAMS, "timeoutMs must be in [1000, 300000]")
        }

        // --- compile marker pattern ---
        val markerPattern: Pattern = try {
            Pattern.compile(markerStr)
        } catch (e: PatternSyntaxException) {
            return errorResult(McpErrorCode.INVALID_REGEX, "Invalid marker regex at index ${e.index}: ${e.description}")
        }

        // --- deploy baseline ---
        val sinceTime = timestampRegistry.getTimestamp(projectDir)
            ?: return errorResult(McpErrorCode.NO_DEPLOY_BASELINE, "No deploy baseline found for project. Run deploy or restart first.")

        // --- device ---
        val adb = resolveAdb(runtime)
            ?: return errorResult(McpErrorCode.NO_DEVICE, "No connected device is available.")

        val packageName = runtime.deployTargetManager.getPackageNameOrNull() ?: ""

        return runWaitLogs(
            adb = adb,
            packageName = packageName,
            markerPattern = markerPattern,
            tags = tags,
            timeoutMs = timeoutMs,
            sinceTime = sinceTime,
            projectDir = projectDir,
        )
    }

    /**
     * Core wait-logs logic. Reads enqueued log lines from the fake adb in tests,
     * or launches a real `adb logcat` subprocess in production via [AdbLogcatSource].
     */
    private fun runWaitLogs(
        adb: IDeviceAdb,
        packageName: String,
        markerPattern: Pattern,
        tags: Set<String>,
        timeoutMs: Int,
        sinceTime: String,
        projectDir: String,
    ): McpToolResult {
        val source: AdbLogcatSource = if (adb is LogcatSourceProvider) {
            adb.createLogcatSource(sinceTime)
        } else {
            RealAdbLogcatSource(adb, sinceTime)
        }

        val ringBuffer = ArrayDeque<String>(RING_BUFFER_SIZE)
        val allLogsFile = prepareAllLogsFile(projectDir)

        var stopReason = "timeout"
        var startTime: String? = null
        var endTime: String? = null

        var lastTargetPidsQuery = 0L
        var cachedTargetPids = emptySet<Int>()
        var lastCrashCheck = 0L
        var lastCrashResult = true // true = ALIVE
        var mainProcessEverSeen = false

        val deadline = System.currentTimeMillis() + timeoutMs

        try {
            while (System.currentTimeMillis() < deadline) {
                val line = source.nextLine(pollTimeoutMs = 100) ?: continue

                if (startTime == null) {
                    startTime = parseTimestamp(line)
                }

                // Write full raw line to allLogs file
                allLogsFile?.appendText(line + "\n")

                val parsedTag = parseTag(line)
                val shouldBuffer = tags.isEmpty() || parsedTag in tags || parsedTag in CrashDetector.CRASH_TAGS

                if (shouldBuffer) {
                    if (ringBuffer.size >= RING_BUFFER_SIZE) {
                        ringBuffer.removeFirst()
                    }
                    ringBuffer.addLast(line)
                }

                val linePid = parsePid(line) ?: continue
                val lineMsg = parseMessage(line)

                // --- marker check ---
                if (lineMsg != null && markerPattern.matcher(lineMsg).find()) {
                    val now = System.currentTimeMillis()
                    if (now - lastTargetPidsQuery >= 200L) {
                        cachedTargetPids = resolveTargetPids(adb, packageName)
                        lastTargetPidsQuery = now
                    }
                    if (linePid in cachedTargetPids) {
                        stopReason = "marker"
                        endTime = parseTimestamp(line)
                        break
                    }
                    // PID not in target — ignore this line
                    continue
                }

                // --- crash check ---
                val crashSignal = CrashDetector.classify(line)
                if (crashSignal != CrashSignal.NONE && parsedTag in CrashDetector.CRASH_TAGS) {
                    val now = System.currentTimeMillis()
                    val isStrong = crashSignal == CrashSignal.STRONG
                    if (isStrong || now - lastCrashCheck >= 500L || !lastCrashResult) {
                        val mainPids = resolveMainPids(adb, packageName)
                        lastCrashCheck = now
                        if (mainPids.isNotEmpty()) {
                            mainProcessEverSeen = true
                            lastCrashResult = true
                        } else {
                            lastCrashResult = false
                            if (mainProcessEverSeen) {
                                stopReason = "crash"
                                endTime = parseTimestamp(line)
                                break
                            }
                        }
                    }
                }
            }
        } finally {
            source.close()
        }

        if (endTime == null) endTime = parseTimestamp(ringBuffer.lastOrNull() ?: "")
        val finalTargetPids = resolveTargetPids(adb, packageName)

        // Precision filter: keep only target PIDs and crash lines, apply tags filter
        val filtered = ringBuffer.filter { line ->
            val pid = parsePid(line)
            val tag = parseTag(line)
            val isCrashLine = tag in CrashDetector.CRASH_TAGS && CrashDetector.classify(line) != CrashSignal.NONE
            val isTargetPid = pid != null && pid in finalTargetPids
            val passTagFilter = tags.isEmpty() || tag in tags
            (isCrashLine || isTargetPid) && passTagFilter
        }

        val truncated = filtered.size > MAX_RETURN_LINES
        val returnedLines = if (truncated) filtered.takeLast(MAX_RETURN_LINES) else filtered
        val logsStr = returnedLines.joinToString("\n")

        val allLogsPath = allLogsFile?.absolutePath ?: ""
        var message = "stopped by $stopReason"
        if (truncated) {
            message += ". logs truncated to last $MAX_RETURN_LINES lines, full log at $allLogsPath"
        }

        val artifacts = if (allLogsFile != null) {
            listOf(McpArtifact(type = "file", path = allLogsPath))
        } else {
            emptyList()
        }

        return McpToolResult(
            status = McpToolStatus.OK,
            message = message,
            data = mutableMapOf<String, Any>().apply {
                put("stopReason", stopReason)
                if (startTime != null) put("startTime", startTime!!)
                if (endTime != null) put("endTime", endTime!!)
                put("targetPids", finalTargetPids.toList())
                put("logs", logsStr)
                put("allLogsPath", allLogsPath)
                put("truncated", truncated)
            },
            artifacts = artifacts,
            errorCode = null,
        )
    }

    private fun resolveTargetPids(adb: IDeviceAdb, packageName: String): Set<Int> {
        if (packageName.isBlank()) return emptySet()
        val mainResult = runCatching { adb.execAdbShellCmd("pidof $packageName") }.getOrDefault("")
        val mainPids = PID_REGEX.findAll(mainResult).mapNotNull { it.value.toIntOrNull() }.toMutableSet()

        val psResult = runCatching {
            adb.execAdbShellCmd("ps -ef | grep $packageName:")
        }.getOrDefault("")
        psResult.lineSequence()
            .filter { it.contains(packageName) }
            .forEach { line ->
                val parts = line.trim().split(WHITESPACE_REGEX)
                parts.getOrNull(1)?.toIntOrNull()?.let { mainPids += it }
            }
        return mainPids
    }

    private fun resolveMainPids(adb: IDeviceAdb, packageName: String): Set<Int> {
        if (packageName.isBlank()) return emptySet()
        val raw = runCatching { adb.execAdbShellCmd("pidof $packageName") }.getOrDefault("")
        return PID_REGEX.findAll(raw).mapNotNull { it.value.toIntOrNull() }.toSet()
    }

    private fun resolveAdb(runtime: IMcpRuntime): IDeviceAdb? {
        val selectionResult = DeviceSelectionResolver().resolve(runtime.deployTargetManager)
        if (selectionResult !is DeviceSelectionResult.Selected) return null
        val adb = PlatformApi.toDeviceAdb(selectionResult.device) ?: return null
        if (!adb.isOnline) return null
        return adb
    }

    private fun prepareAllLogsFile(projectDir: String): File? {
        return try {
            val dir = File(JuggPathManager(File(projectDir)).mcpFetchDir, "wait-logs")
            if (!dir.exists()) dir.mkdirs()
            val ts = System.currentTimeMillis()
            File(dir, "wait-logs-$ts.log")
        } catch (_: Exception) {
            null
        }
    }

    private fun errorResult(code: String, msg: String): McpToolResult = McpToolResult(
        status = McpToolStatus.ERROR,
        message = "$toolName failed. Reason: $msg",
        data = emptyMap<String, Any>(),
        artifacts = emptyList(),
        errorCode = code,
    )

    companion object {
        private const val DEFAULT_TIMEOUT_MS = 30000
        private const val RING_BUFFER_SIZE = 10000
        private const val MAX_RETURN_LINES = 100

        private val PID_REGEX = Regex("\\d+")
        private val WHITESPACE_REGEX = Regex("\\s+")

        // logcat threadtime format: "MM-dd HH:mm:ss.SSS  pid  tid L tag: message"
        private val THREADTIME_REGEX = Regex(
            "^(\\d{2}-\\d{2}\\s+\\d{2}:\\d{2}:\\d{2}\\.\\d+)\\s+(\\d+)\\s+\\d+\\s+[VDIWEAF]\\s+([^:]+):\\s*(.*)\$"
        )

        internal fun parseTimestamp(line: String): String? =
            THREADTIME_REGEX.find(line)?.groupValues?.getOrNull(1)

        internal fun parsePid(line: String): Int? =
            THREADTIME_REGEX.find(line)?.groupValues?.getOrNull(2)?.toIntOrNull()

        internal fun parseTag(line: String): String =
            THREADTIME_REGEX.find(line)?.groupValues?.getOrNull(3)?.trim() ?: ""

        internal fun parseMessage(line: String): String? =
            THREADTIME_REGEX.find(line)?.groupValues?.getOrNull(4)
    }
}

/**
 * AdbLogcatSource abstracts logcat line production for testability.
 * Production uses [RealAdbLogcatSource]; tests inject via [LogcatSourceProvider].
 */
interface AdbLogcatSource {
    /** Return the next available log line, or null if none within [pollTimeoutMs]. */
    fun nextLine(pollTimeoutMs: Long): String?
    fun close()
}

/**
 * IDeviceAdb extension that test fakes implement to inject a custom AdbLogcatSource.
 */
interface LogcatSourceProvider {
    fun createLogcatSource(sinceTime: String): AdbLogcatSource
}

/**
 * RealAdbLogcatSource launches `adb logcat -T <sinceTime> -v threadtime` as a subprocess
 * and streams lines via blocking I/O on a background thread.
 */
private class RealAdbLogcatSource(
    private val adb: IDeviceAdb,
    private val sinceTime: String,
) : AdbLogcatSource {

    private val queue = java.util.concurrent.LinkedBlockingQueue<String>()
    @Volatile private var running = true
    private val thread: Thread

    init {
        thread = Thread({
            try {
                // Run logcat via adb shell, outputting threadtime format starting from sinceTime.
                val output = adb.execAdbShellCmd("logcat -T \"$sinceTime\" -v threadtime")
                output.lineSequence().forEach { line ->
                    if (!running) return@forEach
                    queue.offer(line)
                }
            } catch (_: Exception) {
                // Swallow — caller handles via timeout
            }
        }, "wait-logs-reader").also { it.isDaemon = true }
        thread.start()
    }

    override fun nextLine(pollTimeoutMs: Long): String? =
        queue.poll(pollTimeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)

    override fun close() {
        running = false
        thread.interrupt()
    }
}
