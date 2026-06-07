package com.sickworm.intellij.jugg.project

import com.sickworm.intellij.jugg.compiler.isMac
import com.sickworm.intellij.jugg.compiler.isWindows
import java.io.File
import java.util.Locale

/**
 * Canonicalizes projectDir strings for MCP routing and CLI/MCP agreement.
 *
 * Handles native Windows paths, MSYS/MINGW64 (/d/...), Cygwin (/cygdrive/d/...),
 * and WSL (/mnt/d/...) before delegating to [File] for absolute resolution.
 */
object ProjectDirNormalizer {

    private val wslDrivePath = Regex("^/mnt/([a-zA-Z])(?:/(.*))?$")
    private val cygwinDrivePath = Regex("^/cygdrive/([a-zA-Z])(?:/(.*))?$", RegexOption.IGNORE_CASE)
    private val msysDrivePath = Regex("^/([a-zA-Z])(?:/(.*))?$")

    fun normalizeProjectDir(path: String): String {
        val trimmed = path.trim()
        if (trimmed.isEmpty()) {
            return trimmed
        }
        val converted = convertPosixStyleWindowsPath(trimmed)
        val normalized = resolveToAbsolutePath(converted)
        return finalizeProjectDir(normalized)
    }

    fun projectDirEquals(left: String, right: String): Boolean {
        return normalizeProjectDir(left) == normalizeProjectDir(right)
    }

    internal fun convertPosixStyleWindowsPath(
        path: String,
        msysDriveEnabled: Boolean = isMsysLikeEnvironment(),
    ): String {
        var normalized = path.replace('\\', '/')
        normalized = convertMatchedDrivePath(normalized, wslDrivePath)
            ?: convertMatchedDrivePath(normalized, cygwinDrivePath)
            ?: normalized
        if (msysDriveEnabled) {
            normalized = convertMatchedDrivePath(normalized, msysDrivePath) ?: normalized
        }
        return normalized
    }

    private fun convertMatchedDrivePath(path: String, pattern: Regex): String? {
        val match = pattern.matchEntire(path) ?: return null
        val drive = match.groupValues[1].uppercase(Locale.ROOT)
        val rest = match.groupValues.getOrElse(2) { "" }
        return if (rest.isEmpty()) {
            "$drive:/"
        } else {
            "$drive:/$rest"
        }
    }

    private fun resolveToAbsolutePath(converted: String): String {
        val slashPath = converted.replace('\\', '/')
        if (!isWindows || isWindowsDrivePath(slashPath) || !slashPath.startsWith("/")) {
            return File(converted).absoluteFile.path.replace('\\', '/')
        }
        return slashPath
    }

    private fun finalizeProjectDir(path: String): String {
        if (path.isEmpty()) {
            return path
        }
        var result = path
        if (isWindowsDrivePath(result)) {
            result = result[0].uppercaseChar() + result.substring(1)
        }
        if (shouldFoldCase(result)) {
            result = result.lowercase(Locale.ROOT)
        }
        return result
    }

    private fun isMsysLikeEnvironment(): Boolean {
        if (!System.getenv("MSYSTEM").isNullOrBlank()) {
            return true
        }
        val osType = System.getenv("OSTYPE")?.lowercase(Locale.ROOT).orEmpty()
        return osType.contains("msys") || osType.contains("cygwin")
    }

    private fun isWindowsDrivePath(path: String): Boolean {
        return path.length >= 2 && path[0].isLetter() && path[1] == ':'
    }

    private fun shouldFoldCase(path: String): Boolean {
        return when {
            isWindows && isWindowsDrivePath(path) -> true
            isMac -> true
            else -> false
        }
    }
}
