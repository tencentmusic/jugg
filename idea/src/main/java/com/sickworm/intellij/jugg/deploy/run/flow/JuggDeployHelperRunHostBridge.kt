package com.sickworm.intellij.jugg.deploy.run.flow

import com.android.ddmlib.IDevice
import com.sickworm.intellij.jugg.compiler.CompileUiHandler
import com.sickworm.intellij.jugg.deploy.run.DeployOptions
import com.sickworm.intellij.jugg.deploy.run.DeployTaskResult
import com.sickworm.intellij.jugg.deploy.run.JuggDeployData
import com.sickworm.intellij.jugg.deploy.run.JuggDeployerHelper

/**
 * Forwards [IJuggDeployHelperRunHost] to [JuggDeployerHelper] after construction.
 */
internal class JuggDeployHelperRunHostBridge : IJuggDeployHelperRunHost {

    private lateinit var host: JuggDeployerHelper

    fun bind(host: JuggDeployerHelper) {
        this.host = host
    }

    override fun runRecoverDeployTask(
        device: IDevice,
        data: JuggDeployData,
        isSkipExceptOverlayCheck: Boolean,
        compileUiHandler: CompileUiHandler,
        deferPostDeployLaunch: Boolean,
        isAllowDirectOverlayDeploy: Boolean,
    ) {
        host.runRecoverDeployTask(
            device,
            data,
            isSkipExceptOverlayCheck,
            compileUiHandler,
            deferPostDeployLaunch,
            isAllowDirectOverlayDeploy,
        )
    }

    override fun redeploy(deployOptions: DeployOptions): DeployTaskResult = host.redeploy(deployOptions)

    override fun tryRetryInstall(
        deployOptions: DeployOptions,
        deployData: JuggDeployData,
        reason: String,
    ): DeployTaskResult? = host.tryRetryInstall(deployOptions, deployData, reason)

    override fun detectJvmtiCompatIssue(device: IDevice, deployData: JuggDeployData): Boolean =
        host.detectJvmtiCompatIssue(device, deployData)
}
