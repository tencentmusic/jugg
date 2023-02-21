package com.sickworm.intellij.jugg.remote

import com.sickworm.intellij.jugg.project.JuggException
import java.io.File
import kotlin.jvm.Throws

data class RemoteCompileClientInfo(
    val projectName: String,
    val user: String,
    val password: String,
    val ip: String,
    val port: Int,
    val localToRemoteIftConfigName: String,
    val remoteToLocalIftConfigName: String,
    val remoteToLocalSyncPath: String,
    val httpProxyIp: String? = null,
    val httpProxyPort: Int? = null,
) {

    val localProjectPath get() = "$localToRemoteIftConfigName/$projectName"
    val serverProjectPath get() = "~/remote/$projectName"

}

data class RemoteCompileResult(
    val isSuccess: Boolean,
    val compileOutputFile: File,
) {
    companion object {
        fun failed() = RemoteCompileResult(false, File(""))

        fun success(outputDir: File) = RemoteCompileResult(true, outputDir)
    }
}

interface IRemoteClient {

    @Throws(JuggException::class)
    fun login(clientInfo: RemoteCompileClientInfo)

    fun compileAndFetchResult() : RemoteCompileResult
}