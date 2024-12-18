package com.sickworm.intellij.jugg.server.protocols

@Suppress("PropertyName", "unused")
data class EventData(val version: String, val ide_version: String, val username: String, val project_id: String, val session_id: String, val action: String, val is_success: Boolean, val cost_time: Int, val detail: String?)

data class VersionData(
    val latestVersion: String,
    val isNeedUpgrade: Boolean,
    val downloadUrl: String,
    @Deprecated("wont' use after v1.2.0")
    val templateList: List<RunConfigurationTemplate>,
    val notification: NotificationData?,
    val customConfigJson: ProjectCustomConfig?,
) {
    companion object {
        @Suppress("unused")
        val empty = VersionData("", false, "", emptyList(), null, null)
    }
}

data class ProjectCustomConfig(
    val serverUrls: List<String>? = null,
    @Deprecated("use buildFileRules after Jugg 2.0.1")
    val buildFileList: List<String>,
    val buildFileRules: List<String>,
)

data class NotificationData(
    val title: String,
    val content: String,
    val buttonText: String,
    val jumpUrl: String,
)