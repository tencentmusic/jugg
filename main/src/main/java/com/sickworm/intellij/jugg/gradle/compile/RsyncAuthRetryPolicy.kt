package com.sickworm.intellij.jugg.gradle.compile

/**
 * Detects transient SSH password auth failures during rsync-over-ssh and controls retry budget.
 */
object RsyncAuthRetryPolicy {

    const val MAX_ATTEMPTS = 3

    val retryDelaysMs: LongArray = longArrayOf(500L, 1500L)

    private val RSYNC_TRANSFER_STARTED_MARKERS = listOf(
        "sending incremental file list",
        "receiving file list",
        "receiving incremental file list",
    )

    fun isRetryable(exitCode: Int, outputLines: Collection<String>): Boolean {
        if (exitCode != 255) {
            return false
        }
        val outputText = outputLines.joinToString("\n")
        if (!outputText.contains("Permission denied", ignoreCase = true)) {
            return false
        }
        if (RSYNC_TRANSFER_STARTED_MARKERS.any { outputText.contains(it, ignoreCase = true) }) {
            return false
        }
        return true
    }
}
