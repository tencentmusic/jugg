package com.sickworm.intellij.jugg.deploy.run

/**
 * AndroidTestLogAttributor keeps a bounded run-level logcat buffer and projects matching slices into
 * method logs when the instrumentation lifecycle or TestRunner markers identify a method boundary.
 */
internal class AndroidTestLogAttributor(
    private val maxBufferedLogcatLines: Int = DEFAULT_LOGCAT_BUFFER_MAX_LINES,
    private val emitMethodLog: (className: String, testName: String, line: String) -> Unit,
) {
    private val buffer = AndroidTestLogcatBuffer(maxBufferedLogcatLines)
    private val markerWindows = mutableListOf<MarkerWindow>()
    private val emittedMarkerKeys = mutableSetOf<MarkerWindowKey>()
    private var allowedPids: Set<Int>? = null
    private var activeWindow: LifecycleWindow? = null

    @Synchronized
    fun setAllowedPids(pids: Set<Int>?) {
        allowedPids = pids?.takeIf { it.isNotEmpty() }
    }

    @Synchronized
    fun onTestStarted(className: String, testName: String) {
        flushActiveWindow(buffer.nextSequence())
        activeWindow = LifecycleWindow(className, testName, buffer.nextSequence())
    }

    @Synchronized
    fun onTestFinished(className: String, testName: String) {
        if (emitCompletedMarkerWindow(className, testName)) {
            activeWindow = activeWindow?.takeUnless { it.matches(className, testName) }
            return
        }
        val window = activeWindow ?: return
        if (!window.matches(className, testName)) return
        if (allowedPids == null) {
            flushActiveWindow(buffer.nextSequence())
        }
    }

    @Synchronized
    fun onAborted() {
        flushActiveWindow(buffer.nextSequence())
    }

    @Synchronized
    fun onLogLine(line: String) {
        val entry = buffer.add(line)
        val marker = parseTestRunnerMarker(entry) ?: return
        when (marker.type) {
            MarkerType.STARTED -> markerWindows.add(
                MarkerWindow(
                    className = marker.className,
                    testName = marker.testName,
                    startSequence = entry.sequence,
                    pid = entry.pid,
                ),
            )
            MarkerType.FINISHED -> markerWindows
                .lastOrNull { it.matches(marker.className, marker.testName) && it.endSequence == null }
                ?.endSequence = entry.sequence
        }
    }

    @Synchronized
    fun finish(): AndroidTestLogcatBuffer.Stats {
        flushActiveWindow(buffer.nextSequence())
        val stats = buffer.stats()
        activeWindow = null
        markerWindows.clear()
        emittedMarkerKeys.clear()
        buffer.clear()
        return stats
    }

    private fun flushActiveWindow(endSequence: Long) {
        val window = activeWindow ?: return
        emitEntries(window.className, window.testName) { entry ->
            entry.sequence >= window.startSequence &&
                    entry.sequence < endSequence &&
                    shouldEmitByAllowedPid(entry) &&
                    !isTestRunnerMarkerLine(entry.line)
        }
        activeWindow = null
    }

    private fun emitCompletedMarkerWindow(className: String, testName: String): Boolean {
        val window = markerWindows.lastOrNull {
            it.matches(className, testName) && it.endSequence != null && markerKey(it) !in emittedMarkerKeys
        } ?: return false
        val startSequence = window.startSequence
        val endSequence = window.endSequence ?: return false
        emitEntries(className, testName) { entry ->
            entry.sequence > startSequence &&
                    entry.sequence < endSequence &&
                    !isTestRunnerMarkerLine(entry.line) &&
                    (window.pid == null || entry.pid == window.pid)
        }
        emittedMarkerKeys.add(markerKey(window))
        return true
    }

    private fun emitEntries(
        className: String,
        testName: String,
        shouldEmit: (AndroidTestLogcatBuffer.Entry) -> Boolean,
    ) {
        buffer.snapshot().filter(shouldEmit).forEach { entry ->
            emitMethodLog(className, testName, entry.line)
        }
    }

    private fun shouldEmitByAllowedPid(entry: AndroidTestLogcatBuffer.Entry): Boolean {
        val pids = allowedPids ?: return true
        return entry.pid in pids
    }

    private fun parseTestRunnerMarker(entry: AndroidTestLogcatBuffer.Entry): TestRunnerMarker? {
        val match = TEST_RUNNER_MARKER_REGEX.find(entry.line) ?: return null
        val type = when (match.groupValues[1]) {
            "started" -> MarkerType.STARTED
            "finished" -> MarkerType.FINISHED
            else -> return null
        }
        return TestRunnerMarker(
            type = type,
            testName = match.groupValues[2],
            className = match.groupValues[3],
            pid = entry.pid,
        )
    }

    private fun isTestRunnerMarkerLine(line: String): Boolean {
        return TEST_RUNNER_MARKER_REGEX.containsMatchIn(line)
    }

    private fun markerKey(window: MarkerWindow): MarkerWindowKey {
        return MarkerWindowKey(window.className, window.testName, window.startSequence, window.endSequence)
    }

    private data class LifecycleWindow(
        val className: String,
        val testName: String,
        val startSequence: Long,
    ) {
        fun matches(className: String, testName: String): Boolean {
            return this.className == className && this.testName == testName
        }
    }

    private data class MarkerWindow(
        val className: String,
        val testName: String,
        val startSequence: Long,
        val pid: Int?,
        var endSequence: Long? = null,
    ) {
        fun matches(className: String, testName: String): Boolean {
            return this.className == className && this.testName == testName
        }
    }

    private data class MarkerWindowKey(
        val className: String,
        val testName: String,
        val startSequence: Long,
        val endSequence: Long?,
    )

    private data class TestRunnerMarker(
        val type: MarkerType,
        val testName: String,
        val className: String,
        val pid: Int?,
    )

    private enum class MarkerType {
        STARTED,
        FINISHED,
    }
}

/**
 * Stores a bounded snapshot of logcat lines for one instrumentation run and releases references
 * after attribution is complete.
 */
internal class AndroidTestLogcatBuffer(
    private val maxLines: Int = DEFAULT_LOGCAT_BUFFER_MAX_LINES,
) {
    private val lines = ArrayDeque<Entry>()
    private var nextSequence = 0L
    private var byteSize = 0L
    private var totalLineCount = 0L
    private var truncatedLineCount = 0L

    init {
        require(maxLines > 0) { "maxLines must be positive" }
    }

    fun add(line: String): Entry {
        val entry = Entry(nextSequence++, line, parseThreadtimePid(line))
        lines.addLast(entry)
        totalLineCount++
        byteSize += lineByteSize(line)
        while (lines.size > maxLines) {
            val removed = lines.removeFirst()
            byteSize -= lineByteSize(removed.line)
            truncatedLineCount++
        }
        return entry
    }

    fun nextSequence(): Long = nextSequence

    fun snapshot(): List<Entry> = lines.toList()

    fun stats(): Stats = Stats(
        lineCount = lines.size,
        byteSize = byteSize,
        totalLineCount = totalLineCount,
        truncatedLineCount = truncatedLineCount,
        maxLines = maxLines,
    )

    fun clear() {
        lines.clear()
        nextSequence = 0L
        byteSize = 0L
        totalLineCount = 0L
        truncatedLineCount = 0L
    }

    private fun lineByteSize(line: String): Int {
        return line.toByteArray(Charsets.UTF_8).size + 1
    }

    data class Entry(
        val sequence: Long,
        val line: String,
        val pid: Int?,
    )

    data class Stats(
        val lineCount: Int,
        val byteSize: Long,
        val totalLineCount: Long,
        val truncatedLineCount: Long,
        val maxLines: Int,
    )
}

internal const val DEFAULT_LOGCAT_BUFFER_MAX_LINES = 100_000

private val TEST_RUNNER_MARKER_REGEX = Regex(
    "^\\d{2}-\\d{2}\\s+\\d{2}:\\d{2}:\\d{2}\\.\\d+\\s+\\d+\\s+\\d+\\s+[VDIWEAF]\\s+TestRunner\\s*:\\s+" +
            "(started|finished):\\s+([^()]+)\\(([^)]+)\\).*$",
)
