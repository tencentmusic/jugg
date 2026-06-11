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
    /** Incremental compiler result when an incremental compile has run. */
    val incrementalCompileResult: CompileResult? = null,
    /** Compiler error lines from a failed Gradle build; empty for incremental or success. */
    val errorLog: List<String> = emptyList(),
    /** Whether source files changed since last compile. false when no file changes detected. */
    val hasFileChanges: Boolean = true,
) {
    companion object {

        fun incrementalSuccess(compileResult: CompileResult) = CompileTaskResult(
            isSuccess = true,
            isGradleCompile = false,
            isCanFallback = false,
            costTime = 0,
            incrementalCompileResult = compileResult,
            hasFileChanges = compileResult.task.isNeedCompile,
        )

        fun incrementalFailed(
            isCanFallback: Boolean,
            failedReason: String,
            hasFileChanges: Boolean = true,
            compileResult: CompileResult? = null,
        ) = CompileTaskResult(
            isSuccess = false,
            isGradleCompile = false,
            isCanFallback,
            costTime = 0,
            failedReason = failedReason,
            incrementalFailedReason = failedReason,
            incrementalCompileResult = compileResult,
            hasFileChanges = hasFileChanges,
        )

        fun incrementalCanceled(startTime: Long, hasFileChanges: Boolean = true) = CompileTaskResult(
            isSuccess = false,
            isGradleCompile = false,
            isCanFallback = false,
            costTime = System.currentTimeMillis() - startTime,
            failedReason = "Compile canceled",
            incrementalFailedReason = "Compile canceled",
            hasFileChanges = hasFileChanges,
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
