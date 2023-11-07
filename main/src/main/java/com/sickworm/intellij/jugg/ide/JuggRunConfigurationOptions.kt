package com.sickworm.intellij.jugg.ide

import com.intellij.execution.configurations.RunConfigurationOptions
import com.sickworm.intellij.jugg.gradle.compile.isChild
import com.sickworm.intellij.jugg.project.JuggException
import java.io.File

/**
 * Implementation of [RunConfigurationOptions], which is the bean of config content.
 */
class JuggRunConfigurationOptions: RunConfigurationOptions() {

    var compileCommand by string(JuggSettings.defaultCompileCommand)
    var outputApkName by string(JuggSettings.defaultOutputApkName)
    var isRemoteCompile by property(JuggSettings.defaultIsRemoteCompile)
    var remoteSshUser by string(JuggSettings.defaultRemoteSshUser)
    var remoteSshPassword by string(JuggSettings.defaultRemoteSshPassword)
    var remoteSshIp by string(JuggSettings.defaultRemoteSshIp)
    var remoteSshPort by property(JuggSettings.defaultRemoteSshPort)
    var localToRemoteIftConfigName by string(JuggSettings.defaultLocalToRemoteIftConfigName)
    var localToRemoteSyncPath by string(JuggSettings.defaultLocalToRemoteSyncPath)
    var remoteToLocalIftConfigName by string(JuggSettings.defaultRemoteToLocalIftConfigName)
    var remoteToLocalSyncPath by string(JuggSettings.defaultRemoteToLocalSyncPath)
    var httpProxyIp by string(JuggSettings.defaultHttpProxyIp)
    var httpProxyPort by property(JuggSettings.defaultHttpProxyPort)

}

/**
 * Wrapper of [JuggRunConfigurationOptions], which is used for compilation.
 */
data class JuggGradleCompileOptions(
    val projectRootPath: String,
    val compileCommand: String,
    val outputApkName: String,
    val isRemoteCompile: Boolean,
    val remoteSshUser: String,
    val remoteSshPassword: String,
    val remoteSshIp: String,
    val remoteSshPort: Int,
    val localToRemoteIftConfigName: String,
    val localToRemoteSyncPath: String,
    val remoteToLocalIftConfigName: String,
    val remoteToLocalSyncPath: String,
    val httpProxyIp: String,
    val httpProxyPort: Int,
) {


    private val projectSyncRelativePath get() = File(projectRootPath)
        .relativeTo(File(localToRemoteSyncPath)).path
        .replace(" ", "\\ ")

    private val projectSyncRootRelativePath: String get() = projectSyncRelativePath.substringBefore(File.separatorChar)


    /** local iFt path, used for syncing files to remote by iFt */
    val localSyncIftPath get() = "$localToRemoteIftConfigName/$projectSyncRootRelativePath"

    /** remote project sync path, used for syncing files to remote by iFt, and fetching classpath */
    val remoteSyncRootPath get() = "/root/$localToRemoteIftConfigName/$projectSyncRootRelativePath"

    /** remote project root path, used for compilation */
    val remoteProjectPath get() = "/root/$localToRemoteIftConfigName/$projectSyncRelativePath" // use ~/ will make path replacement don't work

    /** remote iFt path, used for fetching apk output to local */
    val remoteToLocalProjectIftPath get() = "$remoteToLocalIftConfigName/$projectSyncRelativePath"

    /** remote iFt path, used for fetching classpath output to local */
    val remoteToLocalRootIftPath get() = "$remoteToLocalIftConfigName/$projectSyncRootRelativePath"

    /** local apk path, used for get apk output */
    val remoteToLocalProjectSyncPath get() = "$remoteToLocalSyncPath/$projectSyncRelativePath"

    /** local classpath path, used for get classpath output */
    val remoteToLocalSyncClasspathPath get() = "$remoteToLocalSyncPath/$projectSyncRootRelativePath/$projectSyncRelativePath"

    fun checkConfig() {
        var errorDetails = ""

        if (compileCommand.isEmpty()) {
            errorDetails = "Compile command is empty"
        } else if (outputApkName.isEmpty()) {
            errorDetails = "Output apk name is empty"
        } else if (isRemoteCompile) {
            if (remoteSshUser.isEmpty()) {
                errorDetails = "SSH user is empty"
            } else if (remoteSshPassword.isEmpty()) {
                errorDetails = "SSH password is empty"
            } else if (remoteSshIp.isEmpty()) {
                errorDetails = "SSH IP is empty"
            } else if (remoteSshPort <= 0) {
                errorDetails = "SSH port is invalid"
            } else if (localToRemoteIftConfigName.isEmpty()) {
                errorDetails = "Local to remote IFT config name is empty"
            } else if (localToRemoteSyncPath.isEmpty()) {
                errorDetails = "Local to remote sync path is empty"
            } else if (remoteToLocalIftConfigName.isEmpty()) {
                errorDetails = "Remote to local IFT config name is empty"
            } else if (remoteToLocalSyncPath.isEmpty()) {
                errorDetails = "Remote to local sync path is empty"
            } else if (!File(projectRootPath).isChild(File(localToRemoteSyncPath))) {
                errorDetails = "Project path($projectRootPath) must be the parent of " +
                        "localToRemoteSyncPath($localToRemoteSyncPath) which specified in run configuration"
            }
        }

        if (errorDetails.isNotEmpty()) {
            throw JuggException.runConfigInvalid(errorDetails)
        }
    }

    companion object {

        fun fromOptions(
            projectRootPath: String,
            options: JuggRunConfigurationOptions
        ): JuggGradleCompileOptions {
            return JuggGradleCompileOptions(
                projectRootPath,
                options.compileCommand ?: "",
                options.outputApkName ?: "",
                options.isRemoteCompile,
                options.remoteSshUser ?: "",
                options.remoteSshPassword ?: "",
                options.remoteSshIp ?: "",
                options.remoteSshPort,
                options.localToRemoteIftConfigName ?: "",
                options.localToRemoteSyncPath ?: "",
                options.remoteToLocalIftConfigName ?: "",
                options.remoteToLocalSyncPath ?: "",
                options.httpProxyIp ?: "",
                options.httpProxyPort,
            )
        }
    }
}

