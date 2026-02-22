package com.sickworm.intellij.jugg.mcp.actions

import com.sickworm.intellij.jugg.compiler.GradleCompileExecutionResult
import com.sickworm.intellij.jugg.mcp.IMcpRuntime
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

object GradleCompileJobManager {
    private const val SOFT_TIMEOUT_SECONDS = 25L
    const val COMPILE_LATEST_LOG_PATH: String = "build/jugg/log/compile_latest.log"

    private val worker = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "jugg-mcp-force-gradle-compile").apply {
            isDaemon = true
        }
    }

    private val jobs: ConcurrentHashMap<String, CompileJobState> = ConcurrentHashMap()

    fun trigger(runtime: IMcpRuntime): ForceCompileTriggerResult {
        val jobId = UUID.randomUUID().toString()
        val executionType = runtime.forceGradleCompileHelper.resolveExecutionType()
        val initial = CompileJobState(
            jobId = jobId,
            status = "running",
            executionType = executionType,
            message = "Compile job accepted.",
            finishedAt = null,
        )
        jobs[jobId] = initial

        val future: Future<*> = worker.submit {
            val finalState = try {
                val result: GradleCompileExecutionResult = runtime.forceGradleCompileHelper.executeGradleCompileBlocking(
                    autoConfirm = true,
                )
                val finalStatus = normalizeStatus(result.status)
                initial.copy(
                    status = finalStatus,
                    message = result.message,
                    finishedAt = if (finalStatus == "running") null else Instant.now().toString(),
                )
            } catch (t: Throwable) {
                initial.copy(
                    status = "failed",
                    message = t.message ?: "unknown error",
                    finishedAt = Instant.now().toString(),
                )
            }
            jobs[jobId] = finalState
        }

        return try {
            future.get(SOFT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            val final = jobs[jobId] ?: initial
            ForceCompileTriggerResult(
                accepted = true,
                jobId = jobId,
                executionType = final.executionType,
                logPath = COMPILE_LATEST_LOG_PATH,
                isFinal = true,
                status = final.status,
                message = final.message,
            )
        } catch (_: TimeoutException) {
            ForceCompileTriggerResult(
                accepted = true,
                jobId = jobId,
                executionType = initial.executionType,
                logPath = COMPILE_LATEST_LOG_PATH,
                isFinal = false,
                status = "running",
                message = "任务仍在运行，请通过 get_compile_status 关注进度，Job ID 为 $jobId",
            )
        }
    }

    fun getStatus(jobId: String): CompileJobState {
        return jobs[jobId] ?: CompileJobState(
            jobId = jobId,
            status = "unknown",
            executionType = "local",
            message = "Compile job not found.",
            finishedAt = null,
        )
    }

    private fun normalizeStatus(raw: String): String {
        return when (raw.lowercase()) {
            "running", "success", "failed", "canceled", "unknown" -> raw.lowercase()
            else -> "failed"
        }
    }
}

data class ForceCompileTriggerResult(
    val accepted: Boolean,
    val jobId: String,
    val executionType: String,
    val logPath: String,
    val isFinal: Boolean,
    val status: String,
    val message: String,
)

data class CompileJobState(
    val jobId: String,
    val status: String,
    val executionType: String,
    val message: String,
    val finishedAt: String?,
)
