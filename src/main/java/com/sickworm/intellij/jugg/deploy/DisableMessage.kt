package com.sickworm.intellij.jugg.deploy

class DisableMessage(
    val disableMode: DisableMode,
    val tooltip: String,
    val description: String
) {
    enum class DisableMode {
        INVISIBLE, DISABLED
    }
}