package com.sickworm.intellij.jugg.server

import com.google.gson.TypeAdapter
import com.google.gson.reflect.TypeToken
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonWriter
import com.sickworm.intellij.jugg.ide.bean.JuggGradleCompileOptions
import com.sickworm.intellij.jugg.server.protocols.RunConfigurationTemplate
import java.lang.reflect.Type

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
        environmentVariables = options.environmentVariables,
        syncMode = options.syncMode.modeName,
    )
}


val RunConfigurationTemplate.Companion.listType: Type get() = object : TypeToken<List<RunConfigurationTemplate>>() {}.type

private val userHome = System.getProperty("user.home")

val RunConfigurationTemplate.Companion.typeAdapter get() = object : TypeAdapter<String?>() {
    override fun write(p0: JsonWriter?, p1: String?) {
        p0?.value(p1)
    }

    override fun read(p0: JsonReader?): String? {
        val string = p0?.nextString()
        return string?.replace("\$HOME", userHome)
    }
}