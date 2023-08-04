package com.sickworm.intellij.jugg.deploy

import com.intellij.openapi.project.Project
import com.sickworm.intellij.jugg.deploy.run.AsDeployerCompat
import com.sickworm.intellij.jugg.logger.JuggLogger

/**
 * Manage [JuggDeployState].
 */
class DeployStateManager(
    private val project: Project,
    private val deployHistoryManager: IDeployHistoryManager,
    private val ideDeployStateHelper: IIdeDeployStateHelper = IdeDeployStateHelper(project),
) {

    private val logger = JuggLogger.getInstance(project, "DeployStateManager")

    var deployState = JuggDeployState(
        JuggDeployState.State.NOTHING_CAN_DO,
        "jugg not initialized"
    )
        private set

    var isBuildGradleChanged = false

    var isResourceFileChanged = false

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

    private fun getNewDeployState(): JuggDeployState {
        val ideDeployState = ideDeployStateHelper.getIdeDeployState()
        if (!ideDeployState.isReadyRunFullBuild) {
            return ideDeployState
        }

        if (isBuildGradleChanged) {
            return JuggDeployState(JuggDeployState.State.READY_FULL_COMPILE, "build.gradle changed")
        }

        // reopen resource file incremental compile
//        if (isResourceFileChanged) {
//            return JuggDeployState(JuggDeployState.State.READY_FULL_COMPILE, "XML file changed")
//        }

        if (!deployHistoryManager.hasBeenFullCompiled) {
            return JuggDeployState(JuggDeployState.State.READY_FULL_COMPILE, "need full compile")
        }

        return ideDeployState
    }
}

interface IIdeDeployStateHelper {
    fun getIdeDeployState(): JuggDeployState
}

class IdeDeployStateHelper(
    private val project: Project,
) : IIdeDeployStateHelper {

    override fun getIdeDeployState(): JuggDeployState {
        val disableMessage = AsDeployerCompat.getDisableMessage(project)
        if (disableMessage != null) {
            return canNotIncrementalDeploy(disableMessage)
        }

        return JuggDeployState.READY
    }

    private fun canNotIncrementalDeploy(disableMessage: String): JuggDeployState {
        return JuggDeployState(JuggDeployState.State.READY_INCREMENTAL_COMPILE, disableMessage)
    }

}