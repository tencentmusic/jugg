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
) {
    companion object {
        val FAILED = RunResult(isGradleCompile = false, isCompileSuccess = false, isCancel = false, isDeploySuccess = false)
    }
}
