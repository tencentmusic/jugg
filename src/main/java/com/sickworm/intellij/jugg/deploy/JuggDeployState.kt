package com.sickworm.intellij.jugg.deploy

/**
 * The deployment state of a project.
 */
data class JuggDeployState(
    val state: State,
    /**
     * Reason why we can't do incremental deploy. Or "ready to deploy" if state = [State.READY_TO_DEPLOY].
     */
    val msg: String,
) {

    /**
     * We can do assemble app and launch if it's true.
     */
    val isReadyRunFullBuild: Boolean get() = state > State.CANNOT_FULL_COMPILE

    /**
     * We can do incremental compile if it's true.
     */
    val isReadyIncCompile: Boolean get() = state > State.CANNOT_INCREMENTAL_COMPILE

    /**
     * We can do incremental deploy if it's true.
     */
    val isReadyDeploy: Boolean get() = state > State.CANNOT_INCREMENTAL_DEPLOY

    val deployButtonText: String get() = when(state) {
        State.CANNOT_FULL_COMPILE -> "Deploy"
        State.CANNOT_INCREMENTAL_COMPILE -> "Build & Launch"
        State.CANNOT_INCREMENTAL_DEPLOY -> "Install & Launch"
        State.READY_TO_DEPLOY -> "Deploy"
    }

    override fun toString(): String {
        return "[$state]($msg)"
    }

    companion object {
        val READY = JuggDeployState(
            State.READY_TO_DEPLOY,
            msg = "ready to deploy",
        )

        fun canNotFullBuild(disableMessage: DisableMessage): JuggDeployState {
            return JuggDeployState(State.CANNOT_FULL_COMPILE, disableMessage.tooltip)
        }

        fun canNotIncrementalDeploy(disableMessage: DisableMessage): JuggDeployState {
            return JuggDeployState(State.CANNOT_INCREMENTAL_DEPLOY, disableMessage.tooltip)
        }
    }

    enum class State {
        CANNOT_FULL_COMPILE,
        CANNOT_INCREMENTAL_COMPILE,
        CANNOT_INCREMENTAL_DEPLOY,
        READY_TO_DEPLOY,
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