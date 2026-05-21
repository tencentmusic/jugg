package com.sickworm.intellij.jugg.deploy.run

import com.android.ddmlib.IDevice
import com.sickworm.intellij.jugg.compiler.CompileUiHandler

/**
 * Host surface for [DeployStateRecover] and [DeployRetryHandler] to run deploy tasks on [JuggDeployerHelper].
 */
interface IJuggDeployHelperRunHost {
    fun runRecoverDeployTask(
        device: IDevice,
        data: JuggDeployData,
        isSkipExceptOverlayCheck: Boolean,
        compileUiHandler: CompileUiHandler,
    )

    fun redeploy(deployOptions: DeployOptions): DeployTaskResult

    fun tryRetryInstall(
        deployOptions: DeployOptions,
        deployData: JuggDeployData,
        reason: String,
    ): DeployTaskResult?

    fun detectJvmtiCompatIssue(device: IDevice, deployData: JuggDeployData): Boolean
}
