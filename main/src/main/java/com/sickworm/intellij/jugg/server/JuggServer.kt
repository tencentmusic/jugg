package com.sickworm.intellij.jugg.server

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.sickworm.intellij.jugg.ide.bean.JuggSettings
import com.sickworm.intellij.jugg.logger.JuggLogger
import com.sickworm.intellij.jugg.platform.PlatformApi
import com.sickworm.intellij.jugg.project.JuggPathManager
import com.sickworm.intellij.jugg.server.protocols.HotUpdateData
import com.sickworm.intellij.jugg.server.protocols.ServerRule
import com.sickworm.intellij.jugg.server.protocols.VersionData
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Request

import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.IOException
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream


/**
 * Server API for Jugg
 * 1. report event to jugg backend
 * 2. check update
 * 3. upload logs
 * 4. popup action
 * 5. get run configuration templates
 * 6. get project custom config
 */
class JuggServer(
    private val project: Project,
    private val pathManager: JuggPathManager,
    private val coroutineScope: CoroutineScope,
): CoroutineScope by coroutineScope {

    private var logger: Logger = JuggLogger.getInstance(project, "JuggServer")

    private val juggServerChooser = JuggServerChooser(logger)
    private val serverUrl: String? get() = JuggSettings.serverUrl
    private val reportEventUrl get() = "$serverUrl/report_event"
    private val checkUpdateUrl get() = "$serverUrl/check_update"
    private val reportIssueUrl get() = "$serverUrl/report_issue"
    private val checkHotUpdateUrl get() = "$serverUrl/check_hot_update"


    private val username: String = getUserName()

    val version: String = PlatformApi.pluginVersion

    private val projectId: String by lazy { getName(project.name) }
    private val requestToken = (project.basePath + "_" + username).md5.substring(0, 8)

    private var sessionId: Int = 1
    private var sessionSubId: Int = 0

    private val client = OkHttpClient()

    init {
        logger.debug("init finished, version: $version, projectId: $projectId, userName: $username, requestToken: $requestToken, serverUrl: $serverUrl")
    }

    fun afterFullCompile() {
        sessionId += 1
    }

    fun onCompile() {
        sessionSubId += 1
    }

    private var reportLock = Mutex() // report only one event in the same time

    fun checkUpdate(onComplete: (VersionData) -> Unit) = launch {
        if (serverUrl == null) {
            logger.debug("checkUpdate skip: serverUrl is null")
            return@launch
        }

        try {
            val request: Request = Request.Builder()
                .url("$checkUpdateUrl?version=$version&requestToken=$requestToken&projectName=${URLEncoder.encode(projectId, "UTF-8")}")
                .get()
                .build()

            val response = client.newCall(request).execute()
            val body = response.body?.string()
            logger.debug("check update response: [${response.code}] $body")

            val result = Gson().fromJson(body, VersionData::class.java)
            onComplete.invoke(result)
        } catch (e: Exception) {
            logger.debug("check update error: ${e.message}")
        }
    }

    private val String.md5: String get() = MessageDigest.getInstance("MD5").digest(this.toByteArray()).toHex()
    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    fun report(fill: ReportEventData.() -> Unit): Job {
        val data = ReportEventData().fillCommonData()
        fill(data)
        return launch {
            reportLock.withLock {
                doReport(data)
            }
        }
    }

    fun report(data: ReportEventData): Job {
        data.fillCommonData()
        return launch {
            reportLock.withLock {
                doReport(data)
            }
        }
    }

    fun reportAndUploadLogs(logcatErrorLog: String): Deferred<UploadResult> {
        return async {
            logger.debug("reportAndUploadLogs start")
            // zip log directory
            val fileName = "${requestToken}_${System.currentTimeMillis()}".md5.substring(0, 8)
            pathManager.tmpDir.mkdirs()
            val destFile = File(pathManager.tmpDir, "$fileName.zip")
            try {
                logger.debug("reportAndUploadLogs destFile: $destFile")
                val logDir = pathManager.logDir
                if (!logDir.exists()) {
                    val errorMessage = "log dir not exists: ${logDir.absolutePath}"
                    logger.warn(errorMessage)
                    return@async UploadResult.fail(errorMessage)
                }
                val logFiles = logDir.listFiles()?.filter {
                    !it.name.startsWith("compile_latest") && !it.name.endsWith(".lck")
                } ?: emptyList()

                logger.debug("start dump logcatErrorLogs")
                val logcatFile = File(pathManager.tmpDir, "logcat.log")
                if (logcatFile.exists()) {
                    logcatFile.delete()
                }
                logcatFile.writeText(logcatErrorLog)
                logger.debug("dump logcatErrorLogs finished")

                zipTo(destFile, logFiles + logcatFile)
                val uploadResult = uploadFile(destFile)
                if (!uploadResult.isSuccess) {
                    logger.warn("reportAndUploadLogs failed: ${uploadResult.errorMessage}")
                } else {
                    logger.debug("reportAndUploadLogs success")
                }

                logcatFile.delete()
                return@async uploadResult.copy(reportId = fileName)
            } catch (e: Exception) {
                logger.warn("reportAndUploadLogs error", e)
                return@async UploadResult.fail(e.message ?: "Unknown exception")
            } finally {
                if (destFile.exists()) {
                    destFile.delete()
                }
            }
        }
    }

    private fun zipTo(destFile: File, sourceFiles: List<File>) {
        if (destFile.exists()) {
            destFile.delete()
        }
        destFile.createNewFile()

        destFile.outputStream().use { output ->
            ZipOutputStream(output).use { zip ->
                sourceFiles.forEach { sourceFile ->

                    fun writeZip(it: File, path: String) {
                        try {
                            zip.putNextEntry(ZipEntry(path))
                            it.inputStream().use { input ->
                                input.copyTo(zip)
                            }
                        } catch (e: Exception) {
                            // exception when zip .lck on windows, just ignore
                            logger.warn("add zip entry $it failed", e)
                        }
                    }

                    if (sourceFile.isDirectory) {
                        sourceFile.listFiles()?.forEach {
                            val path = it.relativeTo(sourceFile).path
                            writeZip(it, path)
                        }
                    } else if (sourceFile.isFile) {
                        val path = sourceFile.name
                        writeZip(sourceFile, path)
                    }
                }
            }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun uploadFile(file: File) = suspendCancellableCoroutine { continuation ->
        if (serverUrl == null) {
            logger.debug("upload file skip: serverUrl is null")
            continuation.resume(UploadResult.fail("serverUrl is null"), null)
            return@suspendCancellableCoroutine
        }

        val builder = MultipartBody.Builder()
        builder.setType(MultipartBody.FORM)
        builder.addFormDataPart("file" , file.name, file.asRequestBody("application/zip".toMediaTypeOrNull()))
        val requestBody = builder.build()
        val request = Request.Builder()
            .url(reportIssueUrl)
            .post(requestBody)
            .build()
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                val errorMessage = "upload file failed: ${e.message}"
                logger.warn(errorMessage)
                if (continuation.isActive) {
                    continuation.resume(UploadResult.fail(errorMessage), null)
                }
            }

            override fun onResponse(call: Call, response: Response) {
                val message = "[${response.code}] ${response.body?.string()}"
                logger.debug("upload file response: $message")
                if (continuation.isActive) {
                    if (response.code != 200) {
                        continuation.resume(UploadResult.fail("Upload file failed: $message"), null)
                    } else {
                        continuation.resume(UploadResult.success("null"), null)
                    }
                }
            }
        })
    }


    private fun ReportEventData.fillCommonData(): ReportEventData {
        version = this@JuggServer.version
        ideVersion = PlatformApi.getIdeVersion()
        username = this@JuggServer.username
        projectId = this@JuggServer.projectId
        sessionId = "${this@JuggServer.sessionId}_${this@JuggServer.sessionSubId}"
        return this
    }

    private fun doReport(data: ReportEventData) {
        try {
            juggServerChooser.updateServerIfExpired()

            if (serverUrl == null) {
                logger.debug("report ${data.action} skip: serverUrl is null")
                return
            }

            val content = Gson().toJson(data)
            val request: Request = Request.Builder()
                .url(reportEventUrl)
                .post(content.toRequestBody("application/json".toMediaTypeOrNull()))
                .build()

            val response = client.newCall(request).execute()
            logger.debug("report ${data.action} response: [${response.code}] ${response.body?.string()}")
        } catch (e: Exception) {
            logger.debug("report ${data.action} failed: ${e.message}")
        }
    }

    private fun getName(defaultName: String): String {
        val gitManager = PlatformApi.createGitManagerAndTrySearchParent(pathManager.projectDir)
        if (!gitManager.hasInitGit) {
            return defaultName
        }
        return gitManager.name ?: defaultName
    }

    private fun getUserName(): String {
        val defaultName = System.getProperty("user.name") ?: "jugg_user_unknown"
        val projectDir = pathManager.projectDir
        return PlatformApi.createGitManagerAndTrySearchParent(projectDir).userName ?: defaultName
    }

    fun updateServer(servers: List<ServerRule>?) {
        launch {
            juggServerChooser.updateServer(servers)
        }
    }

    fun setCustomServer() {
        juggServerChooser.setCustomServer()
    }

    fun checkHotUpdate(isPositiveCheckout: Boolean): HotUpdateData? {
        try {
            if (serverUrl == null) {
                logger.debug("checkHotUpdate skip: serverUrl is null")
                return null
            }

            val request: Request = Request.Builder()
                .url("$checkHotUpdateUrl?version=$version" +
                        "&requestToken=$$requestToken" +
                        "&username=${URLEncoder.encode(username, "UTF-8")}" +
                        "&projectName=${URLEncoder.encode(projectId, "UTF-8")}" +
                        "&isPositiveCheckout=$isPositiveCheckout")
                .get()
                .build()

            val response = client.newCall(request).execute()
            val content = response.body?.string()
            logger.debug("checkHotUpdate response: [${response.code}] $content")
            return Gson().fromJson(content, HotUpdateData::class.java)
        } catch (e: Exception) {
            logger.debug("checkHotUpdate failed: $e")
            return null
        }
    }

    fun downloadFile(url: String, targetFile: File) {
        targetFile.parentFile.mkdirs()
        targetFile.delete()
        targetFile.createNewFile()
        val request = Request.Builder()
            .url(url)
            .get()
            .build()
        try {
            val result = client.newCall(request).execute()
            if (result.code != 200) {
                logger.debug("downloadFile failed: [${result.code}] ${result.body?.string()}")
                targetFile.delete()
                return
            }
            result.body?.byteStream()?.use { input ->
                targetFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            if (targetFile.length() == 0L) {
                logger.debug("downloadFile failed: file length is 0")
                targetFile.delete()
            }
        } catch (e: Exception) {
            logger.debug("downloadFile failed: $e")
            targetFile.delete()
        }
    }
}


data class ReportEventData(
    @SerializedName("version")
    var version: String,
    @SerializedName("ide_version")
    var ideVersion: String,
    @SerializedName("username")
    var username: String,
    @SerializedName("project_id")
    var projectId: String,
    @SerializedName("session_id")
    var sessionId: String,
    @SerializedName("action")
    var action: String,
    @SerializedName("is_success")
    var isSuccess: Boolean,
    @SerializedName("cost_time")
    var costTime: Long,
    @SerializedName("detail")
    var detail: String?
) {
    constructor() : this("", "", "", "", "", "", true, 0, null)
}

data class UploadResult(
    val isSuccess: Boolean,
    val errorMessage: String?,
    val reportId: String?,
) {
    companion object {
        fun success(reportId: String) = UploadResult(true, null, reportId)

        fun fail(errorMessage: String) = UploadResult(false, errorMessage, null)
    }
}