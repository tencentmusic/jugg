package com.sickworm.intellij.jugg.compiler.external

import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.jugg.compiler.CompileTask
import com.sickworm.intellij.jugg.compiler.isWindows
import java.io.File
import java.util.ArrayDeque
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Replaces the original Gradle tasks with external build tasks while preserving validated Gradle arguments.
 */
fun deriveExternalBuildCommand(compileCommand: String, taskPaths: List<String>): String? {
    if (taskPaths.isEmpty() || compileCommand.containsControlOperator()) {
        return null
    }
    val tokens = tokenizeCommand(compileCommand) ?: return null
    val executableIndex = tokens.indexOfFirst { token ->
        token.raw.trim('"', '\'').substringAfterLast('/').substringAfterLast('\\') in gradleExecutables
    }
    if (executableIndex < 0) {
        return null
    }

    val arguments = mutableListOf<String>()
    var index = executableIndex + 1
    while (index < tokens.size) {
        val token = tokens[index]
        when {
            token.value in rejectedOptions -> return null
            token.value in optionsWithValues -> {
                val value = tokens.getOrNull(index + 1) ?: return null
                if (token.value in excludeOptions && isDangerousExclude(value.value, taskPaths)) {
                    return null
                }
                arguments += token.raw
                arguments += value.raw
                index += 2
            }
            token.value.startsWith("--exclude-task=") -> {
                if (isDangerousExclude(token.value.substringAfter('='), taskPaths)) {
                    return null
                }
                arguments += token.raw
                index++
            }
            token.value.startsWith("--") && token.value.contains('=') -> {
                if (token.value.substringBefore('=') in rejectedOptions) {
                    return null
                }
                arguments += token.raw
                index++
            }
            token.value.startsWith("-P") && token.value.length > 2 ||
                    token.value.startsWith("-D") && token.value.length > 2 -> {
                arguments += token.raw
                index++
            }
            token.value in flagOptions -> {
                arguments += token.raw
                index++
            }
            token.value.startsWith('-') -> return null
            else -> index++
        }
    }
    return (tokens.take(executableIndex + 1).map { it.raw } + taskPaths.distinct() + arguments).joinToString(" ")
}

/** Runs selected Gradle tasks while preserving the active run configuration arguments. */
internal class ExternalBuildTaskRunner(private val logger: Logger) {

    fun run(
        compileCommand: String,
        taskPaths: List<String>,
        compileEnv: List<String>,
        projectDir: File,
        task: CompileTask,
    ): Boolean {
        val command = deriveExternalBuildCommand(compileCommand, taskPaths) ?: return false
        logger.debug("External build command: $command")
        val process = startProcess(command, compileEnv, projectDir)
        return waitForProcess(process, task)
    }

    private fun startProcess(command: String, compileEnv: List<String>, projectDir: File): Process {
        val processCommand = if (isWindows) {
            listOf("cmd.exe", "/c", command)
        } else {
            listOf("/bin/bash", "-c", command)
        }
        val processBuilder = ProcessBuilder(processCommand)
            .directory(projectDir)
            .redirectErrorStream(true)
        compileEnv.forEach { entry ->
            val separator = entry.indexOf('=')
            if (separator > 0) {
                processBuilder.environment()[entry.substring(0, separator)] = entry.substring(separator + 1)
            }
        }
        return processBuilder.start()
    }

    private fun waitForProcess(process: Process, task: CompileTask): Boolean {
        val output = ArrayDeque<String>()
        val outputThread = thread(name = "jugg-external-build-output", isDaemon = true) {
            process.inputStream.bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    logger.debug(line)
                    synchronized(output) {
                        if (output.size >= OUTPUT_LINE_LIMIT) output.removeFirst()
                        output.addLast(line)
                    }
                }
            }
        }
        val deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(PROCESS_TIMEOUT_MINUTES)
        while (!process.waitFor(PROCESS_POLL_MILLIS, TimeUnit.MILLISECONDS)) {
            if (task.isShouldCancel) {
                terminate(process)
                outputThread.join()
                return false
            }
            if (System.nanoTime() >= deadline) {
                logger.warn("External build timed out after $PROCESS_TIMEOUT_MINUTES minutes")
                terminate(process)
                outputThread.join()
                return false
            }
        }
        outputThread.join()
        if (process.exitValue() == 0) {
            return true
        }
        val detail = synchronized(output) { output.joinToString("\n") }
        logger.warn("External build failed with exit code ${process.exitValue()}:\n$detail")
        return false
    }

    private fun terminate(process: Process) {
        process.toHandle().descendants().forEach { it.destroyForcibly() }
        process.destroyForcibly()
    }

    companion object {
        private const val PROCESS_TIMEOUT_MINUTES = 10L
        private const val PROCESS_POLL_MILLIS = 100L
        private const val OUTPUT_LINE_LIMIT = 30
    }
}

private data class CommandToken(val raw: String, val value: String)

private fun tokenizeCommand(command: String): List<CommandToken>? {
    val tokens = mutableListOf<CommandToken>()
    val raw = StringBuilder()
    val value = StringBuilder()
    var quote: Char? = null
    var escaped = false

    fun finishToken() {
        if (raw.isNotEmpty()) {
            tokens += CommandToken(raw.toString(), value.toString())
            raw.setLength(0)
            value.setLength(0)
        }
    }

    command.trim().forEach { character ->
        if (escaped) {
            raw.append(character)
            value.append(character)
            escaped = false
            return@forEach
        }
        if (character == '\\' && quote != '\'') {
            raw.append(character)
            escaped = true
            return@forEach
        }
        if (quote != null) {
            raw.append(character)
            if (character == quote) {
                quote = null
            } else {
                value.append(character)
            }
            return@forEach
        }
        when {
            character == '"' || character == '\'' -> {
                raw.append(character)
                quote = character
            }
            character.isWhitespace() -> finishToken()
            else -> {
                raw.append(character)
                value.append(character)
            }
        }
    }
    if (quote != null || escaped) {
        return null
    }
    finishToken()
    return tokens
}

private fun String.containsControlOperator(): Boolean {
    return contains('\n') || contains('\r') || contains(';') || contains("&&") || contains("||") ||
            contains("$(") || contains('`') || contains('|') || contains('>') || contains('<')
}

private fun isDangerousExclude(excludedTask: String, taskPaths: List<String>): Boolean {
    val excludedName = excludedTask.substringAfterLast(':')
    return taskPaths.any { taskPath ->
        protectedTaskNames(taskPath).any { protectedName ->
            excludedTask == taskPath || excludedName == protectedName
        }
    }
}

private fun protectedTaskNames(taskPath: String): Set<String> {
    val taskName = taskPath.substringAfterLast(':')
    val names = mutableSetOf(taskName)
    when {
        taskName.startsWith("packJniLibsflutterBuild") -> {
            names += "compileFlutterBuild${taskName.removePrefix("packJniLibsflutterBuild")}"
        }
        taskName.startsWith("packLibsflutterBuild") -> {
            names += "compileFlutterBuild${taskName.removePrefix("packLibsflutterBuild")}"
        }
        taskName.startsWith("merge") && taskName.endsWith("NativeLibs") -> {
            names += "externalNativeBuild${taskName.removePrefix("merge").removeSuffix("NativeLibs")}"
        }
    }
    return names
}

private val gradleExecutables = setOf("gradle", "gradlew", "gradle.bat", "gradlew.bat")
private val rejectedOptions = setOf("--dry-run", "-m", "--help", "-h", "--version", "-v")
private val excludeOptions = setOf("-x", "--exclude-task")
private val optionsWithValues = excludeOptions + setOf(
    "-p", "--project-dir", "-I", "--init-script", "-g", "--gradle-user-home", "-P", "--project-prop",
    "-D", "--system-prop", "-c", "--settings-file", "--include-build", "--console", "--warning-mode",
    "--priority", "--max-workers",
)
private val flagOptions = setOf(
    "--offline", "--no-daemon", "--daemon", "--stacktrace", "--full-stacktrace", "--scan", "--no-scan",
    "--continue", "--rerun-tasks", "--refresh-dependencies", "--build-cache", "--no-build-cache",
    "--configuration-cache", "--no-configuration-cache", "--parallel", "--no-parallel", "--quiet", "-q",
    "--info", "-i", "--debug", "-d",
)
