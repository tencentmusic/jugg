package com.sickworm.intellij.jugg.ide;

import com.intellij.execution.configurations.RunConfigurationOptions

class JuggRunConfigurationOptions: RunConfigurationOptions() {

    var compileCommand by string(Default.compileCommand)
    var outputApkName by string(Default.outputApkName)
    var isRemoteCompile by property(Default.isRemoteCompile)
    var remoteSshUser by string(Default.remoteSshUser)
    var remoteSshPassword by string(Default.remoteSshPassword)
    var remoteSshIp by string(Default.remoteSshIp)
    var remoteSshPort by property(Default.remoteSshPort)
    var localToRemoteIftConfigName by string(Default.localToRemoteIftConfigName)
    var remoteToLocalIftConfigName by string(Default.remoteToLocalIftConfigName)
    var remoteToLocalSyncPath by string(Default.remoteToLocalSyncPath)
    var httpProxyIp by string(Default.httpProxyIp)
    var httpProxyPort by property(Default.httpProxyPort)

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
    val remoteToLocalClasspathPath get() = "$remoteToLocalIftConfigName/jugg/$projectNameHandleWhiteSpace"

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

