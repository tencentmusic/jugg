package com.sickworm.intellij.jugg.ai.skills

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.TimeUnit

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
    private const val REG_COMMAND_TIMEOUT_MILLIS = 5_000L

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
        val result = runCommandWithTimeout(
            listOf("reg", "query", REG_KEY, "/v", PATH_VALUE),
            REG_COMMAND_TIMEOUT_MILLIS,
        )
        if (result.exitCode != 0) {
            return null
        }
        return parseRegQueryPathValue(result.output)
    }

    internal fun writeUserPath(value: String) {
        val result = runCommandWithTimeout(
            listOf("reg", "add", REG_KEY, "/v", PATH_VALUE, "/t", "REG_SZ", "/d", value, "/f"),
            REG_COMMAND_TIMEOUT_MILLIS,
        )
        if (result.exitCode != 0) {
            throw IOException("failed_to_update_user_path: ${result.output.trim()}")
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

internal data class ProcessCommandResult(val exitCode: Int, val output: String)

/** Runs a short system command without allowing its output stream or process lifetime to block indefinitely. */
internal fun runCommandWithTimeout(command: List<String>, timeoutMillis: Long): ProcessCommandResult {
    require(command.isNotEmpty()) { "Process command is empty" }
    require(timeoutMillis > 0L) { "Process timeout must be positive" }
    val outputFile = Files.createTempFile("jugg-command-", ".log").toFile()
    var process: Process? = null
    try {
        val runningProcess = ProcessBuilder(command)
            .redirectErrorStream(true)
            .redirectOutput(outputFile)
            .start()
        process = runningProcess
        if (!runningProcess.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)) {
            runningProcess.destroyForcibly()
            throw IOException("process_timeout: ${command.first()}")
        }
        return ProcessCommandResult(runningProcess.exitValue(), outputFile.readText())
    } finally {
        if (process?.isAlive == true) process.destroyForcibly()
        outputFile.delete()
    }
}
