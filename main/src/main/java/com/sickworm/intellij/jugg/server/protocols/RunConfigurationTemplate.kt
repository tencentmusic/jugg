package com.sickworm.intellij.jugg.server.protocols

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
) {
    companion object {

        val default = RunConfigurationTemplate(
            "Default",
            "./gradlew :app:assembleDebug",
            "app-*debug.apk",
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
            "iFt",
        )
    }
}
