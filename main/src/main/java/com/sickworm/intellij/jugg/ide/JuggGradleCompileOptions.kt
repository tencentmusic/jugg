package com.sickworm.intellij.jugg.ide;

import com.intellij.execution.configurations.RunConfigurationOptions
import com.intellij.openapi.components.StoredPropertyBase

class JuggGradleCompileOptions(private val projectName: String): RunConfigurationOptions(
) {

    @Suppress("UNCHECKED_CAST")
    private fun stringNotNull(defaultValue: String): StoredPropertyBase<String> =
        string(defaultValue) as StoredPropertyBase<String>

    var compileCommand by stringNotNull(Default.compileCommand)
    var outputApkName by stringNotNull(Default.outputApkName)
    var isRemoteCompile by property(Default.isRemoteCompile)
    var remoteSshUser by stringNotNull(Default.remoteSshUser)
    var remoteSshPassword by stringNotNull(Default.remoteSshPassword)
    var remoteSshIp by stringNotNull(Default.remoteSshIp)
    var remoteSshPort by property(Default.remoteSshPort)
    var localToRemoteIftConfigName by stringNotNull(Default.localToRemoteIftConfigName)
    var remoteToLocalIftConfigName by stringNotNull(Default.remoteToLocalIftConfigName)
    var remoteToLocalSyncPath by stringNotNull(Default.remoteToLocalSyncPath)
    var httpProxyIp by stringNotNull(Default.httpProxyIp)
    var httpProxyPort by property(Default.httpProxyPort)

    /** ift path, not local path */
    val localProjectIftPath get() = "$localToRemoteIftConfigName/$projectName"
    val remoteProjectPath get() = "/root/remote/$projectName"

    val remoteToLocalClasspathPath get() = "$remoteToLocalIftConfigName/jugg/$projectName"

    fun reset() {
        compileCommand = Default.compileCommand
        outputApkName = Default.outputApkName
        isRemoteCompile = Default.isRemoteCompile
        remoteSshUser = Default.remoteSshUser
        remoteSshPassword = Default.remoteSshPassword
        remoteSshIp = Default.remoteSshIp
        remoteSshPort = Default.remoteSshPort
        localToRemoteIftConfigName = Default.localToRemoteIftConfigName
        remoteToLocalIftConfigName = Default.remoteToLocalIftConfigName
        remoteToLocalSyncPath = Default.remoteToLocalSyncPath
        httpProxyIp = Default.httpProxyIp
        httpProxyPort = Default.httpProxyPort
    }

    private companion object Default {
        private const val compileCommand = "./gradlew :app:assembleDebug"
        private const val outputApkName = "app-universal-debug.apk"
        private const val isRemoteCompile = false
        private const val remoteSshUser = "root"
        private const val remoteSshPassword = ""
        private const val remoteSshIp = ""
        private const val remoteSshPort = 36000
        private const val localToRemoteIftConfigName = "remote"
        private const val remoteToLocalIftConfigName = "local"
        private const val remoteToLocalSyncPath = ""
        private const val httpProxyIp = "127.0.0.1"
        private const val httpProxyPort = 12639
    }
}

