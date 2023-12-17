package com.sickworm.intellij.jugg.server

import com.google.gson.TypeAdapter
import com.google.gson.reflect.TypeToken
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonWriter
import com.sickworm.intellij.jugg.ide.JuggGradleCompileOptions
import com.sickworm.intellij.jugg.ide.JuggRunConfigurationOptions
import java.lang.reflect.Type

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
) {
    companion object {

        val listType: Type = object : TypeToken<List<RunConfigurationTemplate>>() {}.type

        private val userHome = System.getProperty("user.home")

        val typeAdapter = object : TypeAdapter<String?>() {
            override fun write(p0: JsonWriter?, p1: String?) {
                p0?.value(p1)
            }

            override fun read(p0: JsonReader?): String? {
                val string = p0?.nextString()
                return string?.replace("\$HOME", userHome)
            }
        }

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
        )
    }
}

fun JuggGradleCompileOptions.toRunConfigurationTemplate(): RunConfigurationTemplate {
    val options = this
    return RunConfigurationTemplate.default.copy(
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
    )
}