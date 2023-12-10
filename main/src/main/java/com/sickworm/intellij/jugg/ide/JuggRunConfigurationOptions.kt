package com.sickworm.intellij.jugg.ide

import com.intellij.execution.configurations.RunConfigurationOptions
import com.sickworm.intellij.jugg.gradle.compile.isChild
import com.sickworm.intellij.jugg.project.JuggException
import java.io.File

/**
 * Implementation of [RunConfigurationOptions], which is the bean of config content.
 */
class JuggRunConfigurationOptions: RunConfigurationOptions() {

    var compileCommand by string()
    var outputApkName by string()
    var isRemoteCompile by property(false)
    var remoteSshUser by string()
    var remoteSshPassword by string()
    var remoteSshIp by string()
    var remoteSshPort by property(0)
    var localToRemoteIftConfigName by string()
    var localToRemoteSyncPath by string()
    var remoteSyncPath by string()
    var remoteToLocalIftConfigName by string()
    var remoteToLocalSyncPath by string()
    var httpProxyIp by string()
    var httpProxyPort by property(0)
    var isSyncAllProjects by property(false)
    // used to recognize whether default value has set
    // can not set default value in the string() or property(), value will be reset to default when default value is changed.
    var hasSetDefaultValue by property(false)

    // new options must add to the end because property persist is in order

    fun setToDefault() {
        compileCommand = JuggSettings.defaultCompileCommand
        outputApkName = JuggSettings.defaultOutputApkName
        isRemoteCompile = JuggSettings.defaultIsRemoteCompile
        remoteSshUser = JuggSettings.defaultRemoteSshUser
        remoteSshPassword = JuggSettings.defaultRemoteSshPassword
        remoteSshIp = JuggSettings.defaultRemoteSshIp
        remoteSshPort = JuggSettings.defaultRemoteSshPort
        localToRemoteIftConfigName = JuggSettings.defaultLocalToRemoteIftConfigName
        localToRemoteSyncPath = JuggSettings.defaultLocalToRemoteSyncPath
        remoteSyncPath = JuggSettings.defaultRemoteSyncPath
        remoteToLocalIftConfigName = JuggSettings.defaultRemoteToLocalIftConfigName
        remoteToLocalSyncPath = JuggSettings.defaultRemoteToLocalSyncPath
        httpProxyIp = JuggSettings.defaultHttpProxyIp
        httpProxyPort = JuggSettings.defaultHttpProxyPort
        isSyncAllProjects = JuggSettings.defaultIsSyncAllProjects
        hasSetDefaultValue = true
    }
}

/**
 * Wrapper of [JuggRunConfigurationOptions], which is used for compilation.
 */
data class JuggGradleCompileOptions(
    val projectRootPath: String,
    val compileCommand: String,
    val outputApkName: String,
    val isRemoteCompile: Boolean,
    val isSyncAllProjects: Boolean,
    val remoteSshUser: String,
    val remoteSshPassword: String,
    val remoteSshIp: String,
    val remoteSshPort: Int,
    val localToRemoteIftConfigName: String,
    val localToRemoteSyncPath: String,
    val remoteSyncPath: String,
    val remoteToLocalIftConfigName: String,
    val remoteToLocalSyncPath: String,
    val httpProxyIp: String,
    val httpProxyPort: Int,
) {


    private val projectSyncRelativePath get() = File(projectRootPath)
        .relativeTo(File(localToRemoteSyncPath)).path
        .replace(" ", "\\ ")

    private val projectSyncRootRelativePath: String get() = projectSyncRelativePath.substringBefore(File.separatorChar)

    /** remote home directory */
    private val remoteHomePath = if (remoteSshUser == "root") "/root" else "/data/home/$remoteSshUser"

    /** project storage directory */
    private val finalRemoteSyncPath = remoteSyncPath.ifEmpty { "$remoteHomePath/$localToRemoteIftConfigName" }

    /** local iFt path, used for syncing files to remote by iFt */
    val localSyncIftPath get() = if (isSyncAllProjects) {
        localToRemoteIftConfigName
    } else {
        "$localToRemoteIftConfigName/$projectSyncRootRelativePath"
    }

    /** remote project sync path, used for syncing files to remote by iFt, and fetching classpath */
    val remoteSyncRootPath get() = if (isSyncAllProjects) {
        finalRemoteSyncPath
    } else {
        "$finalRemoteSyncPath/$projectSyncRootRelativePath"
    }

    /** remote project root path, used for compilation */
    val remoteProjectPath get() = "$finalRemoteSyncPath/$projectSyncRelativePath"

    /** remote iFt path, used for fetching apk output to local */
    val remoteToLocalProjectIftPath get() = "$remoteToLocalIftConfigName/$projectSyncRelativePath"

    /** remote iFt path, used for fetching classpath output to local */
    val remoteToLocalRootIftPath get() = if (isSyncAllProjects) {
        "$remoteToLocalIftConfigName/jugg_all_classpath"
    } else {
        "$remoteToLocalIftConfigName/$projectSyncRootRelativePath"
    }

    /** local apk path, used for get apk output */
    val remoteToLocalProjectSyncPath get() = "$remoteToLocalSyncPath/$projectSyncRelativePath"

    /** local classpath path, used for get classpath output */
    val remoteToLocalSyncClasspathPath get() = if (isSyncAllProjects) {
        "$remoteToLocalSyncPath/jugg_all_classpath/${File(finalRemoteSyncPath).name}/$projectSyncRelativePath"
    } else {
        "$remoteToLocalSyncPath/$projectSyncRootRelativePath/$projectSyncRelativePath"
    }

    fun checkConfig() {
        var errorDetails = ""

        if (compileCommand.isEmpty()) {
            errorDetails += "Run configuration argument [Compile command] is empty\n"
        }
        if (outputApkName.isEmpty()) {
            errorDetails += "Run configuration argument [Output apk name] is empty\n"
        }
        if (isRemoteCompile) {
            if (remoteSshUser.isEmpty()) {
                errorDetails += "Run configuration argument [SSH user] is empty\n"
            }
            if (remoteSshIp.isEmpty()) {
                errorDetails += "Run configuration argument [SSH host] is empty\n"
            }
            if (remoteSshPort <= 0) {
                errorDetails += "Run configuration argument [SSH port] is invalid\n"
            }
            if (localToRemoteIftConfigName.isEmpty()) {
                errorDetails += "Run configuration argument [Local to remote IFT config] name is empty\n"
            }
            if (localToRemoteSyncPath.isEmpty()) {
                errorDetails += "Run configuration argument [Local to remote sync path] is empty\n"
            }
            if (remoteToLocalIftConfigName.isEmpty()) {
                errorDetails += "Run configuration argument [Remote to local IFT config] name is empty\n"
            }
            if (remoteToLocalSyncPath.isEmpty()) {
                errorDetails += "Run configuration argument [Remote to local sync path] is empty\n"
            }
            if (!File(projectRootPath).isChild(File(localToRemoteSyncPath))) {
                errorDetails += "Run configuration argument [Local to remote IFT sync path]($localToRemoteSyncPath) " +
                        "must be the parent of project path($projectRootPath)\n"
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
                options.isSyncAllProjects,
                options.remoteSshUser ?: "",
                options.remoteSshPassword ?: "",
                options.remoteSshIp ?: "",
                options.remoteSshPort,
                options.localToRemoteIftConfigName ?: "",
                options.localToRemoteSyncPath ?: "",
                options.remoteSyncPath ?: "",
                options.remoteToLocalIftConfigName ?: "",
                options.remoteToLocalSyncPath ?: "",
                options.httpProxyIp ?: "",
                options.httpProxyPort,
            )
        }
    }
}

