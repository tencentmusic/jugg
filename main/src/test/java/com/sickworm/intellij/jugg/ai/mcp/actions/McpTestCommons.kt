package com.sickworm.intellij.jugg.ai.mcp.actions

import com.intellij.execution.ExecutionResult
import com.intellij.execution.Executor
import com.intellij.execution.configurations.RunProfile
import com.sickworm.intellij.jugg.compiler.CompileUiHandler
import com.sickworm.intellij.jugg.compiler.ForceGradleCompileHelper
import com.sickworm.intellij.jugg.compiler.GradleCompileExecutionResult
import com.sickworm.intellij.jugg.compiler.RemoteSshInfoResult
import com.sickworm.intellij.jugg.ide.bean.JuggGradleCompileOptions
import com.sickworm.intellij.jugg.deploy.instrument.AndroidTestRunSpec
import com.sickworm.intellij.jugg.ide.logic.IJuggConfigurationRunner
import com.sickworm.intellij.jugg.ide.logic.JuggRunInvocationResult

/**
 * Stub implementation of [IJuggConfigurationRunner] for tests that do not exercise runner behavior.
 * All methods throw [UnsupportedOperationException] unless overridden.
 */
open class FakeJuggConfigurationRunner : IJuggConfigurationRunner {
    override val isCompiling: Boolean = false

    override fun runTask(options: JuggGradleCompileOptions, compileUiHandler: CompileUiHandler, executor: Executor?, runProfile: RunProfile?, androidTestRunSpec: AndroidTestRunSpec?): ExecutionResult =
        throw UnsupportedOperationException("not used in this test")

    override fun forceReInstallNextTime() {
        throw UnsupportedOperationException("not used in this test")
    }

    override fun runFirstConfiguration(
        isRpcMode: Boolean,
        isSkipDeploy: Boolean,
        isAlwaysRestartApp: Boolean,
    ): JuggRunInvocationResult =
        throw UnsupportedOperationException("not used in this test")
}

/**
 * Stub implementation of [ForceGradleCompileHelper] for tests that do not exercise Gradle compile behavior.
 * All methods throw [UnsupportedOperationException] unless overridden.
 */
open class FakeForceGradleCompileHelper : ForceGradleCompileHelper() {
    override fun executeGradleCompile(autoConfirm: Boolean, useCleanAndReinstall: Boolean) {
        throw UnsupportedOperationException("not used in this test")
    }

    override fun executeGradleCompileBlocking(
        autoConfirm: Boolean,
        useCleanAndReinstall: Boolean,
    ): GradleCompileExecutionResult =
        throw UnsupportedOperationException("not used in this test")

    override fun resolveExecutionType(): String = "local"

    override fun requestRemoteSshInfo(requestedBy: String, reason: String): RemoteSshInfoResult =
        throw UnsupportedOperationException("not used in this test")
}
