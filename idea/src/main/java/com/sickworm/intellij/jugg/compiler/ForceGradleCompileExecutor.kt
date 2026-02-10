package com.sickworm.intellij.jugg.compiler

import com.intellij.execution.ProgramRunnerUtil
import com.intellij.execution.RunManager
import com.intellij.execution.configurations.RunConfigurationBase
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.sickworm.intellij.jugg.deploy.DeployFileManager
import com.sickworm.intellij.jugg.deploy.run.ExportIncrementalApkHelper
import com.sickworm.intellij.jugg.ide.JuggConfigurationType
import com.sickworm.intellij.jugg.ide.JuggRunConfiguration
import com.sickworm.intellij.jugg.ide.JuggRunConfigurationOptions
import com.sickworm.intellij.jugg.ide.bean.ConfirmResult
import com.sickworm.intellij.jugg.ide.logic.IJuggConfigurationRunner
import com.sickworm.intellij.jugg.ide.ui.CommonConfirmDialog
import com.sickworm.intellij.jugg.project.CompileContextManager
import com.sickworm.intellij.jugg.project.TaskRunnerManager
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
}
