package com.sickworm.intellij.jugg.mcp.util

/**
 * CrashSignal classifies a logcat line as a strong crash marker, weak crash marker, or neither.
 *
 * - STRONG: immediate stop signal (FATAL EXCEPTION, Fatal signal)
 * - WEAK: supporting/fallback crash indicator (backtrace, Process:, etc.)
 * - NONE: no crash signal detected
 */
enum class CrashSignal {
    STRONG, WEAK, NONE
}

/**
 * CrashDetector recognises Android crash signals in raw logcat threadtime lines.
 * Internal use only — not exposed as an independent MCP tool.
 */
object CrashDetector {

    /** Tags that may carry crash signals and should pass coarse log filter. */
    val CRASH_TAGS: Set<String> = setOf(
        "AndroidRuntime",
        "libc",
        "DEBUG",
        "tombstoned",
        "ActivityManager",
    )

    private val STRONG_MARKERS = listOf(
        "FATAL EXCEPTION",
        "Fatal signal",
    )

    private val WEAK_MARKERS = listOf(
        "backtrace:",
        "Process:",
        "AndroidRuntime",
    )

    /**
     * Classify a single raw logcat threadtime line.
     *
     * @param rawLine full logcat line, e.g. "04-19 10:00:00.000  1234  1234 E AndroidRuntime: FATAL EXCEPTION: main"
     */
    fun classify(rawLine: String): CrashSignal {
        if (rawLine.isBlank()) return CrashSignal.NONE
        if (STRONG_MARKERS.any { rawLine.contains(it) }) return CrashSignal.STRONG
        if (WEAK_MARKERS.any { rawLine.contains(it) }) return CrashSignal.WEAK
        return CrashSignal.NONE
    }

    /**
     * Extract a crash snippet starting from [hitIndex] in [rawLines], capped at 80 lines.
     * Stops early at a buffer-boundary sentinel ("--------- beginning").
     *
     * @return newline-joined string of the snippet
     */
    fun extractSnippet(rawLines: List<String>, hitIndex: Int): String {
        if (rawLines.isEmpty() || hitIndex < 0 || hitIndex >= rawLines.size) return ""
        val endIndex = minOf(rawLines.lastIndex, hitIndex + 79)
        val selected = mutableListOf<String>()
        for (i in hitIndex..endIndex) {
            val line = rawLines[i]
            if (i > hitIndex && line.startsWith("--------- beginning")) break
            selected += line
            if (selected.size >= 80) break
        }
        return selected.joinToString("\n")
    }
}
