package com.sickworm.intellij.jugg.deploy.run

import com.android.tools.deployer.model.Apk

/**
 * Runtime-neutral wrappers for Android Studio deployer objects whose packages move between releases.
 */
data class JuggInstallSession(
    val rawInstaller: Any,
    val installerVersion: String?,
    private val onPrompt: (String) -> Boolean,
    private val onMessage: (String) -> Unit,
) {
    fun prompt(message: String): Boolean = onPrompt(message)

    fun message(message: String) = onMessage(message)

    enum class Mode {
        DELTA,
        DELTA_NO_SKIP,
        FULL,
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
