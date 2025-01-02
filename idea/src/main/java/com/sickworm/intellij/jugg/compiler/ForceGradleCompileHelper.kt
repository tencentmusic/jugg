package com.sickworm.intellij.jugg.compiler

import com.intellij.execution.ProgramRunnerUtil
import com.intellij.execution.RunManager
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.openapi.project.Project
import com.sickworm.intellij.jugg.ide.JuggRunConfiguration
import com.sickworm.intellij.jugg.ide.bean.ConfirmResult
import com.sickworm.intellij.jugg.ide.ui.CommonConfirmDialog

object ForceGradleCompileHelper {

    var isForceGradleCompileNextTime = false
    var isForceReinstallNextTime = false

    fun executeGradleCompile(project: Project) {
        val currentConfiguration = RunManager.getInstance(project).selectedConfiguration
        if (currentConfiguration?.configuration !is JuggRunConfiguration) {
            CommonConfirmDialog.showAndGetResult(
                "Run failed", "Please select Jugg run configuration first.",
                okButtonText = "I got it!"
            )
            return
        }
        val confirmResult = CommonConfirmDialog.showAndGetOrCancel(
            "Confirm fallback", "Jugg is going to fallback to gradle. Continue?",
            okButtonText = "Yes",
            negativeButtonText = "No",
            leftButtonText = "Just Reinstall",
        )
        when (confirmResult) {
            ConfirmResult.POSITIVE -> {
                isForceGradleCompileNextTime = true
                ProgramRunnerUtil.executeConfiguration(currentConfiguration, DefaultRunExecutor.getRunExecutorInstance())
            }
            ConfirmResult.LEFT -> {
                isForceReinstallNextTime = true
                ProgramRunnerUtil.executeConfiguration(currentConfiguration, DefaultRunExecutor.getRunExecutorInstance())
            }
            else -> {
                // no-op
            }
        }
    }
}