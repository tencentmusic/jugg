package com.sickworm.intellij.jugg.remote

import com.sickworm.intellij.jugg.project.JuggException
import kotlin.jvm.Throws

data class RemoteCompileClientInfo(
    val user: String,
    val password: String,
    val ip: String,
    val port: Int,
    val syncPathName: String,
    val projectName: String,
    val httpProxyIp: String? = null,
    val httpProxyPort: Int? = null,
) {

    val localProjectPath get() = "$syncPathName/$projectName"
    val serverProjectPath get() = "~/remote/$projectName"

}

interface IRemoteClient {

    @Throws(JuggException::class)
    fun login(clientInfo: RemoteCompileClientInfo)

    @Throws(JuggException::class)
    fun compileAndFetchResult()
}