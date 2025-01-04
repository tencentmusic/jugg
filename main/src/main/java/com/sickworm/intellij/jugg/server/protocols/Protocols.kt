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
    val servers: List<ServerRule>? = null,
    @Deprecated("use buildFileRules after Jugg 2.0.1")
    val buildFileList: List<String>,
    val buildFileRules: List<String>,
    /** don't filter ignored files in filterUnchangedFiles. e.g. Detect changes for ignored build files */
    val dontFilterIgnoredFileRules: List<String>,
)

data class NotificationData(
    val title: String,
    val content: String,
    val buttonText: String?,
    val jumpUrl: String?,
)

data class ServerRule(
    val url: String,
    val checkReachableHost: String?,
)

data class HotUpdateData(
    val isNeedUpdate: Boolean,
    val targetVersion: String,
    val updateInfo: NotificationData?,
    val jarFileInfos: List<JarFileInfo>,
)

data class JarFileInfo(
    val uniqueName: String,
    val url: String,
    val md5: String,
)