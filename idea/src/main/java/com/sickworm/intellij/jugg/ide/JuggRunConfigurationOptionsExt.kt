package com.sickworm.intellij.jugg.ide

import com.sickworm.intellij.jugg.project.JuggPathManager

fun JuggRunConfigurationOptions.toCompileOptions(
    pathManager: JuggPathManager,
): JuggGradleCompileOptions {
    val options = this
    return JuggGradleCompileOptions(
        projectRootPath = pathManager.projectDir.absolutePath,
        localClasspathStoragePath = pathManager.localClasspathStoragePathManager,
        initGradleFileRelativePath = pathManager.initGradleFileRelativePath,
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
    )
}