package com.sickworm.intellij.jugg.deploy

import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Describes the latest successful deployment that contained Jugg-detected file changes.
 */
data class LastChangedDeploySnapshot(
    val deployedAtMillis: Long,
    val files: List<File>,
)

/**
 * Keeps the latest successful non-empty deployment for each project during the IDE session.
 */
class LastChangedDeployRegistry {

    private val snapshots = ConcurrentHashMap<String, LastChangedDeploySnapshot>()

    fun record(
        projectDir: String,
        files: List<File>,
        deployedAtMillis: Long = System.currentTimeMillis(),
    ) {
        if (projectDir.isBlank()) {
            return
        }
        val deployedFiles = files.filter { it.path.isNotBlank() }.distinct()
        if (deployedFiles.isEmpty()) {
            return
        }
        snapshots[normalizeProjectDir(projectDir)] = LastChangedDeploySnapshot(
            deployedAtMillis = deployedAtMillis,
            files = deployedFiles,
        )
    }

    fun get(projectDir: String): LastChangedDeploySnapshot? {
        return snapshots[normalizeProjectDir(projectDir)]
    }

    private fun normalizeProjectDir(projectDir: String): String {
        return projectDir.replace('\\', '/').trimEnd('/')
    }

    companion object {
        val INSTANCE = LastChangedDeployRegistry()
    }
}
