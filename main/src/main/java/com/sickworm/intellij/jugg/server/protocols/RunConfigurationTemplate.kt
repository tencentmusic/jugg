package com.sickworm.intellij.jugg.server.protocols

/**
 * RunConfigurationTemplate carries templateName, compileCommand, outputApkName, and isRemoteCompile.
 */
data class RunConfigurationTemplate(
    val templateName: String,
    val compileCommand: String?,
    val outputApkName: String?,
    val isRemoteCompile: Boolean,
    val remoteSshUser: String?,
    val remoteSshPassword: String?,
    val remoteSshIp: String?,
    val remoteSshPort: Int,
    val localToRemoteIftConfigName: String?,
    val localToRemoteSyncPath: String?,
    val remoteSyncPath: String?,
    val remoteToLocalIftConfigName: String?,
    val remoteToLocalSyncPath: String?,
    val httpProxyIp: String?,
    val httpProxyPort: Int,
    val isSyncAllProjects: Boolean,
    val syncMode: String?,
    val environmentVariables: String?,
    val remoteSyncExcludePatterns: String? = null,
) {
    companion object {

        val default = RunConfigurationTemplate(
            "Default",
            "",
            "",
            false,
            "",
            "",
            "",
            0,
            "",
            "",
            "",
            "",
            "",
            "",
            0,
            false,
            "rsync_simple",
            null,
        )
    }
}
