package com.sickworm.intellij.jugg.server

import com.sickworm.intellij.jugg.ide.JuggRunConfigurationOptions
import com.sickworm.intellij.jugg.server.protocols.RunConfigurationTemplate

fun JuggRunConfigurationOptions.toRunConfigurationTemplate(): RunConfigurationTemplate {
    val options = this
    return RunConfigurationTemplate(
        templateName = "Default",
        compileCommand = options.compileCommand,
        outputApkName = options.outputApkName,
        isRemoteCompile = options.isRemoteCompile,
        isSyncAllProjects = options.isSyncAllProjects,
        remoteSshUser = options.remoteSshUser,
        remoteSshIp = options.remoteSshIp,
        remoteSshPassword = options.remoteSshPassword,
        remoteSshPort = options.remoteSshPort,
        localToRemoteIftConfigName = options.localToRemoteIftConfigName,
        localToRemoteSyncPath = options.localToRemoteSyncPath,
        remoteSyncPath = options.remoteSyncPath,
        remoteToLocalIftConfigName = options.remoteToLocalIftConfigName,
        remoteToLocalSyncPath = options.remoteToLocalSyncPath,
        httpProxyIp = options.httpProxyIp,
        httpProxyPort = options.httpProxyPort,
        syncMode = options.syncMode,
    )
}