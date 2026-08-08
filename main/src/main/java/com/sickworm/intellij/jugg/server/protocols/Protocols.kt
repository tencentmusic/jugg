@file:Suppress("PLUGIN_IS_NOT_ENABLED")

package com.sickworm.intellij.jugg.server.protocols

import kotlinx.serialization.Serializable

@Suppress("PropertyName", "unused")
/**
 * EventData carries version, ide_version, username, and project_id.
 */
data class EventData(val version: String, val ide_version: String, val username: String, val project_id: String, val session_id: String, val action: String, val is_success: Boolean, val cost_time: Int, val detail: String?)

/**
 * VersionData carries latestVersion, isNeedUpgrade, downloadUrl, and templateList.
 */
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

/**
 * ProjectCustomConfig carries servers, buildFileList, buildFileRules, and dontFilterIgnoredFileRules.
 */
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
/**
 * ModuleCustomConfig carries moduleStdPath, customClasspath, customSyncFilePath, and isDoNotIgnored.
 */
data class ModuleCustomConfig(
    val moduleStdPath: String, // platform independent path
    /** sync and add these path to classpath */
    val customClasspath: List<String>,
    /** sync these paths */
    val customSyncFilePath: List<String>,
    /** don't filter ignored modules in FileChangesHandler.compiledModules */
    val isDoNotIgnored: Boolean,
)

/**
 * NotificationData carries title, content, buttonText, and jumpUrl.
 */
data class NotificationData(
    val title: String,
    val content: String,
    val buttonText: String?,
    val jumpUrl: String?,
    val isSticky: Boolean,
)

/**
 * ServerRule carries url and checkReachableHost.
 */
data class ServerRule(
    val url: String,
    val checkReachableHost: String?,
)

/**
 * HotUpdateData carries isNeedUpdate, targetVersion, updateInfo, and jarFileInfos.
 */
data class HotUpdateData(
    val isNeedUpdate: Boolean,
    val targetVersion: String,
    val updateInfo: NotificationData?,
    val jarFileInfos: List<JarFileInfo>,
    val isNeedReinstall: Boolean,
    val standaloneJarFileInfos: List<JarFileInfo>? = null,
    val standaloneBundleFileInfo: JarFileInfo? = null,
    val releaseBuildId: String? = null,
    val releaseChannel: String? = null,
)

/**
 * JarFileInfo carries uniqueName, url, and md5.
 */
data class JarFileInfo(
    val uniqueName: String,
    val url: String,
    val md5: String,
)

/**
 * CustomCompilerInfo carries jarFileName, path, and md5.
 */
data class CustomCompilerInfo(
    val jarFileName: String,
    val path: String,
    val md5: String,
)

/**
 * InteractionProcessFlow carries stepList, firstStep, token, and quitUrl.
 */
data class InteractionProcessFlow(
    val stepList: List<InteractionStepDesc>,
    val firstStep: InteractionStep,
    val token: String,
    val quitUrl: String,
)

/**
 * InteractionStepDesc carries stepName.
 */
data class InteractionStepDesc(
    val stepName: String,
)

/**
 * InteractionStep carries stepName, title, htmlText, and nextStepUrl.
 */
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

/**
 * RemoteServerInfo carries remoteSshUser, remoteSshPassword, remoteSshIp, and remoteSshPort.
 */
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
