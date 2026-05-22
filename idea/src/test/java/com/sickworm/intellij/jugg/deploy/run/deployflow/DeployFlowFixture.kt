package com.sickworm.intellij.jugg.deploy.run.deployflow

import com.android.ddmlib.IDevice
import com.sickworm.intellij.jugg.deploy.DeployFileManager
import com.sickworm.intellij.jugg.deploy.run.DeployOptions
import com.sickworm.intellij.jugg.deploy.run.JuggDeployerHelper
import com.sickworm.intellij.jugg.deploy.run.flow.DeployStateRecover

/**
 * Prepared collaborators for one L2 deploy-flow scenario.
 */
data class DeployFlowFixture(
    val caseId: DeployFlowCaseId,
    val device: IDevice,
    val deployOptions: DeployOptions,
    val helper: JuggDeployerHelper,
    val executor: RecordingDeployRunTaskExecutor,
    val deployFileManager: DeployFileManager,
    val deployStateRecover: DeployStateRecover? = null,
)
