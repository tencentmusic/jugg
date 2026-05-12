package com.sickworm.intellij.jugg.compiler.ui

/**
 * RunResult carries compile/deploy outcome flags and one explicit cancel flag.
 */
data class RunResult(
    val isGradleCompile: Boolean,
    val isCompileSuccess: Boolean,
    val isDeploySuccess: Boolean,
    val isCancel: Boolean,
    val isNeedResetHasRun: Boolean = false,
    /** Compiler error lines from a failed Gradle build; empty on success or incremental compile. */
    val errorLog: List<String> = emptyList(),
    /** Human-readable reason when deployment failed; null when N/A or success. */
    val failedReason: String? = null,
) {
    /**
     * Returns true if the overall run invocation succeeded, considering whether deployment was skipped.
     *
     * For Gradle compile: only isCompileSuccess matters.
     * For incremental compile with skip deploy: only isCompileSuccess matters.
     * For incremental compile with deploy: both isCompileSuccess and isDeploySuccess must be true.
     */
    fun isInvocationSuccess(isSkipDeploy: Boolean): Boolean {
        return when {
            isGradleCompile -> isCompileSuccess
            isSkipDeploy -> isCompileSuccess
            else -> isDeploySuccess
        }
    }

    companion object {
        val FAILED = RunResult(isGradleCompile = false, isCompileSuccess = false, isCancel = false, isDeploySuccess = false)
    }
}
