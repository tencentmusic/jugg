package com.sickworm.intellij.jugg.deploy.run.deployflow

import com.android.ddmlib.IDevice
import com.sickworm.intellij.jugg.deploy.IIdeDeployStateHelper
import com.sickworm.intellij.jugg.deploy.run.IdeDeployState
import java.util.concurrent.atomic.AtomicInteger

/**
 * Drives [com.sickworm.intellij.jugg.deploy.DeployStateManager] through [IIdeDeployStateHelper] for deploy-flow tests.
 */
class DeployFlowIdeDeployStateHelper : IIdeDeployStateHelper {

    private val recoverWaitOkBudget = AtomicInteger(0)

    override fun getIdeDeployState(device: IDevice?, packageName: String?): IdeDeployState {
        if (recoverWaitOkBudget.getAndDecrement() > 0) {
            return IdeDeployState.ok
        }
        return IdeDeployState.appNotRunningOrNotDebuggable
    }

    fun forIncrementalNotDeployable() {
        recoverWaitOkBudget.set(0)
    }

    /** After install recover task, before [waitingForDeployable] polls. */
    fun signalInstallCompletedForRecoverWait() {
        recoverWaitOkBudget.set(RECOVER_WAIT_OK_BUDGET)
    }

    companion object {
        private const val RECOVER_WAIT_OK_BUDGET = 12
    }
}
