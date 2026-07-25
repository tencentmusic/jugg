package com.sickworm.intellij.jugg.ai.skills

import java.util.concurrent.TimeUnit

/** Resolves a Python 3.7+ command for Jugg CLI and agent hooks. */
object PythonRuntimeResolver {
    private const val MINIMUM_MAJOR = 3
    private const val MINIMUM_MINOR = 7
    private val pythonVersionPattern = Regex("Python\\s+(\\d+)\\.(\\d+)", RegexOption.IGNORE_CASE)

    fun resolve(commands: List<String> = listOf("python3", "python")): String? {
        return commands.firstOrNull(::isSupportedPython)
    }

    fun requireCommand(): String {
        return resolve() ?: throw IllegalStateException(
            "Python 3.7+ was not found. Install Python or add python3/python to PATH.",
        )
    }

    private fun isSupportedPython(command: String): Boolean {
        return runCatching {
            val process = ProcessBuilder(command, "--version")
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().use { it.readText() }
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                return false
            }
            process.exitValue() == 0 && output.isSupportedPythonVersion()
        }.getOrDefault(false)
    }

    private fun String.isSupportedPythonVersion(): Boolean {
        val match = pythonVersionPattern.find(this) ?: return false
        val major = match.groupValues[1].toIntOrNull() ?: return false
        val minor = match.groupValues[2].toIntOrNull() ?: return false
        return major > MINIMUM_MAJOR || (major == MINIMUM_MAJOR && minor >= MINIMUM_MINOR)
    }
}
