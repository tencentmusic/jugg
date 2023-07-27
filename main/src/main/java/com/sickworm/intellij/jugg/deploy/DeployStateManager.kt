package com.sickworm.intellij.jugg.deploy

import com.intellij.openapi.project.Project
import com.sickworm.intellij.jugg.deploy.run.AsDeployerCompat

/**
 * Manage [JuggDeployState].
 */
class DeployStateManager(
    private val project: Project,
    private val deployHistoryManager: IDeployHistoryManager,
    private val ideDeployStateHelper: IIdeDeployStateHelper = IdeDeployStateHelper(project),
) {

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
        deployState = getNewDeployState()
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