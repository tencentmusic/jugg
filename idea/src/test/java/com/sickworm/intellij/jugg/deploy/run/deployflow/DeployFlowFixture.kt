package com.sickworm.intellij.jugg.deploy.run.deployflow

import com.android.ddmlib.IDevice
import com.sickworm.intellij.jugg.deploy.DeployFileManager
import com.sickworm.intellij.jugg.deploy.IDeployHistoryManager
import com.sickworm.intellij.jugg.deploy.IDeployTargetManager
import com.sickworm.intellij.jugg.deploy.run.DeployOptions
import com.sickworm.intellij.jugg.deploy.run.JuggDeployerHelper

/**
 * Prepared collaborators for one L2 deploy-flow scenario (Virtual Device).
 */
data class DeployFlowFixture(
    val caseId: DeployFlowCaseId,
    val virtualDevice: VirtualDeployDevice,
    val device: IDevice,
    val deployOptions: DeployOptions,
    val helper: JuggDeployerHelper,
    val deployFileManager: DeployFileManager,
    val deployHistoryManager: IDeployHistoryManager,
    val deployTargetManager: IDeployTargetManager,
    val compatBoundary: DeployFlowAsDeployerCompatBoundary,
    val ideDeployStateHelper: DeployFlowIdeDeployStateHelper,
    val recoverRunHost: DeployFlowRecoverRunHost? = null,
    val seededOverlayId: String,
)
