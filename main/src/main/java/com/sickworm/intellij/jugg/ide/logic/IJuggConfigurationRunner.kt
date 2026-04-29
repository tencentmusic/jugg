package com.sickworm.intellij.jugg.ide.logic

import com.intellij.execution.ExecutionResult
import com.sickworm.intellij.jugg.compiler.CompileUiHandler
import com.sickworm.intellij.jugg.compiler.ui.RunResult
import com.sickworm.intellij.jugg.deploy.instrument.AndroidTestRunSpec
import com.sickworm.intellij.jugg.ide.bean.JuggGradleCompileOptions

/**
 * IJuggConfigurationRunner defines the run-trigger workflow for compile/deploy execution from IDE actions.
 */
interface IJuggConfigurationRunner {

    val isCompiling: Boolean

    fun runTask(
        options: JuggGradleCompileOptions,
        compileUiHandler: CompileUiHandler,
        androidTestRunSpec: AndroidTestRunSpec? = null,
    ): ExecutionResult

    fun forceReInstallNextTime()

    fun runFirstConfiguration(isRpcMode: Boolean, isSkipDeploy: Boolean = false, isAlwaysRestartApp: Boolean = false): JuggRunInvocationResult
}

/**
 * JuggRunInvocationResult models the outcome of [IJuggConfigurationRunner.runFirstConfiguration].
 * Data Contract: [runResult] and [errorMessage] default to null, [detail] defaults to an empty string, and [isSuccess] is the only required field.
 */
data class JuggRunInvocationResult(
    val isSuccess: Boolean,
    val runResult: RunResult? = null,
    val detail: String = "",
    val errorMessage: String? = null,
)
