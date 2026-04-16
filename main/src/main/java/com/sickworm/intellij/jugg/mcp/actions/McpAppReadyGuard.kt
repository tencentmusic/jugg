package com.sickworm.intellij.jugg.mcp.actions

import com.sickworm.intellij.jugg.mcp.IMcpRuntime
import com.sickworm.intellij.jugg.mcp.McpErrorCode
import com.sickworm.intellij.jugg.mcp.McpToolResult
import com.sickworm.intellij.jugg.mcp.McpToolStatus

/**
 * McpAppReadyGuard provides a unified "wait until app is ready" gate for MCP tools.
 * Runtime readiness is checked by IMcpRuntime.isAppReadyDeploy(), which uses
 * deployStateManager.updateDeployState().isReadyDeploy in IDE runtime.
 */
object McpAppReadyGuard {
    private const val PRE_CHECK_TIMEOUT_MS = 10_000L
    private const val PRE_CHECK_POLL_INTERVAL_MS = 100L
    private const val PRE_WAIT_FAILURE_RETRY_COUNT = 3
    private const val PRE_WAIT_FAILURE_RETRY_INTERVAL_MS = 2_000L
    private const val POST_CHECK_TIMEOUT_MS = 10_000L
    private const val POST_CHECK_POLL_INTERVAL_MS = 200L

    @Volatile
    internal var sleepForTest: ((Long) -> Unit)? = null

    @Volatile
    internal var preTimeoutOverrideForTest: Long? = null

    @Volatile
    internal var prePollIntervalOverrideForTest: Long? = null

    @Volatile
    internal var preFailureRetryIntervalOverrideForTest: Long? = null

    @Volatile
    internal var postTimeoutOverrideForTest: Long? = null

    @Volatile
    internal var postPollIntervalOverrideForTest: Long? = null

    /**
     * Wait before runtime-observe tools.
     * Poll interval is 100ms and max wait is 10s.
     */
    fun waitBeforeRuntimeObserve(runtime: IMcpRuntime, toolName: String): PreWaitResult {
        val timeoutMs = resolvePreTimeoutMs()
        val pollMs = resolvePrePollIntervalMs()
        val deadline = System.currentTimeMillis() + timeoutMs
        var checks = 0
        while (System.currentTimeMillis() <= deadline) {
            checks++
            if (runtime.isAppReadyDeploy()) {
                return PreWaitResult(
                    isReady = true,
                    checks = checks,
                    hasWaited = checks > 1,
                )
            }
            sleep(pollMs)
        }
        return PreWaitResult(
            isReady = false,
            checks = checks,
            hasWaited = checks > 1,
            errorResult = appNotReadyResult(
                toolName = toolName,
                stage = "before executing $toolName",
                attempts = checks,
            ),
        )
    }

    /**
     * Retry one tool call when:
     * 1) pre-wait really waited at least once;
     * 2) call failed with internal/transient error.
     */
    fun executeWithRetryIfPreWaited(
        preWaitResult: PreWaitResult,
        executeOnce: () -> McpToolResult,
    ): McpToolResult {
        var result = executeOnce()
        if (!preWaitResult.hasWaited || !shouldRetryAfterPreWait(result)) {
            return result
        }
        val retryInterval = resolvePreFailureRetryIntervalMs()
        repeat(PRE_WAIT_FAILURE_RETRY_COUNT) {
            sleep(retryInterval)
            result = executeOnce()
            if (!shouldRetryAfterPreWait(result)) {
                return result
            }
        }
        return result
    }

    /**
     * Wait after mutating tools (restart/build/deploy).
     */
    fun waitAfterMutating(runtime: IMcpRuntime, toolName: String): WaitResult {
        if (runtime.isAppReadyDeploy()) {
            return WaitResult(isReady = true, checks = 1)
        }
        val timeoutMs = resolvePostTimeoutMs()
        val pollMs = resolvePostPollIntervalMs()
        val deadline = System.currentTimeMillis() + timeoutMs
        var checks = 1
        while (System.currentTimeMillis() < deadline) {
            sleep(pollMs)
            checks++
            if (runtime.isAppReadyDeploy()) {
                return WaitResult(isReady = true, checks = checks)
            }
        }
        return WaitResult(
            isReady = false,
            checks = checks,
            reason = "$toolName finished but app is still not ready after ${timeoutMs}ms."
        )
    }

    internal fun resetForTest() {
        sleepForTest = null
        preTimeoutOverrideForTest = null
        prePollIntervalOverrideForTest = null
        preFailureRetryIntervalOverrideForTest = null
        postTimeoutOverrideForTest = null
        postPollIntervalOverrideForTest = null
    }

    private fun appNotReadyResult(toolName: String, stage: String, attempts: Int): McpToolResult {
        return McpToolResult(
            status = McpToolStatus.ERROR,
            message = "$toolName failed. Reason: app is not ready $stage after $attempts checks. " +
                "Next action: run restart and retry $toolName.",
            data = mapOf("readyChecks" to attempts),
            artifacts = emptyList(),
            errorCode = McpErrorCode.INTERNAL_ERROR,
        )
    }

    private fun sleep(ms: Long) {
        val action = sleepForTest
        if (action != null) {
            action(ms)
            return
        }
        Thread.sleep(ms)
    }

    private fun resolvePreTimeoutMs(): Long {
        return preTimeoutOverrideForTest?.takeIf { it > 0 } ?: PRE_CHECK_TIMEOUT_MS
    }

    private fun resolvePrePollIntervalMs(): Long {
        return prePollIntervalOverrideForTest?.takeIf { it > 0 } ?: PRE_CHECK_POLL_INTERVAL_MS
    }

    private fun resolvePreFailureRetryIntervalMs(): Long {
        return preFailureRetryIntervalOverrideForTest?.takeIf { it >= 0 } ?: PRE_WAIT_FAILURE_RETRY_INTERVAL_MS
    }

    private fun resolvePostTimeoutMs(): Long {
        return postTimeoutOverrideForTest?.takeIf { it > 0 } ?: POST_CHECK_TIMEOUT_MS
    }

    private fun resolvePostPollIntervalMs(): Long {
        return postPollIntervalOverrideForTest?.takeIf { it > 0 } ?: POST_CHECK_POLL_INTERVAL_MS
    }

    private fun shouldRetryAfterPreWait(result: McpToolResult): Boolean {
        if (result.status != McpToolStatus.ERROR) {
            return false
        }
        return result.errorCode.isNullOrBlank() || result.errorCode == McpErrorCode.INTERNAL_ERROR
    }
}

/**
 * WaitResult describes app-ready wait outcome for post-mutation tools.
 */
data class WaitResult(
    val isReady: Boolean,
    val checks: Int,
    val reason: String? = null,
)

/**
 * PreWaitResult describes pre-check readiness and whether waiting actually happened.
 */
data class PreWaitResult(
    val isReady: Boolean,
    val checks: Int,
    val hasWaited: Boolean,
    val errorResult: McpToolResult? = null,
)
