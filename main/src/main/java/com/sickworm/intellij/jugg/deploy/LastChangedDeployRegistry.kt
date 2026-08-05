package com.sickworm.intellij.jugg.deploy

import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Describes the latest successful deployment that contained Jugg-detected file changes.
 */
data class LastChangedDeploySnapshot(
    val deployedAtMillis: Long,
    val files: List<String>,
)

/**
 * Keeps the latest successful non-empty deployment for each project during the IDE session.
 */
class LastChangedDeployRegistry {

    private val snapshots = ConcurrentHashMap<String, LastChangedDeploySnapshot>()

    fun record(
        projectDir: String,
        files: List<String>,
        deployedAtMillis: Long = System.currentTimeMillis(),
    ) {
        if (projectDir.isBlank()) {
            return
        }
        val normalizedFiles = files.mapNotNull { normalizeFilePath(projectDir, it) }.distinct()
        if (normalizedFiles.isEmpty()) {
            return
        }
        snapshots[normalizeProjectDir(projectDir)] = LastChangedDeploySnapshot(
            deployedAtMillis = deployedAtMillis,
            files = normalizedFiles,
        )
    }

    fun get(projectDir: String): LastChangedDeploySnapshot? {
        return snapshots[normalizeProjectDir(projectDir)]
    }

    private fun normalizeFilePath(projectDir: String, path: String): String? {
        if (path.isBlank()) {
            return null
        }
        val file = File(path)
        val normalized = if (file.isAbsolute) {
            file.relativeToOrSelf(File(projectDir)).path
        } else {
            path
        }
        return normalized.replace('\\', '/').trimStart('/')
    }

    private fun normalizeProjectDir(projectDir: String): String {
        return projectDir.replace('\\', '/').trimEnd('/')
    }

    companion object {
        val INSTANCE = LastChangedDeployRegistry()
    }
}
