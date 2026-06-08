package com.sickworm.intellij.jugg.ai.skills

import java.io.File
import java.io.IOException

/**
 * Pure helpers for merging a directory into the Windows user PATH.
 */
object WindowsUserPathHelper {

    fun containsPathEntry(currentPath: String?, targetPath: String): Boolean {
        val targetFull = normalizePathEntry(targetPath)
        if (targetFull.isEmpty()) {
            return false
        }
        if (currentPath.isNullOrBlank()) {
            return false
        }
        return currentPath.split(';').any { entry ->
            val normalized = normalizePathEntry(entry)
            normalized.isNotEmpty() && normalized.equals(targetFull, ignoreCase = true)
        }
    }

    fun prependPathEntry(currentPath: String?, targetPath: String): String {
        return if (currentPath.isNullOrBlank()) {
            targetPath
        } else {
            "$targetPath;$currentPath"
        }
    }

    fun normalizePathEntry(path: String): String {
        val trimmed = path.trim().trimEnd('\\', '/')
        if (trimmed.isEmpty()) {
            return ""
        }
        return runCatching { File(trimmed).canonicalPath.trimEnd('\\', '/') }
            .getOrDefault(trimmed)
    }
}

/**
 * Updates the Windows user PATH via reg.exe (faster than spawning PowerShell).
 */
object WindowsUserPathUpdater {

    private const val REG_KEY = "HKCU\\Environment"
    private const val PATH_VALUE = "Path"

    fun prependIfMissing(targetPath: String) {
        val targetFull = WindowsUserPathHelper.normalizePathEntry(targetPath)
        if (targetFull.isEmpty()) {
            return
        }
        val current = readUserPath()
        if (WindowsUserPathHelper.containsPathEntry(current, targetFull)) {
            return
        }
        val updated = WindowsUserPathHelper.prependPathEntry(current, targetFull)
        writeUserPath(updated)
    }

    internal fun readUserPath(): String? {
        val process = ProcessBuilder("reg", "query", REG_KEY, "/v", PATH_VALUE)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        if (process.waitFor() != 0) {
            return null
        }
        return parseRegQueryPathValue(output)
    }

    internal fun writeUserPath(value: String) {
        val process = ProcessBuilder(
            "reg",
            "add",
            REG_KEY,
            "/v",
            PATH_VALUE,
            "/t",
            "REG_SZ",
            "/d",
            value,
            "/f",
        )
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        if (process.waitFor() != 0) {
            throw IOException("failed_to_update_user_path: ${output.trim()}")
        }
    }

    internal fun parseRegQueryPathValue(output: String): String? {
        val line = output.lineSequence()
            .firstOrNull { it.trimStart().startsWith(PATH_VALUE) }
            ?: return null
        val parts = line.trim().split(Regex("\\s{2,}"), limit = 3)
        if (parts.size < 3) {
            return null
        }
        return parts[2].trim()
    }
}
