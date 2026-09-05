package com.sickworm.intellij.jugg.compiler.external

import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.jugg.compiler.CompileTask
import com.sickworm.intellij.jugg.compiler.isWindows
import java.io.File
import java.util.ArrayDeque
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** Runs selected Gradle tasks while preserving the active run configuration arguments. */
internal class ExternalBuildTaskRunner(private val logger: Logger) {

    fun run(
        compileCommand: String,
        taskPaths: List<String>,
        compileEnv: List<String>,
        projectDir: File,
        task: CompileTask,
    ): Boolean {
        val command = deriveCommand(compileCommand, taskPaths) ?: return false
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
        private val taskNamePattern = Regex(
            "^(?::[A-Za-z0-9_.-]+)*:?(clean|assemble|bundle|install|package|process|compile|merge|externalNativeBuild)[A-Za-z0-9_.-]*$"
        )

        internal fun deriveCommand(compileCommand: String, taskPaths: List<String>): String? {
            if (taskPaths.isEmpty() || compileCommand.containsControlOperator()) {
                return null
            }
            val tokens = Regex("\\\"[^\\\"]*\\\"|'[^']*'|\\S+")
                .findAll(compileCommand.trim())
                .map { it.value }
                .toList()
            val executableIndex = tokens.indexOfFirst { token ->
                token.trim('"', '\'').substringAfterLast('/').substringAfterLast('\\') in gradleExecutables
            }
            if (executableIndex < 0) {
                return null
            }
            val suffix = tokens.drop(executableIndex + 1).filterNot { token ->
                taskNamePattern.matches(token.trim('"', '\''))
            }
            return (tokens.take(executableIndex + 1) + taskPaths.distinct() + suffix).joinToString(" ")
        }

        private fun String.containsControlOperator(): Boolean {
            return contains('\n') || contains(';') || contains("&&") || contains("||") || contains("$(") ||
                    contains('`') || contains('|') || contains('>') || contains('<')
        }

        private val gradleExecutables = setOf("gradle", "gradlew", "gradle.bat", "gradlew.bat")
    }
}
