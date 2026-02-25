package com.sickworm.intellij.jugg.mcp.actions

import com.sickworm.intellij.jugg.compiler.GradleCompileExecutionResult
import com.sickworm.intellij.jugg.compiler.ui.RunResult
import com.sickworm.intellij.jugg.ide.logic.JuggRunInvocationResult
import com.sickworm.intellij.jugg.mcp.IMcpRuntime
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicReference

object CompileJobManager {
    private const val DEFAULT_SOFT_TIMEOUT_MILLIS = 25_000L
    private const val RUNNING_MESSAGE_TEMPLATE = "任务仍在运行，请通过 get_compile_status 关注进度，Job ID 为 %s"
    const val COMPILE_LATEST_LOG_PATH: String = "build/jugg/log/compile_latest.log"

    @Volatile
    internal var softTimeoutMillisOverrideForTest: Long? = null

    private val worker = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "jugg-mcp-compile-job").apply {
            isDaemon = true
        }
    }

    private val jobs: ConcurrentHashMap<String, CompileJobStatus> = ConcurrentHashMap()

    fun triggerForceGradleCompile(runtime: IMcpRuntime): CompileJobTriggerResult {
        return trigger(
            executionType = runtime.forceGradleCompileHelper.resolveExecutionType(),
            runTask = {
                val result: GradleCompileExecutionResult = runtime.forceGradleCompileHelper.executeGradleCompileBlocking(
                    autoConfirm = true,
                )
                CompileJobExecutionResult(
                    status = result.status,
                    message = result.message,
                )
            },
        )
    }

    fun triggerJuggCompile(runtime: IMcpRuntime, isSkipDeploy: Boolean): CompileJobTriggerResult {
        return trigger(
            executionType = runtime.forceGradleCompileHelper.resolveExecutionType(),
            runTask = {
                val runResponse = runtime.juggConfigurationRunner.runFirstConfiguration(
                    isRpcMode = true,
                    isSkipDeploy = isSkipDeploy,
                )
                if (!runResponse.isSuccess) {
                    return@trigger CompileJobExecutionResult(
                        status = "failed",
                        message = runResponse.errorMessage ?: "run configuration failed",
                        runInvocationResult = runResponse,
                    )
                }
                val runResult = runResponse.runResult
                if (runResult == null) {
                    return@trigger CompileJobExecutionResult(
                        status = "failed",
                        message = "run result is empty.",
                        runInvocationResult = runResponse,
                    )
                }
                val finalStatus = resolveRunResultStatus(runResult, isSkipDeploy)
                val finalMessage = if (finalStatus == "success") {
                    "Jugg compile finished successfully."
                } else {
                    "Jugg compile finished with status=$finalStatus."
                }
                CompileJobExecutionResult(
                    status = finalStatus,
                    message = finalMessage,
                    runInvocationResult = runResponse,
                )
            },
        )
    }

    fun getStatus(jobId: String): CompileJobStatus {
        return jobs[jobId] ?: CompileJobStatus(
            jobId = jobId,
            status = "unknown",
            executionType = "local",
            message = "Compile job not found.",
            finishedAt = null,
        )
    }

    internal fun resetForTest() {
        jobs.clear()
        softTimeoutMillisOverrideForTest = null
    }

    private fun trigger(
        executionType: String,
        runTask: () -> CompileJobExecutionResult,
    ): CompileJobTriggerResult {
        val jobId = UUID.randomUUID().toString()
        val initial = CompileJobStatus(
            jobId = jobId,
            status = "running",
            executionType = executionType,
            message = "Compile job accepted.",
            finishedAt = null,
        )
        jobs[jobId] = initial

        val finalResultRef = AtomicReference<CompileJobExecutionResult?>()
        val future: Future<*> = worker.submit {
            val rawResult = try {
                runTask()
            } catch (t: Throwable) {
                CompileJobExecutionResult(
                    status = "failed",
                    message = t.message ?: "unknown error",
                )
            }
            val normalizedStatus = normalizeStatus(rawResult.status)
            val normalizedResult = rawResult.copy(status = normalizedStatus)
            finalResultRef.set(normalizedResult)
            jobs[jobId] = initial.copy(
                status = normalizedStatus,
                message = normalizedResult.message,
                finishedAt = if (normalizedStatus == "running") null else Instant.now().toString(),
            )
        }

        return try {
            future.get(resolveSoftTimeoutMillis(), TimeUnit.MILLISECONDS)
            val finalState = jobs[jobId] ?: initial
            CompileJobTriggerResult(
                accepted = true,
                jobId = jobId,
                executionType = finalState.executionType,
                logPath = COMPILE_LATEST_LOG_PATH,
                isFinal = true,
                status = finalState.status,
                message = finalState.message,
                finalResult = finalResultRef.get(),
            )
        } catch (_: TimeoutException) {
            CompileJobTriggerResult(
                accepted = true,
                jobId = jobId,
                executionType = initial.executionType,
                logPath = COMPILE_LATEST_LOG_PATH,
                isFinal = false,
                status = "running",
                message = RUNNING_MESSAGE_TEMPLATE.format(jobId),
                finalResult = null,
            )
        } catch (t: Throwable) {
            val message = t.cause?.message ?: t.message ?: "unknown error"
            val failedResult = finalResultRef.get() ?: CompileJobExecutionResult(
                status = "failed",
                message = message,
            )
            val normalizedStatus = normalizeStatus(failedResult.status)
            val normalizedResult = failedResult.copy(status = normalizedStatus)
            jobs[jobId] = initial.copy(
                status = normalizedStatus,
                message = normalizedResult.message,
                finishedAt = Instant.now().toString(),
            )
            CompileJobTriggerResult(
                accepted = true,
                jobId = jobId,
                executionType = initial.executionType,
                logPath = COMPILE_LATEST_LOG_PATH,
                isFinal = true,
                status = normalizedStatus,
                message = normalizedResult.message,
                finalResult = normalizedResult,
            )
        }
    }

    private fun resolveRunResultStatus(runResult: RunResult, isSkipDeploy: Boolean): String {
        if (runResult.isCancel) {
            return "canceled"
        }
        val deployOk = if (isSkipDeploy) true else runResult.isDeploySuccess
        return if (runResult.isCompileSuccess && deployOk) "success" else "failed"
    }

    private fun resolveSoftTimeoutMillis(): Long {
        val override = softTimeoutMillisOverrideForTest
        if (override == null || override <= 0L) {
            return DEFAULT_SOFT_TIMEOUT_MILLIS
        }
        return override
    }

    private fun normalizeStatus(raw: String): String {
        return when (raw.lowercase()) {
            "running", "success", "failed", "canceled", "unknown" -> raw.lowercase()
            else -> "failed"
        }
    }
}

data class CompileJobTriggerResult(
    val accepted: Boolean,
    val jobId: String,
    val executionType: String,
    val logPath: String,
    val isFinal: Boolean,
    val status: String,
    val message: String,
    val finalResult: CompileJobExecutionResult? = null,
)

data class CompileJobExecutionResult(
    val status: String,
    val message: String,
    val runInvocationResult: JuggRunInvocationResult? = null,
)

data class CompileJobStatus(
    val jobId: String,
    val status: String,
    val executionType: String,
    val message: String,
    val finishedAt: String?,
)
