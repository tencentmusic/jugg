package com.sickworm.intellij.jugg.compiler.ui

/**
 * RunResult carries isGradleCompile, isCompileSuccess, isDeploySuccess, and isNeedResetHasRun.
 */
data class RunResult(
    val isGradleCompile: Boolean,
    val isCompileSuccess: Boolean,
    val isDeploySuccess: Boolean,
    val isNeedResetHasRun: Boolean = false,
) {
    companion object {
        val FAILED = RunResult(isGradleCompile = false, isCompileSuccess = false, isDeploySuccess = false)
    }
}
