package com.sickworm.intellij.jugg.compiler

import java.io.File

/**
 * CompileTaskResult carries isSuccess, isGradleCompile, isCanFallback, and costTime.
 */
data class CompileTaskResult(
    val isSuccess: Boolean,
    val isGradleCompile: Boolean,
    val isCanFallback: Boolean,
    val costTime: Long,
    val failedReason: String? = null,
    val incrementalFailedReason: String? = null,
    /** Not null if isGradleCompile=false and isSuccess=true */
    val incrementalCompileResult: CompileResult? = null,
    /** Compiler error lines from a failed Gradle build; empty for incremental or success. */
    val errorLog: List<String> = emptyList(),
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

/**
 * ExportIncrementalApkResult carries isSuccess, apkFiles, and failedReason.
 */
data class ExportIncrementalApkResult(
    val isSuccess: Boolean,
    val apkFiles: List<File>,
    val failedReason: String? = null,
)
