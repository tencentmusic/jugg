package com.sickworm.intellij.jugg.ai.mcp.util

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap

/**
 * LastCompileTimestampRegistry records the most-recent compile-tool invocation time per project.
 * The timestamp format is "yyyy-MM-dd HH:mm:ss" for direct display in `status`.
 */
class LastCompileTimestampRegistry {

    private val timestamps = ConcurrentHashMap<String, String>()

    /** Record compile invocation time for [projectDir] using current local time. */
    fun recordNow(projectDir: String) {
        timestamps[projectDir] = LocalDateTime.now().format(READABLE_TIME_FORMATTER)
    }

    /** Directly set a timestamp string (used in tests). */
    fun setTimestamp(projectDir: String, timestamp: String) {
        timestamps[projectDir] = timestamp
    }

    /** Return recorded timestamp for [projectDir], or null if no compile has been invoked yet. */
    fun getTimestamp(projectDir: String): String? = timestamps[projectDir]

    companion object {
        private val READABLE_TIME_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

        /** Singleton instance shared across MCP compile/status actions. */
        val INSTANCE = LastCompileTimestampRegistry()
    }
}
