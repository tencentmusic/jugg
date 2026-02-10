package com.sickworm.intellij.jugg.ide.logic

import com.intellij.execution.ExecutionResult
import com.sickworm.intellij.jugg.compiler.CompileUiHandler
import com.sickworm.intellij.jugg.compiler.ui.RunResult
import com.sickworm.intellij.jugg.ide.bean.JuggGradleCompileOptions

/**
 * All about click RUN button.
 */
interface IJuggConfigurationRunner {

    val isCompiling: Boolean

    fun runTask(options: JuggGradleCompileOptions, compileUiHandler: CompileUiHandler): ExecutionResult

    fun forceReInstallNextTime()

    fun runFirstConfiguration(isRpcMode: Boolean, isSkipDeploy: Boolean = false): JuggRunInvocationResult
}

data class JuggRunInvocationResult(
    val isSuccess: Boolean,
    val runResult: RunResult? = null,
    val detail: String = "",
    val errorMessage: String? = null,
)
