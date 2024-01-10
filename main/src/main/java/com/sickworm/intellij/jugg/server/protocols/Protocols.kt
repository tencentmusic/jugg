package com.sickworm.intellij.jugg.server.protocols

@Suppress("PropertyName", "unused")
data class EventData(val version: String, val ide_version: String, val username: String, val project_id: String, val session_id: String, val action: String, val is_success: Boolean, val cost_time: Int, val detail: String?)

data class VersionData(
    val latestVersion: String,
    val isNeedUpgrade: Boolean,
    val downloadUrl: String,
    val templateList: List<RunConfigurationTemplate>,
    val popupText: String?,
    val popupUrl: String?,
    val customConfigJson: ProjectCustomConfig?,
) {
    companion object {
        @Suppress("unused")
        val empty = VersionData("", false, "", emptyList(), null, null, null)
    }
}

data class ProjectCustomConfig(
    val serverUrl: String? = null,
    val buildFileList: List<String> = emptyList(),
)