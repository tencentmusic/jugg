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
import com.sickworm.intellij.jugg.project.CompileContextManager
import com.sickworm.intellij.jugg.project.JuggPathManager
import com.sickworm.intellij.jugg.project.TaskRunnerManager
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class IdeaForceGradleCompileHelper(
    private val project: Project,
    private val juggConfigurationRunner: IJuggConfigurationRunner,
    private val deployFileManager: DeployFileManager,
    private val taskRunnerManager: TaskRunnerManager,
    private val compileContextManager: CompileContextManager,
    private val pathManager: JuggPathManager,
    private val logger: Logger,
) : ForceGradleCompileHelper() {

    override fun executeGradleCompile(
        autoConfirm: Boolean,
        useCleanAndReinstall: Boolean,
    ) {
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
            )
        )
        when (confirmResult) {
            ConfirmResult.POSITIVE -> {
                ForceGradleCompileHelper.isForceGradleCompileNextTime = true
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
        if (!autoConfirm) {
            return GradleCompileExecutionResult(
                status = "failed",
                message = "executeGradleCompileBlocking requires autoConfirm=true.",
            )
        }
        if (useCleanAndReinstall) {
            ForceGradleCompileHelper.isCleanAndReinstallNextTime = true
        } else {
            ForceGradleCompileHelper.isForceGradleCompileNextTime = true
        }
        val result = juggConfigurationRunner.runFirstConfiguration(isRpcMode = true)
        if (!result.isSuccess) {
            return GradleCompileExecutionResult(
                status = "failed",
                message = result.errorMessage ?: "run configuration failed",
            )
        }
        val runResult = result.runResult
        if (runResult == null) {
            return GradleCompileExecutionResult(
                status = "failed",
                message = "run result is empty.",
            )
        }
        return toExecutionResult(runResult)
    }

    override fun resolveExecutionType(): String {
        val options = resolveJuggRunConfigurationOptions() ?: return "local"
        return if (options.isRemoteCompile) "remote" else "local"
    }

    override fun requestRemoteSshInfo(
        requestedBy: String,
        reason: String,
    ): RemoteSshInfoResult {
        val auditId = UUID.randomUUID().toString()
        val options = resolveJuggRunConfigurationOptions()
        if (options == null) {
            writeSshAudit(
                auditId = auditId,
                requestedBy = requestedBy,
                reason = reason,
                confirmed = false,
                outcome = "no_run_configuration",
            )
            return RemoteSshInfoResult(
                approved = false,
                message = "request_remote_ssh_info failed. Reason: Jugg run configuration not found.",
                auditId = auditId,
            )
        }
        if (!options.isRemoteCompile) {
            writeSshAudit(
                auditId = auditId,
                requestedBy = requestedBy,
                reason = reason,
                confirmed = false,
                outcome = "remote_compile_disabled",
            )
            return RemoteSshInfoResult(
                approved = false,
                message = "request_remote_ssh_info failed. Reason: current run configuration is not remote compile.",
                auditId = auditId,
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
        val outcome = if (confirmed) "approved" else "denied_by_user"
        writeSshAudit(
            auditId = auditId,
            requestedBy = requestedBy,
            reason = reason,
            confirmed = confirmed,
            outcome = outcome,
        )
        if (!confirmed) {
            return RemoteSshInfoResult(
                approved = false,
                message = "request_remote_ssh_info failed. Reason: IDE confirmation denied by user.",
                auditId = auditId,
            )
        }
        return RemoteSshInfoResult(
            approved = true,
            message = "request_remote_ssh_info executed successfully.",
            auditId = auditId,
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
                CommonConfirmDialog.showAndGetResult(
                    "Run failed", "Error: $errorMessage",
                    okButtonText = "Close"
                )
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

    private fun toExecutionResult(runResult: RunResult): GradleCompileExecutionResult {
        val isSuccess = runResult.isCompileSuccess && runResult.isDeploySuccess
        return if (isSuccess) {
            GradleCompileExecutionResult(
                status = "success",
                message = "Gradle compile finished successfully.",
            )
        } else {
            val status = if (runResult.isNeedResetHasRun) "canceled" else "failed"
            GradleCompileExecutionResult(
                status = status,
                message = "Gradle compile finished with status=$status.",
            )
        }
    }

    private fun writeSshAudit(
        auditId: String,
        requestedBy: String,
        reason: String,
        confirmed: Boolean,
        outcome: String,
    ) {
        val auditFile = pathManager.logDir.resolve("ssh_info_audit.log")
        auditFile.parentFile?.mkdirs()
        val line = buildString {
            append("{")
            append("\"auditId\":\"").append(auditId).append("\",")
            append("\"requestedBy\":\"").append(escapeJson(requestedBy)).append("\",")
            append("\"reason\":\"").append(escapeJson(reason)).append("\",")
            append("\"confirmed\":").append(confirmed).append(",")
            append("\"outcome\":\"").append(outcome).append("\",")
            append("\"timestamp\":\"").append(Instant.now().toString()).append("\"")
            append("}\n")
        }
        auditFile.appendText(line)
    }

    private fun escapeJson(raw: String): String {
        return raw
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
    }
}
