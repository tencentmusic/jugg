package com.sickworm.intellij.jugg.gradle.compile

import com.intellij.openapi.diagnostic.Logger
import java.io.File

/** Uses the caller's project JuggLogger without letting diagnostic failures change process results. */
internal class NativeProcessDiagnostics(private val logger: Logger, arguments: List<String>) {
    private val startedAt = System.nanoTime()
    private val tool = arguments.firstOrNull().orEmpty()
    private val probeArgument = arguments.getOrNull(1)?.takeIf {
        arguments.size == 2 && ((File(tool).name.equals("rsync.exe", true) && it == "--version") ||
                (File(tool).name.equals("ssh.exe", true) && it == "-V"))
    }
    private var pid: Long? = null

    fun starting() = record {
        logger.debug("Native process starting: ${context()}")
    }

    fun started(process: Process) = record {
        pid = process.pid()
        logger.debug("Native process started: ${context()}")
    }

    fun finished(exitCode: Int, canceled: Boolean) = record {
        val status = if (exitCode == -1073741757) " STATUS_SHARING_VIOLATION" else ""
        val result = "${context()} exitCode=$exitCode hex=0x${exitCode.toUInt().toString(16).uppercase()}" +
                "$status canceled=$canceled elapsedMs=${elapsedMs()}"
        if (probeArgument != null && exitCode != 0 && !canceled) {
            logger.warn("Windows rsync probe failed: $result. " +
                    "Continuing with the actual transfer; the probe will not be retried.")
        } else {
            logger.debug("Native process finished: $result")
        }
    }

    fun failed(exception: Exception) = record {
        // ProcessBuilder exception messages can contain the entire command; retain only safe fields.
        val code = Regex("error=\\d+").find(exception.message.orEmpty())?.value.orEmpty()
        val reason = "${context()} exception=${exception.javaClass.name} $code elapsedMs=${elapsedMs()}"
        if (probeArgument != null && exception !is java.util.concurrent.CancellationException &&
            exception !is InterruptedException) {
            logger.warn("Windows rsync probe failed: $reason. " +
                    "Continuing with the actual transfer; the probe will not be retried.")
        } else {
            logger.debug("Native process failed: $reason")
        }
    }

    private fun context(): String = "tool=$tool probe=${probeArgument ?: "none"}" +
            " parentPid=${ProcessHandle.current().pid()} pid=${pid ?: "not-started"}"

    private fun elapsedMs(): Long = (System.nanoTime() - startedAt) / 1_000_000

    private inline fun record(action: () -> Unit) {
        try {
            action()
        } catch (_: Exception) {
            // A failed logger is itself the unavailable diagnostic channel; never retry it or fail work.
        }
    }
}
