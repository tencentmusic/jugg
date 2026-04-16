package com.sickworm.intellij.jugg.deploy

import com.sickworm.intellij.jugg.deploy.run.IdeDeployState

/**
 * The deployment state of a project.
 */
data class JuggDeployState(
    val state: State,
    /**
     * Reason why we can't do incremental deploy. Or "ready to deploy" if state = [State.READY_DEPLOY].
     */
    val msg: String,
    /**
     * The state of deployment which detected by IDE. Use to check why we can't deploy.
     */
    val ideDeployState: IdeDeployState,
) {

    /**
     * We can do incremental compile if it's true.
     */
    val isReadyIncCompile: Boolean get() = state > State.READY_FULL_COMPILE

    /**
     * We can do incremental deploy if it's true.
     */
    val isReadyDeploy: Boolean get() = state > State.READY_INCREMENTAL_COMPILE

    override fun toString(): String {
        return "[$state]($msg)"
    }

    companion object {
        val READY = JuggDeployState(
            State.READY_DEPLOY,
            "ready to deploy",
            IdeDeployState.ok,
        )
    }

    /**
     * State represents readiness progression from blocked/building to deploy-ready.
     */
    enum class State {
        NOTHING_CAN_DO,
        READY_FULL_COMPILE,
        READY_INCREMENTAL_COMPILE,
        READY_DEPLOY,
        ;
    }
}
