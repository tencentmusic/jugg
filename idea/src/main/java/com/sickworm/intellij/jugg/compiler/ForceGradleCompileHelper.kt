package com.sickworm.intellij.jugg.compiler

import com.intellij.execution.ProgramRunnerUtil
import com.intellij.execution.RunManager
import com.intellij.execution.configurations.RunConfigurationBase
import com.intellij.execution.executors.DefaultRunExecutor
import com.sickworm.intellij.jugg.JuggManager
import com.sickworm.intellij.jugg.ide.JuggConfigurationType
import com.sickworm.intellij.jugg.ide.JuggRunConfiguration
import com.sickworm.intellij.jugg.ide.JuggRunConfigurationOptions
import com.sickworm.intellij.jugg.ide.bean.ConfirmResult
import com.sickworm.intellij.jugg.ide.ui.CommonConfirmDialog
import com.sickworm.intellij.jugg.rpc.RpcCommand
import com.sickworm.intellij.jugg.rpc.RpcRequest
import com.sickworm.intellij.jugg.rpc.RpcResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

object ForceGradleCompileHelper {

    var isForceGradleCompileNextTime = false
    var isCleanAndReinstallNextTime = false

    fun executeGradleCompile(juggManager: JuggManager) {
        val project = juggManager.project
        val currentConfiguration = RunManager.getInstance(project).selectedConfiguration
        val isJuggConfiguration = currentConfiguration?.configuration is JuggRunConfiguration
        juggManager.logger.debug("executeGradleCompile selected: $currentConfiguration, isJuggConfiguration:$isJuggConfiguration")

        var content = "Jugg is going to compile the project using gradle. Continue?"
        if (!isJuggConfiguration) {
            val currentRunConfigurationList = RunManager.getInstance(juggManager.project)
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
        )
        when (confirmResult) {
            ConfirmResult.POSITIVE -> {
                isForceGradleCompileNextTime = true
                if (isJuggConfiguration) {
                    ProgramRunnerUtil.executeConfiguration(currentConfiguration!!, DefaultRunExecutor.getRunExecutorInstance())
                } else {
                    tryRunFirstConfiguration(juggManager)
                }
            }
            ConfirmResult.LEFT -> {
                isCleanAndReinstallNextTime = true
                if (isJuggConfiguration) {
                    tryRunFirstConfiguration(juggManager)
                } else {
                    ProgramRunnerUtil.executeConfiguration(currentConfiguration!!, DefaultRunExecutor.getRunExecutorInstance())
                }            }
            else -> {
                // no-op
            }
        }
    }

    private fun tryRunFirstConfiguration(juggManager: JuggManager) {
        val rpcRequest = RpcRequest(
            cmd = RpcCommand.RUN,
            projectDir = juggManager.pathManager.projectDir.absolutePath,
            args = mapOf(
                "isRpcMode" to false,
            )
        )
        CoroutineScope(Dispatchers.IO).launch {
            val response = juggManager.call(rpcRequest)
            if (response.status != RpcResult.OK) {
                juggManager.logger.debug("tryRunFirstConfiguration failed: $response")
                CommonConfirmDialog.showAndGetResult(
                    "Run failed", "Error: $response",
                    okButtonText = "Close"
                )
            }
        }
    }
}