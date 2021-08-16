package com.sickworm.intellij.jugg.deploy

data class DeployState(
    val isReadyInstall: Boolean,
    val isReadyApply: Boolean,
    val disableMessage: DisableMessage?,
) {
    constructor(disableMessage: DisableMessage, isReadyInstall: Boolean = false):
            this(isReadyInstall, false, disableMessage)

    val msg get() = disableMessage?.tooltip ?: "ready to apply"
}