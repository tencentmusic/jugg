package com.sickworm.intellij.jugg.project

import com.intellij.openapi.diagnostic.Logger
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Removes expired project-local artifacts and prunes directories left empty by cleanup.
 */
object ExpiredArtifactCleaner {

    data class CleanupResult(
        val scannedFiles: Int,
        val expiredFiles: Int,
        val deletedFiles: Int,
        val failedFiles: Int,
        val deletedEmptyDirs: Int,
        val rootDirExists: Boolean,
    )

    fun cleanupExpiredFiles(
        rootDir: File,
        logger: Logger,
        retentionDays: Long,
        nowMs: Long = System.currentTimeMillis(),
    ): CleanupResult {
        if (retentionDays <= 0) {
            logger.warn("skip ${rootDir.name} cleanup because retentionDays=$retentionDays is invalid")
            return CleanupResult(0, 0, 0, 0, 0, rootDirExists = false)
        }

        if (!rootDir.exists()) {
            logger.debug("skip ${rootDir.name} cleanup because ${rootDir.absolutePath} does not exist")
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
                logger.warn("delete expired ${rootDir.name} file failed: ${file.absolutePath}")
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
        logger.debug("${rootDir.name} cleanup done, scanned=${result.scannedFiles}, " +
                "expired=${result.expiredFiles}, deleted=${result.deletedFiles}, " +
                "failed=${result.failedFiles}, deletedEmptyDirs=${result.deletedEmptyDirs}")
        return result
    }

    private fun pruneEmptyDirectories(rootDir: File, logger: Logger): Int {
        var deletedDirCount = 0
        rootDir.walkBottomUp()
            .filter { it.isDirectory && it != rootDir }
            .forEach { dir ->
                if (dir.list()?.isEmpty() != true) {
                    return@forEach
                }
                if (dir.delete()) {
                    deletedDirCount++
                } else {
                    logger.warn("delete empty ${rootDir.name} dir failed: ${dir.absolutePath}")
                }
            }
        return deletedDirCount
    }
}
