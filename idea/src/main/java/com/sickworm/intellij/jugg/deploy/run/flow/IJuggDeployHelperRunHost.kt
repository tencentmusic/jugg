package com.sickworm.intellij.jugg.deploy.run.flow

import com.android.ddmlib.IDevice
import com.sickworm.intellij.jugg.compiler.CompileUiHandler
import com.sickworm.intellij.jugg.deploy.run.DeployOptions
import com.sickworm.intellij.jugg.deploy.run.DeployTaskResult
import com.sickworm.intellij.jugg.deploy.run.JuggDeployData

/**
 * Host surface for [DeployStateRecover] and [DeployRetryHandler] to run deploy tasks on [com.sickworm.intellij.jugg.deploy.run.JuggDeployerHelper].
 */
interface IJuggDeployHelperRunHost {
    fun runRecoverDeployTask(
        device: IDevice,
        data: JuggDeployData,
        isSkipExceptOverlayCheck: Boolean,
        compileUiHandler: CompileUiHandler,
        deferPostDeployLaunch: Boolean = false,
        isAllowDirectOverlayDeploy: Boolean = true,
    )

    fun redeploy(deployOptions: DeployOptions): DeployTaskResult

    fun tryRetryInstall(
        deployOptions: DeployOptions,
        deployData: JuggDeployData,
        reason: String,
    ): DeployTaskResult?

    fun detectJvmtiCompatIssue(device: IDevice, deployData: JuggDeployData): Boolean
}