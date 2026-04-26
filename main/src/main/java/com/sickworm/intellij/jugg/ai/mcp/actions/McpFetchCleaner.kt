package com.sickworm.intellij.jugg.ai.mcp.actions

import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.jugg.project.JuggPathManager
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * McpFetchCleaner cleans expired artifacts under JuggPathManager.mcpFetchDir on startup.
 * Data Contract: any file older than [retentionDays] (based on lastModified) is removed.
 */
object McpFetchCleaner {
    private const val DEFAULT_RETENTION_DAYS = 30L

    data class CleanupResult(
        val scannedFiles: Int,
        val expiredFiles: Int,
        val deletedFiles: Int,
        val failedFiles: Int,
        val deletedEmptyDirs: Int,
        val rootDirExists: Boolean,
    )

    /**
     * Remove expired files and then prune empty folders.
     */
    fun cleanupExpiredFiles(
        rootDir: File,
        logger: Logger,
        retentionDays: Long = DEFAULT_RETENTION_DAYS,
        nowMs: Long = System.currentTimeMillis(),
    ): CleanupResult {
        if (retentionDays <= 0) {
            logger.warn("skip mcp_fetch cleanup because retentionDays=$retentionDays is invalid")
            return CleanupResult(0, 0, 0, 0, 0, rootDirExists = false)
        }

        if (!rootDir.exists()) {
            logger.debug("skip mcp_fetch cleanup because ${rootDir.absolutePath} does not exist")
            return CleanupResult(0, 0, 0, 0, 0, rootDirExists = false)
        }

        val expireBeforeMs = nowMs - TimeUnit.DAYS.toMillis(retentionDays)
        val files = rootDir.walkTopDown().filter { it.isFile }.toList()

        var expiredFiles = 0
        var deletedFiles = 0
        var failedFiles = 0
        files.forEach { file ->
            if (file.lastModified() > expireBeforeMs) {
                return@forEach
            }
            expiredFiles++
            if (file.delete()) {
                deletedFiles++
            } else {
                failedFiles++
                logger.warn("delete expired mcp_fetch file failed: ${file.absolutePath}")
            }
        }

        val deletedEmptyDirs = pruneEmptyDirectories(rootDir, logger)
        val result = CleanupResult(
            scannedFiles = files.size,
            expiredFiles = expiredFiles,
            deletedFiles = deletedFiles,
            failedFiles = failedFiles,
            deletedEmptyDirs = deletedEmptyDirs,
            rootDirExists = true,
        )
        logger.debug(
            "mcp_fetch cleanup done, scanned=${result.scannedFiles}, expired=${result.expiredFiles}, " +
                "deleted=${result.deletedFiles}, failed=${result.failedFiles}, deletedEmptyDirs=${result.deletedEmptyDirs}"
        )
        return result
    }

    private fun pruneEmptyDirectories(rootDir: File, logger: Logger): Int {
        var deletedDirCount = 0
        rootDir.walkBottomUp()
            .filter { it.isDirectory && it != rootDir }
            .forEach { dir ->
                val isEmpty = dir.list()?.isEmpty() == true
                if (!isEmpty) {
                    return@forEach
                }
                if (dir.delete()) {
                    deletedDirCount++
                } else {
                    logger.warn("delete empty mcp_fetch dir failed: ${dir.absolutePath}")
                }
            }
        return deletedDirCount
    }
}
