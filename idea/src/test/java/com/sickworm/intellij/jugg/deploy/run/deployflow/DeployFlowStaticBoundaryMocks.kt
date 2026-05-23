package com.sickworm.intellij.jugg.deploy.run.deployflow

import com.sickworm.intellij.jugg.deploy.run.IAsDeployerCompat

/**
 * Physical-boundary compat for deploy-flow L2 tests (install + optimisticSwap).
 */
object DeployFlowStaticBoundaryMocks {

    fun createCompat(
        virtualDevice: VirtualDeployDevice,
        optimisticSwapPolicy: DeployFlowAsDeployerCompatBoundary.OptimisticSwapPolicy =
            DeployFlowAsDeployerCompatBoundary.OptimisticSwapPolicy.FORBIDDEN,
        onInstall: Runnable = Runnable { virtualDevice.onInstallCompleted() },
        installerVersion: String? = null,
    ): DeployFlowAsDeployerCompatBoundary {
        return DeployFlowAsDeployerCompatBoundary(
            virtualDevice = virtualDevice,
            optimisticSwapPolicy = optimisticSwapPolicy,
            onInstall = onInstall,
            installerVersion = installerVersion,
        )
    }
}
