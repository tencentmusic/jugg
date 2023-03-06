package com.sickworm.intellij.jugg.ide;

data class GradleCompileSettings(
    val projectName: String,
    val compileCommand: String,
    val targetApkName: String,
    val isRemoteCompile: Boolean,
    val remoteClientInfo: RemoteGradleCompileClientInfo,
)

data class RemoteGradleCompileClientInfo(
    val projectName: String,
    val user: String,
    val password: String,
    val ip: String,
    val port: Int,
    val localToRemoteIftConfigName: String,
    val remoteToLocalIftConfigName: String,
    val remoteToLocalSyncPath: String,
    val httpProxyIp: String? = null,
    val httpProxyPort: Int? = null,
) {

    /** ift path, not local path */
    val localProjectIftPath get() = "$localToRemoteIftConfigName/$projectName"
    val remoteProjectPath get() = "/root/remote/$projectName"

    val remoteToLocalClasspathPath get() = "$remoteToLocalIftConfigName/jugg/$projectName"

    companion object {
        fun createEmpty(): RemoteGradleCompileClientInfo {
            return RemoteGradleCompileClientInfo(
                "", "", "", "", 0,
                "", "", ""
            )
        }
    }
}
