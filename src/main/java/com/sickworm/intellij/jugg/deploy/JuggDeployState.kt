package com.sickworm.intellij.jugg.deploy

/**
 * The deployment state of a project.
 */
data class JuggDeployState(
    /**
     * We can do assemble app and launch if it's true.
     */
    val isReadyRunFullBuild: Boolean,
    /**
     * We can do incremental compile if it's true.
     */
    val isReadyCompile: Boolean,
    /**
     * We can do incremental deploy if it's true.
     */
    val isReadyDeploy: Boolean,
    /**
     * Reason why we can't do incremental deploy.
     */
    val disableMessage: DisableMessage?,
) {
    constructor(disableMessage: DisableMessage, isReadyInstall: Boolean = false):
            this(isReadyInstall, false, false, disableMessage)

    val msg get() = disableMessage?.tooltip ?: "ready to deploy"

    override fun toString(): String {
        return "[${isReadyRunFullBuild.toInt()}${isReadyCompile.toInt()}${isReadyDeploy.toInt()}](${msg})"
    }

    private fun Boolean.toInt() = if (this) 1 else 0

    companion object {
        val READY = JuggDeployState(
            isReadyRunFullBuild = true,
            isReadyCompile = true,
            isReadyDeploy = true,
            disableMessage = null
        )
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