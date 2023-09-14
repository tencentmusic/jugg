package com.sickworm.intellij.jugg.ide;

import com.intellij.execution.configurations.RunConfigurationOptions

class JuggRunConfigurationOptions: RunConfigurationOptions() {

    var compileCommand by string(JuggSettings.defaultCompileCommand)
    var outputApkName by string(JuggSettings.defaultOutputApkName)
    var isRemoteCompile by property(JuggSettings.defaultIsRemoteCompile)
    var remoteSshUser by string(JuggSettings.defaultRemoteSshUser)
    var remoteSshPassword by string(JuggSettings.defaultRemoteSshPassword)
    var remoteSshIp by string(JuggSettings.defaultRemoteSshIp)
    var remoteSshPort by property(JuggSettings.defaultRemoteSshPort)
    var localToRemoteIftConfigName by string(JuggSettings.defaultLocalToRemoteIftConfigName)
    var remoteToLocalIftConfigName by string(JuggSettings.defaultRemoteToLocalIftConfigName)
    var remoteToLocalSyncPath by string(JuggSettings.defaultRemoteToLocalSyncPath)
    var httpProxyIp by string(JuggSettings.defaultHttpProxyIp)
    var httpProxyPort by property(JuggSettings.defaultHttpProxyPort)

}

data class JuggGradleCompileOptions(
    val projectName: String,
    val compileCommand: String,
    val outputApkName: String,
    val isRemoteCompile: Boolean,
    val remoteSshUser: String,
    val remoteSshPassword: String,
    val remoteSshIp: String,
    val remoteSshPort: Int,
    val localToRemoteIftConfigName: String,
    val remoteToLocalIftConfigName: String,
    val remoteToLocalSyncPath: String,
    val httpProxyIp: String,
    val httpProxyPort: Int,
) {

    private val projectNameHandleWhiteSpace = projectName.replace(" ", "\\ ")

    /** ift path, not local path */
    val localProjectIftPath get() = "$localToRemoteIftConfigName/$projectNameHandleWhiteSpace"
    val remoteProjectPath get() = "/root/remote/$projectNameHandleWhiteSpace"
    val remoteToLocalProjectIftPath get() = "$remoteToLocalIftConfigName/$projectNameHandleWhiteSpace"

    val remoteToLocalProjectSyncPath get() = "$remoteToLocalSyncPath/$projectNameHandleWhiteSpace"

    val remoteToLocalProjectSyncClasspathPath get() = "$remoteToLocalSyncPath/$projectNameHandleWhiteSpace/$projectNameHandleWhiteSpace"

    companion object {

        fun fromOptions(
            projectRootDirName: String,
            options: JuggRunConfigurationOptions
        ): JuggGradleCompileOptions {

            return JuggGradleCompileOptions(
                projectRootDirName,
                options.compileCommand ?: "",
                options.outputApkName ?: "",
                options.isRemoteCompile,
                options.remoteSshUser ?: "",
                options.remoteSshPassword ?: "",
                options.remoteSshIp ?: "",
                options.remoteSshPort,
                options.localToRemoteIftConfigName ?: "",
                options.remoteToLocalIftConfigName ?: "",
                options.remoteToLocalSyncPath ?: "",
                options.httpProxyIp ?: "",
                options.httpProxyPort,
            )
        }
    }
}

