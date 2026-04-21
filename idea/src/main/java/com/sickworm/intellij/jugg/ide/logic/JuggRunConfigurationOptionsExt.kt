package com.sickworm.intellij.jugg.ide.logic

import com.sickworm.intellij.jugg.compiler.BuildTarget
import com.sickworm.intellij.jugg.ide.JuggRunConfigurationOptions
import com.sickworm.intellij.jugg.ide.bean.JuggGradleCompileOptions
import com.sickworm.intellij.jugg.ide.bean.SyncMode
import com.sickworm.intellij.jugg.project.JuggPathManager
import com.sickworm.intellij.jugg.server.protocols.RunConfigurationTemplate

fun JuggRunConfigurationOptions.toCompileOptions(
    pathManager: JuggPathManager,
): JuggGradleCompileOptions {
    val options = this
    return JuggGradleCompileOptions(
        projectRootPath = pathManager.projectDir.absolutePath,
        localClasspathStoragePath = pathManager.localClasspathStoragePathManager,
        initGradleFilePath = pathManager.initGradleFilePath.path,
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
        SyncMode.values().find { it.modeName == options.syncMode } ?: SyncMode.IFT,
        options.environmentVariables ?: "",
        buildTarget = if (options.enableAndroidTest) BuildTarget.ANDROID_TEST else BuildTarget.APP,
    )
}

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
        environmentVariables = options.environmentVariables,
    )
}


fun JuggRunConfigurationOptions.setDefaultRemoteOption(template: RunConfigurationTemplate) {
    isRemoteCompile = template.isRemoteCompile
    remoteSshUser = template.remoteSshUser
    remoteSshPassword = template.remoteSshPassword
    remoteSshIp = template.remoteSshIp
    remoteSshPort = template.remoteSshPort
    localToRemoteIftConfigName = template.localToRemoteIftConfigName
    localToRemoteSyncPath = template.localToRemoteSyncPath
    remoteSyncPath = template.remoteSyncPath
    remoteToLocalIftConfigName = template.remoteToLocalIftConfigName
    remoteToLocalSyncPath = template.remoteToLocalSyncPath
    httpProxyIp = template.httpProxyIp
    httpProxyPort = template.httpProxyPort
    isSyncAllProjects = template.isSyncAllProjects
    syncMode = template.syncMode
    environmentVariables = template.environmentVariables
}