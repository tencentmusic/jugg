package com.sickworm.intellij.jugg.deploy

/**
 * The deployment state of a project.
 */
data class JuggDeployState(
    val state: State,
    /**
     * Reason why we can't do incremental deploy. Or "ready to deploy" if state = [State.READY_DEPLOY].
     */
    val msg: String,
) {

    /**
     * We can do assemble app and launch if it's true.
     */
    val isReadyRunFullBuild: Boolean get() = state > State.NOTHING_CAN_DO

    /**
     * We can do incremental compile if it's true.
     */
    val isReadyIncCompile: Boolean get() = state > State.READY_FULL_COMPILE

    /**
     * We can do incremental deploy if it's true.
     */
    val isReadyDeploy: Boolean get() = state > State.READY_INCREMENTAL_COMPILE

    val deployButtonText: String get() = when(state) {
        State.NOTHING_CAN_DO -> "Deploy"
        State.READY_FULL_COMPILE -> "Build & Launch"
        State.READY_INCREMENTAL_COMPILE -> "Install & Launch"
        State.READY_DEPLOY -> "Deploy"
    }

    override fun toString(): String {
        return "[$state]($msg)"
    }

    companion object {
        val READY = JuggDeployState(
            State.READY_DEPLOY,
            msg = "ready to deploy",
        )

        fun canNotFullBuild(disableMessage: DisableMessage): JuggDeployState {
            return JuggDeployState(State.NOTHING_CAN_DO, disableMessage.tooltip)
        }

        fun canNotIncrementalDeploy(disableMessage: DisableMessage): JuggDeployState {
            return JuggDeployState(State.READY_INCREMENTAL_COMPILE, disableMessage.tooltip)
        }
    }

    enum class State {
        NOTHING_CAN_DO,
        READY_FULL_COMPILE,
        READY_INCREMENTAL_COMPILE,
        READY_DEPLOY,
        ;
    }
}

data class DisableMessage(
    val disableMode: DisableMode,
    val tooltip: String,
    val description: String
) {
    enum class DisableMode {
        INVISIBLE, DISABLED
    }
}