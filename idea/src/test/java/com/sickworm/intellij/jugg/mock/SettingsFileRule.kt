package com.sickworm.intellij.jugg.mock

import com.sickworm.intellij.jugg.ide.bean.JuggSettings
import com.sickworm.intellij.jugg.project.runtime.JuggGlobalPathManager
import org.junit.rules.ExternalResource

/** Isolates persisted settings without changing the Runtime's immutable storage root. */
class SettingsFileRule : ExternalResource() {
    private var previous: ByteArray? = null

    override fun before() {
        val file = JuggGlobalPathManager.settingsFile
        previous = if (file.exists()) file.readBytes() else null
        file.parentFile.mkdirs()
        file.delete()
        JuggSettings.reload()
    }

    override fun after() {
        val file = JuggGlobalPathManager.settingsFile
        previous?.let { file.writeBytes(it) } ?: file.delete()
        JuggSettings.reload()
    }
}
