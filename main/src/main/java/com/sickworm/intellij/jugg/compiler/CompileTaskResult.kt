package com.sickworm.intellij.jugg.compiler

data class CompileTaskResult(
    val isSuccess: Boolean,
    val isGradleCompile: Boolean,
    val isCanFallback: Boolean,
    val costTime: Long,
    val failedReason: String? = null,
    val incrementalFailedReason: String? = null,
    val incrementalCompileResult: CompileResult? = null,
) {
    companion object {

        fun incrementalSuccess(compileResult: CompileResult) = CompileTaskResult(
            isSuccess = true,
            isGradleCompile = false,
            isCanFallback = false,
            costTime = 0,
            incrementalCompileResult = compileResult,
        )

        fun incrementalFailed(isCanFallback: Boolean, failedReason: String) = CompileTaskResult(
            isSuccess = false,
            isGradleCompile = false,
            isCanFallback,
            costTime = 0,
            failedReason = failedReason,
            incrementalFailedReason = failedReason,
        )

        fun incrementalCanceled(startTime: Long) = CompileTaskResult(
            isSuccess = false,
            isGradleCompile = false,
            isCanFallback = false,
            costTime = System.currentTimeMillis() - startTime,
            failedReason = "Compile canceled",
            incrementalFailedReason = "Compile canceled",
        )
    }
}