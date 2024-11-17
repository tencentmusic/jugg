package com.sickworm.intellij.jugg.deploy

import com.android.ddmlib.IDevice
import com.intellij.openapi.project.Project
import com.sickworm.intellij.jugg.deploy.run.AsDeployerCompat
import com.sickworm.intellij.jugg.deploy.run.IdeDeployState
import com.sickworm.intellij.jugg.ide.JuggSettings
import com.sickworm.intellij.jugg.logger.JuggLogger

/**
 * Manage [JuggDeployState].
 */
class DeployStateManager(
    private val project: Project,
    private val deployTargetManager: IDeployTargetManager,
    private val deployHistoryManager: IDeployHistoryManager,
    private val ideDeployStateHelper: IIdeDeployStateHelper = IdeDeployStateHelper(project),
) {

    private val logger = JuggLogger.getInstance(project, "DeployStateManager")

    var deployState = JuggDeployState(
        JuggDeployState.State.NOTHING_CAN_DO,
        "jugg not initialized",
        IdeDeployState.ok,
    )
        private set

    private var deployStateMap = mapOf<String, JuggDeployState>()

    @Volatile
    var isBuildFileChanged = false

    @Volatile
    var whatBuildFileChanged: String = ""

    @Volatile
    var isInitializingIncrementalCompile = false

    /**
     * Invoke when project need to update [JuggDeployState].
     */
    fun updateDeployState(): JuggDeployState {
        var lastState = deployState
        deployState = getNewDeployState()
        while (lastState != deployState) {
            logger.debug("deploy state changed: $lastState -> $deployState")

            // deploy state not stable, need revoke again.
            // case:
            // first unplug/plug a device, first you will get unknown API level
            // then you will get app not detect
            // last you will get ready to deploy (if it is)
            lastState = deployState
            deployState = getNewDeployState()
            Thread.sleep(10)
        }

        // now deploy state is stable
        return deployState
    }

    fun getDeployState(device: IDevice): JuggDeployState {
        return deployStateMap[device.name] ?: getNewDeployState(device)
    }

    private fun getNewDeployState(): JuggDeployState {
        deployStateMap = deployTargetManager.getDevices().map {
            // name includes serial number
            it.name to getNewDeployState(it)
        }.associate { it }

        return if (deployStateMap.isEmpty()) {
            getNewDeployState(null)
        } else {
            deployStateMap.minBy { it.value.state.ordinal }.value
        }
    }

    private fun getNewDeployState(device: IDevice? = null): JuggDeployState {
        val ideDeployState = ideDeployStateHelper.getIdeDeployState(device, deployTargetManager.getPackageNameOrNull())

        if (isBuildFileChanged) {
            return JuggDeployState(JuggDeployState.State.READY_FULL_COMPILE, "$whatBuildFileChanged changed", ideDeployState)
        }

        if (!deployHistoryManager.hasBeenFullCompiled) {
            return JuggDeployState(JuggDeployState.State.READY_FULL_COMPILE, "not gradle compile yet", ideDeployState)
        }

        if (deployHistoryManager.isLastFullCompileFailed) {
            return JuggDeployState(JuggDeployState.State.READY_FULL_COMPILE, "last gradle compile not success", ideDeployState)
        }

        if (ideDeployState.state != IdeDeployState.State.OK) {
            return JuggDeployState(JuggDeployState.State.READY_INCREMENTAL_COMPILE,
                ideDeployState.message,
                ideDeployState,
            )
        }

        return JuggDeployState.READY
    }
}

interface IIdeDeployStateHelper {
    fun getIdeDeployState(device: IDevice?, packageName: String?): IdeDeployState
}

class IdeDeployStateHelper(
    private val project: Project,
) : IIdeDeployStateHelper {

    override fun getIdeDeployState(device: IDevice?, packageName: String?): IdeDeployState {
        return AsDeployerCompat.getIdeDeployStateResult(project, device, packageName)
    }

}