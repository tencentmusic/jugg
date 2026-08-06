package com.sickworm.intellij.jugg.deploy.run.deployflow

import com.sickworm.intellij.jugg.deploy.api.IDevice
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.Project
import com.sickworm.intellij.jugg.compiler.CompileUiHandler
import com.sickworm.intellij.jugg.deploy.DeployFileManager
import com.sickworm.intellij.jugg.deploy.DeployStateManager
import com.sickworm.intellij.jugg.deploy.IDeployHistoryManager
import com.sickworm.intellij.jugg.deploy.IDeployTargetManager
import com.sickworm.intellij.jugg.deploy.IDeviceAdb
import com.sickworm.intellij.jugg.deploy.IJuggDeploymentService
import com.sickworm.intellij.jugg.deploy.run.flow.DeployStateRecover
import com.sickworm.intellij.jugg.deploy.run.flow.IJuggDeployHelperRunHost

/**
 * Post-recover fixture hook only: refresh IDE deploy-state mock before incremental runTask.
 * Does not alter dry-deploy or reinstall branching (delegates to production [DeployStateRecover]).
 */
class DeployFlowRecoverFixtureHooks(
    project: Project,
    deployTargetManager: IDeployTargetManager,
    deployFileManager: DeployFileManager,
    deployHistoryManager: IDeployHistoryManager,
    private val deployStateManager: DeployStateManager,
    deployRunHost: IJuggDeployHelperRunHost,
    deploymentService: IJuggDeploymentService,
    deviceAdbFactory: (IDevice, Logger) -> IDeviceAdb,
    logger: Logger,
    private val ideDeployStateHelper: DeployFlowIdeDeployStateHelper,
    private val afterRecoverSuccess: Runnable? = null,
) : DeployStateRecover(
    project = project,
    deployTargetManager = deployTargetManager,
    deployFileManager = deployFileManager,
    deployHistoryManager = deployHistoryManager,
    deployStateManager = deployStateManager,
    deployRunHost = deployRunHost,
    deploymentService = deploymentService,
    deviceAdbFactory = deviceAdbFactory,
    logger = logger,
) {

    override fun recoverDeployState(
        device: IDevice,
        indicator: ProgressIndicator?,
        isNeedDryDeployFirst: Boolean,
        isSkipExceptOverlayCheck: Boolean,
        isInstallUpdateApk: Boolean,
        compileUiHandler: CompileUiHandler,
        allowDirectOverlayRecover: Boolean,
    ): Pair<Boolean, Boolean> {
        val result = super.recoverDeployState(
            device = device,
            indicator = indicator,
            isNeedDryDeployFirst = isNeedDryDeployFirst,
            isSkipExceptOverlayCheck = isSkipExceptOverlayCheck,
            isInstallUpdateApk = isInstallUpdateApk,
            compileUiHandler = compileUiHandler,
            allowDirectOverlayRecover = allowDirectOverlayRecover,
        )
        if (result.first) {
            ideDeployStateHelper.forIncrementalNotDeployable()
            deployStateManager.updateDeployState()
            afterRecoverSuccess?.run()
        }
        return result
    }
}
