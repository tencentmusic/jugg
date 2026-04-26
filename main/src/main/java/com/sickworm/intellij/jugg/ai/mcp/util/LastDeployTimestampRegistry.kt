package com.sickworm.intellij.jugg.ai.mcp.util

import java.util.concurrent.ConcurrentHashMap

/**
 * LastDeployTimestampRegistry records the most-recent successful deploy/restart timestamp per project.
 * The timestamp format matches `adb logcat -v threadtime`: "MM-dd HH:mm:ss.SSS".
 */
class LastDeployTimestampRegistry {

    private val timestamps = ConcurrentHashMap<String, String>()

    /**
     * Record a successful deploy/restart for [projectDir] at the current system time.
     * Automatically formats the timestamp to match logcat threadtime output.
     */
    fun recordNow(projectDir: String) {
        val ts = formatNow()
        timestamps[projectDir] = ts
    }

    /** Directly set a timestamp string (used in tests and by deploy/restart success paths). */
    fun setTimestamp(projectDir: String, timestamp: String) {
        timestamps[projectDir] = timestamp
    }

    /**
     * Return the stored timestamp for [projectDir], or null if none has been recorded.
     */
    fun getTimestamp(projectDir: String): String? = timestamps[projectDir]

    private fun formatNow(): String {
        val now = java.util.Date()
        val cal = java.util.Calendar.getInstance()
        cal.time = now
        val month = cal.get(java.util.Calendar.MONTH) + 1
        val day = cal.get(java.util.Calendar.DAY_OF_MONTH)
        val hour = cal.get(java.util.Calendar.HOUR_OF_DAY)
        val min = cal.get(java.util.Calendar.MINUTE)
        val sec = cal.get(java.util.Calendar.SECOND)
        val ms = cal.get(java.util.Calendar.MILLISECOND)
        return "%02d-%02d %02d:%02d:%02d.%03d".format(month, day, hour, min, sec, ms)
    }

    companion object {
        /** Singleton instance shared across MCP tool actions and deploy/restart pipelines. */
        val INSTANCE = LastDeployTimestampRegistry()
    }
}
