package com.sickworm.intellij.jugg.deploy.run.deployflow

/**
 * Builds deploy-flow fixtures backed by [VirtualDeployDevice].
 */
interface DeployFlowDeviceBackend {
    fun buildFixture(caseId: DeployFlowCaseId): DeployFlowFixture
}
