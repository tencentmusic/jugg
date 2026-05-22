package com.sickworm.intellij.jugg.deploy.run.deployflow

import com.sickworm.intellij.jugg.deploy.run.IAsDeployerCompat

/**
 * Physical-boundary compat for deploy-flow L2 tests (install + optimisticSwap).
 */
object DeployFlowStaticBoundaryMocks {

    fun createCompat(
        virtualDevice: VirtualDeployDevice,
        onInstall: Runnable = Runnable { virtualDevice.onInstallCompleted() },
    ): IAsDeployerCompat {
        return DeployFlowAsDeployerCompatBoundary(
            virtualDevice = virtualDevice,
            onInstall = onInstall,
        )
    }
}
