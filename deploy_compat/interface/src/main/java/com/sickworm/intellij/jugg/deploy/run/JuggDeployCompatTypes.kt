package com.sickworm.intellij.jugg.deploy.run

import com.sickworm.intellij.jugg.deploy.api.Apk

/**
 * Runtime-neutral wrappers for Android Studio deployer objects whose packages move between releases.
 */
data class JuggInstallSession(
    /** Executor selected when this stateful deployer runtime was created. */
    val applyChangesExecutor: IApplyChangesExecutor,
    val rawInstaller: Any,
    val installerVersion: String?,
    private val onPrompt: (String) -> PromptAction,
    private val onMessage: (String) -> Unit,
) {
    var allowDowngrade: Boolean = false

    fun prompt(message: String): Boolean {
        if (allowDowngrade && isVersionDowngrade(message)) return false
        return when (onPrompt(message)) {
            PromptAction.ALLOW_DOWNGRADE -> {
                allowDowngrade = true
                false
            }
            PromptAction.UNINSTALL -> true
            PromptAction.CANCEL -> false
        }
    }

    fun message(message: String) = onMessage(message)

    /** Host choices adapted to the deployer's Boolean uninstall confirmation. */
    enum class PromptAction {
        ALLOW_DOWNGRADE,
        UNINSTALL,
        CANCEL,
    }

    enum class Mode {
        DELTA,
        DELTA_NO_SKIP,
        FULL,
    }

    companion object {
        fun isVersionDowngrade(message: String): Boolean {
            // Studio exposes only the formatted prompt at this boundary, not InstallStatus.
            return message.contains("INSTALL_FAILED_VERSION_DOWNGRADE") ||
                message.contains("The device already has a newer version of this application.")
        }
    }
}

data class JuggOverlayId(
    val raw: Any,
    val sha: String,
    val isBaseInstall: Boolean,
    val overlayFiles: List<JuggOverlayFile> = emptyList(),
)

data class JuggDeploymentCacheEntry(
    val raw: Any,
    val apks: List<Apk>,
    val overlayId: JuggOverlayId,
)

data class JuggOverlayFile(
    val path: String,
    val checksum: Long,
)

/** Wraps one runtime-specific class redefiner without exposing its implementation type. */
data class JuggClassRedefiner(
    val raw: Any,
)

class JuggDeployerException(
    val errorOrdinal: Int,
    override val message: String?,
    val details: String?,
    cause: Throwable? = null,
) : Exception(message, cause)
