@file:Suppress("PLUGIN_IS_NOT_ENABLED")

package com.sickworm.intellij.jugg.server.protocols

import kotlinx.serialization.Serializable

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
    val moduleCustomConfigs: List<ModuleCustomConfig>?,
    /**
     * custom compile plugins. maybe absolute/relative local file path, or download url.
     */
    val customCompilers: List<CustomCompilerInfo>?,
    /**
     * embedded apks in apk
     */
    val embeddedApksSearchRules: List<String>?,
)

@Serializable
data class ModuleCustomConfig(
    val moduleStdPath: String, // platform independent path
    /** sync and add these path to classpath */
    val customClasspath: List<String>,
    /** sync these paths */
    val customSyncFilePath: List<String>,
    /** don't filter ignored modules in FileChangesHandler.compiledModules */
    val isDoNotIgnored: Boolean,
)

data class NotificationData(
    val title: String,
    val content: String,
    val buttonText: String?,
    val jumpUrl: String?,
    val isSticky: Boolean,
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
    val isNeedReinstall: Boolean,
)

data class JarFileInfo(
    val uniqueName: String,
    val url: String,
    val md5: String,
)

data class CustomCompilerInfo(
    val jarFileName: String,
    val path: String,
    val md5: String,
)

data class InteractionProcessFlow(
    val stepList: List<InteractionStepDesc>,
    val firstStep: InteractionStep,
    val token: String,
    val quitUrl: String,
)

data class InteractionStepDesc(
    val stepName: String,
)

data class InteractionStep(
    val stepName: String,
    val title: String,
    val htmlText: String,
    val nextStepUrl: String? = null,
    val checkFinishUrl: String? = null,
    val isSuccess: Boolean = true,
    val inputTips: List<String> = emptyList(),
    val isCanRetryWhenFailed: Boolean = false,
    val remoteServerInfo: RemoteServerInfo? = null,
)

data class RemoteServerInfo(
    val remoteSshUser: String?,
    val remoteSshPassword: String?,
    val remoteSshIp: String?,
    val remoteSshPort: Int,
    val httpProxyIp: String?,
    val httpProxyPort: Int,
    val isSyncAllProjects: Boolean,
    val syncMode: String?,
    val remoteSyncPath: String?,
)