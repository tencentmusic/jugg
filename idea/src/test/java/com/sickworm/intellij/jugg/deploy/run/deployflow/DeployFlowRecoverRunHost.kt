package com.sickworm.intellij.jugg.deploy.run.deployflow

import com.android.ddmlib.IDevice
import com.sickworm.intellij.jugg.compiler.CompileUiHandler
import com.sickworm.intellij.jugg.deploy.run.DeployOptions
import com.sickworm.intellij.jugg.deploy.run.DeployTaskResult
import com.sickworm.intellij.jugg.deploy.run.JuggDeployData
import com.sickworm.intellij.jugg.deploy.run.JuggDeployerHelper
import com.sickworm.intellij.jugg.deploy.run.flow.IJuggDeployHelperRunHost

/**
 * Late-bound [IJuggDeployHelperRunHost] for deploy-flow L2 tests.
 * Optional [onAfterInstallRecoverTask] re-aligns deployment cache after a real install recover task.
 */
class DeployFlowRecoverRunHost(
    private val onAfterInstallRecoverTask: Runnable? = null,
) : IJuggDeployHelperRunHost {

    private lateinit var helper: JuggDeployerHelper

    var recoverTaskInvokeCount: Int = 0
        private set
    var installRecoverTaskCount: Int = 0
        private set

    fun bind(helper: JuggDeployerHelper) {
        this.helper = helper
    }

    override fun runRecoverDeployTask(
        device: IDevice,
        data: JuggDeployData,
        isSkipExceptOverlayCheck: Boolean,
        compileUiHandler: CompileUiHandler,
        deferPostDeployLaunch: Boolean,
    ) {
        recoverTaskInvokeCount++
        if (data.isInstall) {
            installRecoverTaskCount++
        }
        helper.runRecoverDeployTask(device, data, isSkipExceptOverlayCheck, compileUiHandler, deferPostDeployLaunch)
        if (data.isInstall) {
            onAfterInstallRecoverTask?.run()
        }
    }

    override fun redeploy(deployOptions: DeployOptions): DeployTaskResult = helper.redeploy(deployOptions)

    override fun tryRetryInstall(
        deployOptions: DeployOptions,
        deployData: JuggDeployData,
        reason: String,
    ): DeployTaskResult? = helper.tryRetryInstall(deployOptions, deployData, reason)

    override fun detectJvmtiCompatIssue(device: IDevice, deployData: JuggDeployData): Boolean =
        helper.detectJvmtiCompatIssue(device, deployData)
}
