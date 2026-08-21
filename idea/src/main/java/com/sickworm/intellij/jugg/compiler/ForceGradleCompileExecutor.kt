package com.sickworm.intellij.jugg.compiler

import com.intellij.execution.ProgramRunnerUtil
import com.intellij.execution.RunManager
import com.intellij.execution.configurations.RunConfigurationBase
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.sickworm.intellij.jugg.compiler.ui.RunResult
import com.sickworm.intellij.jugg.deploy.DeployFileManager
import com.sickworm.intellij.jugg.deploy.run.ExportIncrementalApkHelper
import com.sickworm.intellij.jugg.ide.JuggConfigurationType
import com.sickworm.intellij.jugg.ide.JuggRunConfiguration
import com.sickworm.intellij.jugg.ide.JuggRunConfigurationOptions
import com.sickworm.intellij.jugg.ide.bean.ConfirmResult
import com.sickworm.intellij.jugg.ide.logic.IJuggConfigurationRunner
import com.sickworm.intellij.jugg.ide.ui.CommonConfirmDialog
import com.sickworm.intellij.jugg.compiler.context.CompileContextManager
import com.sickworm.intellij.jugg.project.runtime.TaskRunnerManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class IdeaForceGradleCompileHelper(
    private val project: Project,
    private val juggConfigurationRunner: IJuggConfigurationRunner,
    private val deployFileManager: DeployFileManager,
    private val taskRunnerManager: TaskRunnerManager,
    private val compileContextManager: CompileContextManager,
    private val logger: Logger,
) : ForceGradleCompileHelper() {

    override fun executeGradleCompile(
        autoConfirm: Boolean,
        useCleanAndReinstall: Boolean,
    ) {
        isGradleCacheRefreshNextTime = false
        val currentConfiguration = RunManager.getInstance(project).selectedConfiguration
        val isJuggConfiguration = currentConfiguration?.configuration is JuggRunConfiguration
        logger.debug(
            "executeGradleCompile selected: $currentConfiguration, " +
                "isJuggConfiguration:$isJuggConfiguration, autoConfirm:$autoConfirm, useCleanAndReinstall:$useCleanAndReinstall"
        )

        if (autoConfirm) {
            if (useCleanAndReinstall) {
                isCleanAndReinstallNextTime = true
                if (isJuggConfiguration) {
                    tryRunFirstConfiguration(juggConfigurationRunner, logger)
                } else if (currentConfiguration != null) {
                    ProgramRunnerUtil.executeConfiguration(currentConfiguration, DefaultRunExecutor.getRunExecutorInstance())
                } else {
                    tryRunFirstConfiguration(juggConfigurationRunner, logger)
                }
            } else {
                isForceGradleCompileNextTime = true
                if (isJuggConfiguration && currentConfiguration != null) {
                    ProgramRunnerUtil.executeConfiguration(currentConfiguration, DefaultRunExecutor.getRunExecutorInstance())
                } else {
                    tryRunFirstConfiguration(juggConfigurationRunner, logger)
                }
            }
            return
        }

        var content = "Jugg is going to compile the project using gradle. Continue?"
        if (!isJuggConfiguration) {
            val currentRunConfigurationList = RunManager.getInstance(project)
                .getConfigurationSettingsList(JuggConfigurationType::class.java)
            @Suppress("UNCHECKED_CAST")
            val runConfiguration = (currentRunConfigurationList.firstOrNull()?.configuration
                    as? RunConfigurationBase<JuggRunConfigurationOptions>)
            if (runConfiguration == null) {
                CommonConfirmDialog.showAndGetResult(
                    "Run failed", "No Jugg configuration found",
                    okButtonText = "Close"
                )
                return
            }
            content = "<html>Jugg is going to compile the project using gradle. Continue? <br>(will run ${runConfiguration.name})</html>"
        }
        var isGradleCacheRefreshRequested = false
        val confirmResult = CommonConfirmDialog.showAndGetOrCancel(
            "Confirm fallback",
            content,
            okButtonText = "Yes",
            negativeButtonText = "No",
            leftButtonText = "Clean And Reinstall",
            linkActions = listOf(
                CommonConfirmDialog.CustomLinkAction("Export incremental APK") {
                    ExportIncrementalApkHelper(project, taskRunnerManager, deployFileManager, logger)
                        .exportIncrementalApk(it, compileContextManager.compileContext)
                }
            ),
            checkBoxText = "clean gradle cache on fallback",
            checkBoxSelectionAction = { isGradleCacheRefreshRequested = it },
        )
        when (confirmResult) {
            ConfirmResult.POSITIVE -> {
                ForceGradleCompileHelper.isForceGradleCompileNextTime = true
                ForceGradleCompileHelper.isGradleCacheRefreshNextTime = isGradleCacheRefreshRequested
                if (isJuggConfiguration) {
                    ProgramRunnerUtil.executeConfiguration(currentConfiguration!!, DefaultRunExecutor.getRunExecutorInstance())
                } else {
                    tryRunFirstConfiguration(juggConfigurationRunner, logger)
                }
            }
            ConfirmResult.LEFT -> {
                ForceGradleCompileHelper.isCleanAndReinstallNextTime = true
                if (isJuggConfiguration) {
                    ProgramRunnerUtil.executeConfiguration(currentConfiguration!!, DefaultRunExecutor.getRunExecutorInstance())
                } else {
                    tryRunFirstConfiguration(juggConfigurationRunner, logger)
                }
            }
            else -> {
                // no-op
            }
        }
    }

    override fun executeGradleCompileBlocking(
        autoConfirm: Boolean,
        useCleanAndReinstall: Boolean,
    ): GradleCompileExecutionResult {
        return executeGradleCompileBlockingForDevice(autoConfirm, useCleanAndReinstall, null)
    }

    override fun executeGradleCompileBlockingForDevice(
        autoConfirm: Boolean,
        useCleanAndReinstall: Boolean,
        targetDeviceSerial: String?,
    ): GradleCompileExecutionResult {
        ForceGradleCompileHelper.isGradleCacheRefreshNextTime = false
        if (!autoConfirm) {
            return GradleCompileExecutionResult(
                status = "failed",
                message = "executeGradleCompileBlocking requires autoConfirm=true.",
                isCompileSuccess = false,
                isDeploySuccess = false,
            )
        }
        if (useCleanAndReinstall) {
            ForceGradleCompileHelper.isCleanAndReinstallNextTime = true
        } else {
            ForceGradleCompileHelper.isForceGradleCompileNextTime = true
        }
        val result = if (targetDeviceSerial == null) {
            juggConfigurationRunner.runFirstConfiguration(isRpcMode = true)
        } else {
            juggConfigurationRunner.runFirstConfigurationWithSpec(
                isRpcMode = true,
                targetDeviceSerial = targetDeviceSerial,
            )
        }
        if (!result.isSuccess) {
            return GradleCompileExecutionResult(
                status = "failed",
                message = result.errorMessage ?: "run configuration failed",
                isCompileSuccess = false,
                isDeploySuccess = false,
                detail = result.detail,
            )
        }
        val runResult = result.runResult
        if (runResult == null) {
            return GradleCompileExecutionResult(
                status = "failed",
                message = "run result is empty.",
                isCompileSuccess = false,
                isDeploySuccess = false,
                detail = result.detail,
            )
        }
        return toExecutionResult(runResult, result.detail)
    }

    override fun resolveExecutionType(): String {
        val options = resolveJuggRunConfigurationOptions() ?: return "local"
        return if (options.isRemoteCompile) "remote" else "local"
    }

    override fun requestRemoteSshInfo(
        requestedBy: String,
        reason: String,
    ): RemoteSshInfoResult {
        val options = resolveJuggRunConfigurationOptions()
        if (options == null) {
            return RemoteSshInfoResult(
                approved = false,
                message = "request_remote_ssh_info failed. Reason: Jugg run configuration not found.",
            )
        }
        if (!options.isRemoteCompile) {
            return RemoteSshInfoResult(
                approved = false,
                message = "request_remote_ssh_info failed. Reason: current run configuration is not remote compile.",
            )
        }
        val confirmContent = buildString {
            append("<html>Allow exposing remote SSH login info?<br/>")
            append("Requester: ").append(requestedBy).append("<br/>")
            append("Reason: ").append(reason).append("<br/>")
            append("Target: ").append(options.remoteSshUser).append("@").append(options.remoteSshIp)
                .append(":").append(options.remoteSshPort).append("</html>")
        }
        val confirmed = CommonConfirmDialog.showAndGetResult(
            title = "Confirm Remote SSH Info Access",
            content = confirmContent,
            okButtonText = "Allow",
            cancelButtonText = "Deny",
        )
        if (!confirmed) {
            return RemoteSshInfoResult(
                approved = false,
                message = "request_remote_ssh_info failed. Reason: IDE confirmation denied by user.",
            )
        }
        return RemoteSshInfoResult(
            approved = true,
            message = "request_remote_ssh_info executed successfully.",
            user = options.remoteSshUser,
            ip = options.remoteSshIp,
            port = options.remoteSshPort,
            password = options.remoteSshPassword,
            sshLoginCommand = "ssh ${options.remoteSshUser}@${options.remoteSshIp} -p ${options.remoteSshPort}",
        )
    }

    private fun tryRunFirstConfiguration(juggConfigurationRunner: IJuggConfigurationRunner, logger: Logger) {
        CoroutineScope(Dispatchers.IO).launch {
            val result = juggConfigurationRunner.runFirstConfiguration(isRpcMode = false)
            if (!result.isSuccess) {
                val errorMessage = result.errorMessage ?: "Unknown error"
                logger.debug("tryRunFirstConfiguration failed: $errorMessage")
            }
        }
    }

    private fun resolveJuggRunConfigurationOptions(): JuggRunConfigurationOptions? {
        val selected = RunManager.getInstance(project).selectedConfiguration?.configuration
        if (selected is JuggRunConfiguration) {
            return selected.state
        }
        val currentRunConfigurationList = RunManager.getInstance(project)
            .getConfigurationSettingsList(JuggConfigurationType::class.java)
        @Suppress("UNCHECKED_CAST")
        val runConfiguration = (currentRunConfigurationList.firstOrNull()?.configuration
                as? RunConfigurationBase<JuggRunConfigurationOptions>)
        return runConfiguration?.state
    }

    private fun toExecutionResult(runResult: RunResult, detail: String): GradleCompileExecutionResult {
        val isSuccess = runResult.isCompileSuccess && runResult.isDeploySuccess
        return if (isSuccess) {
            GradleCompileExecutionResult(
                status = "success",
                message = "Gradle compile finished successfully.",
                isCompileSuccess = runResult.isCompileSuccess,
                isDeploySuccess = runResult.isDeploySuccess,
                detail = detail,
            )
        } else {
            val status = if (runResult.isCancel) "canceled" else "failed"
            GradleCompileExecutionResult(
                status = status,
                message = resolveFailureMessage(status, detail),
                isCompileSuccess = runResult.isCompileSuccess,
                isDeploySuccess = runResult.isDeploySuccess,
                detail = detail,
            )
        }
    }

    private fun resolveFailureMessage(status: String, detail: String): String {
        return detail.lines()
            .map { it.trim() }
            .firstOrNull { it.startsWith("No device found.") }
            ?: "Gradle compile finished with status=$status."
    }

}
