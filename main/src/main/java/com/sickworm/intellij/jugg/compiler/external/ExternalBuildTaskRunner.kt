package com.sickworm.intellij.jugg.compiler.external

import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.jugg.compiler.CompileTask
import com.sickworm.intellij.jugg.compiler.isWindows
import com.sickworm.intellij.jugg.project.info.ProjectInfoSerializer
import com.sickworm.intellij.jugg.project.info.ExternalBuildInfoRequest
import com.sickworm.intellij.jugg.project.info.ExternalBuildInfoRequestItem
import com.sickworm.intellij.jugg.project.info.ExternalBuildInfoUpdate
import com.sickworm.intellij.jugg.project.info.ExternalBuildInfoUpdateResult
import java.io.File
import java.util.ArrayDeque
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Replaces the original Gradle tasks with external build tasks while preserving validated Gradle arguments.
 */
fun deriveExternalBuildCommand(
    compileCommand: String,
    taskPaths: List<String>,
    collector: ExternalBuildCollectorCommand? = null,
): String? {
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
    val tasks = taskPaths.distinct() + listOfNotNull(
        collector?.let { ExternalBuildCollectorCommand.COLLECTOR_TASK_PATH },
    )
    val collectorArguments = collector?.arguments().orEmpty()
    return (tokens.take(executableIndex + 1).map { it.raw } + tasks.distinct() + arguments + collectorArguments)
        .joinToString(" ")
}

/** Arguments required by the invocation-scoped Gradle metadata collector. */
data class ExternalBuildCollectorCommand(
    val initScript: File,
    val requestFile: File,
    val outputDir: File,
    val invocationId: String,
) {
    fun arguments(): List<String> = listOf(
        "-I", quoteCommandArgument(initScript.absolutePath),
        "-Pjugg.externalBuildRequest=${quoteCommandArgument(requestFile.absolutePath)}",
        "-Pjugg.externalBuildOutput=${quoteCommandArgument(outputDir.absolutePath)}",
        "-Pjugg.externalBuildInvocation=$invocationId",
    )

    companion object {
        const val COLLECTOR_TASK_PATH = ":juggCollectExternalBuildInfo"
    }
}

/** Result of one scoped external Gradle invocation. */
internal data class ExternalBuildRunResult(
    val isSuccess: Boolean,
    val updates: List<ExternalBuildInfoUpdate> = emptyList(),
)

/** Runs selected Gradle tasks while preserving the active run configuration arguments. */
internal class ExternalBuildTaskRunner(private val logger: Logger) {

    fun run(
        compileCommand: String,
        requests: List<ExternalBuildInfoRequestItem>,
        compileEnv: List<String>,
        projectDir: File,
        metadataRoot: File,
        initScript: File?,
        task: CompileTask,
    ): ExternalBuildRunResult {
        if (initScript != null && !initScript.isFile) {
            logger.warn("External build info init script not found: $initScript")
            return ExternalBuildRunResult(false)
        }
        val collector = initScript?.let { createCollectorCommand(metadataRoot, requests, it) }
        val command = deriveExternalBuildCommand(
            compileCommand,
            requests.map { it.taskPath },
            collector,
        ) ?: return ExternalBuildRunResult(false)
        logger.debug("External build command: $command")
        val process = startProcess(command, compileEnv, projectDir)
        if (!waitForProcess(process, task)) {
            return ExternalBuildRunResult(false)
        }
        if (collector == null) {
            return ExternalBuildRunResult(true)
        }
        val updates = readUpdates(collector, requests) ?: return ExternalBuildRunResult(false)
        return ExternalBuildRunResult(true, updates)
    }

    private fun createCollectorCommand(
        metadataRoot: File,
        requests: List<ExternalBuildInfoRequestItem>,
        initScript: File,
    ): ExternalBuildCollectorCommand {
        val invocationId = UUID.randomUUID().toString()
        val invocationDir = File(metadataRoot, invocationId)
        val outputDir = File(invocationDir, "output")
        outputDir.mkdirs()
        val requestFile = File(invocationDir, "request.json")
        requestFile.parentFile.mkdirs()
        requestFile.writeText(ProjectInfoSerializer.gson.toJson(
            ExternalBuildInfoRequest(invocationId, requests),
        ))
        return ExternalBuildCollectorCommand(initScript, requestFile, outputDir, invocationId)
    }

    private fun readUpdates(
        collector: ExternalBuildCollectorCommand,
        requests: List<ExternalBuildInfoRequestItem>,
    ): List<ExternalBuildInfoUpdate>? {
        val results = collector.outputDir.listFiles().orEmpty().filter { it.isFile && it.extension == "json" }
            .mapNotNull { file ->
                runCatching {
                    ProjectInfoSerializer.gson.fromJson(file.readText(), ExternalBuildInfoUpdateResult::class.java)
                }.onFailure { logger.debug("Read external build info result $file failed", it) }.getOrNull()
            }
        if (results.isEmpty() || results.any { it.invocationId != collector.invocationId }) {
            logger.warn("External build info collector produced no valid result")
            return null
        }
        val updates = results.flatMap { it.updates }
        val requestedKeys = requests.map { it.key() }.toSet()
        val updateKeys = updates.map { it.key() }.toSet()
        if (requestedKeys != updateKeys || updates.size != updateKeys.size) {
            logger.warn("External build info collector result does not match requested tasks")
            return null
        }
        return updates
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

private fun ExternalBuildInfoRequestItem.key(): String {
    return "${moduleRootDir.absoluteFile.normalize()}:$buildVariant:$taskPath:$type"
}

private fun ExternalBuildInfoUpdate.key(): String {
    return "${moduleRootDir.absoluteFile.normalize()}:$buildVariant:$previousTaskPath:${externalBuildInfo.type}"
}

private fun quoteCommandArgument(value: String): String {
    if (isWindows) {
        return "\"${value.replace("\"", "\\\"")}\""
    }
    return "'${value.replace("'", "'\"'\"'")}'"
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
        taskName.startsWith("copyJniLibsflutterBuild") -> {
            names += "compileFlutterBuild${taskName.removePrefix("copyJniLibsflutterBuild")}"
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
