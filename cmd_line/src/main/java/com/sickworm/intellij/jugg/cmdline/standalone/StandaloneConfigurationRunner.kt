package com.sickworm.intellij.jugg.cmdline.standalone

import com.intellij.execution.ExecutionResult
import com.intellij.execution.Executor
import com.intellij.execution.configurations.RunProfile
import com.sickworm.intellij.jugg.compiler.BuildTarget
import com.sickworm.intellij.jugg.compiler.CompileTaskResult
import com.sickworm.intellij.jugg.compiler.CompileUiHandler
import com.sickworm.intellij.jugg.compiler.ForceGradleCompileHelper
import com.sickworm.intellij.jugg.compiler.GradleCompileExecutionResult
import com.sickworm.intellij.jugg.compiler.RemoteSshInfoResult
import com.sickworm.intellij.jugg.compiler.ui.RunResult
import com.sickworm.intellij.jugg.deploy.instrument.AndroidTestRunSpec
import com.sickworm.intellij.jugg.deploy.run.DeployOptions
import com.sickworm.intellij.jugg.deploy.run.DeployProgress
import com.sickworm.intellij.jugg.ide.bean.JuggGradleCompileOptions
import com.sickworm.intellij.jugg.ide.logic.IJuggConfigurationRunner
import com.sickworm.intellij.jugg.ide.logic.JuggRunInvocationResult
import com.sickworm.intellij.jugg.project.runtime.CliRunConfigurationStore
import com.sickworm.intellij.jugg.project.runtime.toCompileOptions
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Executes one standalone compile/deploy chain and cancels the previous run when replaced. */
internal class StandaloneConfigurationRunner(
    private val services: StandaloneProjectServices,
    private val activity: StandaloneDaemonActivity,
) : IJuggConfigurationRunner {
    private val runLock = Any()
    private val currentRequest = AtomicReference<AtomicBoolean?>()
    @Volatile private var currentHandler: StandaloneCompileUiHandler? = null
    @Volatile private var compiling = false
    @Volatile private var forceReinstall = false

    override val isCompiling: Boolean get() = compiling
    override val currentIndicatorText: String get() = currentHandler?.indicatorText.orEmpty()

    override fun runTask(
        options: JuggGradleCompileOptions, compileUiHandler: CompileUiHandler, executor: Executor?,
        runProfile: RunProfile?, androidTestRunSpec: AndroidTestRunSpec?,
    ): ExecutionResult = throw UnsupportedOperationException("Standalone execution is available through MCP commands")

    override fun forceReInstallNextTime() {
        forceReinstall = true
    }

    override fun runFirstConfiguration(
        isRpcMode: Boolean, isSkipDeploy: Boolean, isAlwaysRestartApp: Boolean,
    ): JuggRunInvocationResult = runFirstConfigurationWithSpec(
        isRpcMode, isSkipDeploy, isAlwaysRestartApp, null, null, null,
    )

    override fun runFirstConfigurationWithSpec(
        isRpcMode: Boolean,
        isSkipDeploy: Boolean,
        isAlwaysRestartApp: Boolean,
        androidTestRunSpec: AndroidTestRunSpec?,
        buildTargetOverride: BuildTarget?,
        targetDeviceSerial: String?,
    ): JuggRunInvocationResult {
        return execute(
            isRpcMode, isSkipDeploy, isAlwaysRestartApp, androidTestRunSpec, buildTargetOverride,
            targetDeviceSerial, false,
        )
    }

    fun executeGradleBuild(targetDeviceSerial: String?): JuggRunInvocationResult {
        return execute(true, true, false, null, null, targetDeviceSerial, true)
    }

    private fun execute(
        isRpcMode: Boolean,
        isSkipDeploy: Boolean,
        isAlwaysRestartApp: Boolean,
        androidTestRunSpec: AndroidTestRunSpec?,
        buildTargetOverride: BuildTarget?,
        targetDeviceSerial: String?,
        forceGradle: Boolean,
    ): JuggRunInvocationResult {
        val canceled = AtomicBoolean()
        currentRequest.getAndSet(canceled)?.set(true)
        currentHandler?.let {
            it.processHandler.isCanceledByNextTask = true
            it.cancel()
        }
        synchronized(runLock) {
            if (canceled.get()) return canceledResult(isSkipDeploy)
            return executeLocked(RunRequest(
                isRpcMode, isSkipDeploy, isAlwaysRestartApp, androidTestRunSpec, buildTargetOverride,
                targetDeviceSerial, forceGradle,
            ), canceled)
        }
    }

    private fun executeLocked(request: RunRequest, canceled: AtomicBoolean): JuggRunInvocationResult {
        activity.beginJob()
        compiling = true
        return try {
            services.runProjectWriteLocked("Run Jugg") { runChain(request, canceled) }
        } catch (e: Throwable) {
            services.logger.warn("Standalone compile/deploy failed", e)
            JuggRunInvocationResult(false, detail = e.stackTraceToString(), errorMessage = e.message ?: "Standalone run failed")
        } finally {
            currentHandler = null
            currentRequest.compareAndSet(canceled, null)
            compiling = false
            activity.endJob()
        }
    }

    private fun runChain(request: RunRequest, canceled: AtomicBoolean): JuggRunInvocationResult {
        if (canceled.get()) return canceledResult(request.isSkipDeploy)
        val initialization = services.initializeProject()
        if (!initialization.isSuccess) return JuggRunInvocationResult(false, errorMessage = initialization.message)
        if (canceled.get()) return canceledResult(request.isSkipDeploy)
        val configuration = services.configurationStore.loadCurrent()
            ?: return JuggRunInvocationResult(false, errorMessage = "Run jugg init before compiling.")
        val baseOptions = configuration.toCompileOptions(services.pathManager)
        val options = request.buildTargetOverride?.let { baseOptions.copy(buildTarget = it) } ?: baseOptions
        val handler = StandaloneCompileUiHandler(
            options, request.isSkipDeploy, request.isAlwaysRestartApp, request.isRpcMode,
            request.targetDeviceSerial, request.forceGradle, services.logger,
        )
        currentHandler = handler
        if (canceled.get()) handler.cancel()
        services.onBuildStarted()
        var invocation: JuggRunInvocationResult? = null
        try {
            invocation = runCompileAndDeploy(options, handler, request)
            return invocation
        } finally {
            services.onBuildFinished(invocation?.runResult, handler)
        }
    }

    private fun runCompileAndDeploy(
        options: JuggGradleCompileOptions, handler: StandaloneCompileUiHandler, request: RunRequest,
    ): JuggRunInvocationResult {
        services.refreshCustomConfig()
        services.refreshChangedFiles()
        val startTime = System.currentTimeMillis()
        val compileResult = services.compilerHelper.compile(
            options, handler, isAndroidTestRun = request.androidTestRunSpec != null,
        )
        if (!compileResult.isSuccess) return compileFailure(compileResult, handler, request.isSkipDeploy)
        if (compileResult.isGradleCompile) services.initAfterFullBuild(startTime, options)
        if (request.isSkipDeploy) {
            return result(
                RunResult(
                    compileResult.isGradleCompile, true, false, handler.isCanceled, isNeedResetHasRun = true,
                ),
                true,
            )
        }
        return deploy(compileResult, handler, request.androidTestRunSpec)
    }

    private fun canceledResult(isSkipDeploy: Boolean): JuggRunInvocationResult {
        return result(RunResult(false, false, false, true, failedReason = "Canceled by next task."), isSkipDeploy)
    }

    private fun compileFailure(
        compileResult: CompileTaskResult, handler: StandaloneCompileUiHandler, isSkipDeploy: Boolean,
    ): JuggRunInvocationResult {
        val runResult = RunResult(
            compileResult.isGradleCompile, false, false, handler.isCanceled,
            errorLog = compileResult.errorLog, failedReason = compileResult.failedReason,
        )
        return result(runResult, isSkipDeploy)
    }

    private fun deploy(
        compileResult: CompileTaskResult, handler: StandaloneCompileUiHandler, androidTestRunSpec: AndroidTestRunSpec?,
    ): JuggRunInvocationResult {
        val devices = services.deployTargetManager.getTargetDevices(handler.targetDeviceSerial)
        if (devices.isEmpty()) {
            val failedReason = handler.targetDeviceSerial?.let {
                "Device $it is not connected. Stop deploying."
            } ?: "No device found. Stop deploying."
            val runResult = RunResult(
                compileResult.isGradleCompile, true, false, handler.isCanceled,
                failedReason = failedReason,
            )
            return result(runResult, false)
        }
        val shouldInstall = compileResult.isGradleCompile || forceReinstall
        forceReinstall = false
        val deployResults = devices.mapIndexed { index, device ->
            services.deployerHelper.deploy(DeployOptions(
                device = device, isLastDevice = index == devices.lastIndex, isMultipleDevices = devices.size > 1,
                processHandler = handler.processHandler, progress = DeployProgress(handler::updateIndicatorText),
                isInstall = shouldInstall, compileUiHandler = handler, androidTestRunSpec = androidTestRunSpec,
            ))
        }
        val failedReason = deployResults.filterNot { it.isSuccess }
            .joinToString("; ") { it.failedReason ?: "deploy failed" }.ifBlank { null }
        val runResult = RunResult(
            compileResult.isGradleCompile, true, deployResults.all { it.isSuccess }, handler.isCanceled,
            failedReason = failedReason,
        )
        return result(runResult, false)
    }

    private fun result(runResult: RunResult, isSkipDeploy: Boolean): JuggRunInvocationResult {
        val detail = (runResult.errorLog + listOfNotNull(runResult.failedReason)).joinToString("\n")
        return JuggRunInvocationResult(
            isSuccess = runResult.isInvocationSuccess(isSkipDeploy),
            runResult = runResult,
            detail = detail,
            errorMessage = runResult.failedReason,
        )
    }

    private data class RunRequest(
        val isRpcMode: Boolean,
        val isSkipDeploy: Boolean,
        val isAlwaysRestartApp: Boolean,
        val androidTestRunSpec: AndroidTestRunSpec?,
        val buildTargetOverride: BuildTarget?,
        val targetDeviceSerial: String?,
        val forceGradle: Boolean,
    )
}

/** Exposes standalone Gradle builds through the shared MCP job protocol. */
internal class StandaloneForceGradleCompileHelper(
    private val runner: StandaloneConfigurationRunner,
    private val configurationStore: CliRunConfigurationStore,
) : ForceGradleCompileHelper() {
    override fun executeGradleCompile(autoConfirm: Boolean, useCleanAndReinstall: Boolean) {
        executeGradleCompileBlocking(autoConfirm, useCleanAndReinstall)
    }

    override fun executeGradleCompileBlocking(
        autoConfirm: Boolean, useCleanAndReinstall: Boolean,
    ): GradleCompileExecutionResult {
        return executeGradleCompileBlockingForDevice(autoConfirm, useCleanAndReinstall, null)
    }

    override fun executeGradleCompileBlockingForDevice(
        autoConfirm: Boolean, useCleanAndReinstall: Boolean, targetDeviceSerial: String?,
    ): GradleCompileExecutionResult {
        if (useCleanAndReinstall) runner.forceReInstallNextTime()
        val invocation = runner.executeGradleBuild(targetDeviceSerial)
        val runResult = invocation.runResult
        val isCompileSuccess = runResult?.isCompileSuccess == true
        return GradleCompileExecutionResult(
            status = when {
                runResult?.isCancel == true -> "canceled"
                invocation.isSuccess -> "success"
                else -> "failed"
            },
            message = invocation.errorMessage ?: if (invocation.isSuccess) "Gradle build finished successfully." else "Gradle build failed.",
            isCompileSuccess = isCompileSuccess,
            isDeploySuccess = isCompileSuccess,
            detail = invocation.detail,
        )
    }

    override fun resolveExecutionType(): String {
        return if (configurationStore.loadCurrent()?.isRemoteCompile == true) "remote" else "local"
    }

    override fun requestRemoteSshInfo(requestedBy: String, reason: String): RemoteSshInfoResult {
        return RemoteSshInfoResult(false, "Standalone remote SSH configuration is not available.")
    }
}
