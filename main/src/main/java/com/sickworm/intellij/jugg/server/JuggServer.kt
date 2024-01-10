package com.sickworm.intellij.jugg.server

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.sickworm.intellij.jugg.deploy.run.AsDeployerCompat
import com.sickworm.intellij.jugg.git.GitManager
import com.sickworm.intellij.jugg.ide.JuggInitializer
import com.sickworm.intellij.jugg.logger.JuggLogger
import com.sickworm.intellij.jugg.project.JuggPathManager
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
import java.security.MessageDigest
import java.util.*
import java.util.jar.Manifest
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
    project: Project,
    private val pathManager: JuggPathManager? = JuggInitializer.getManager(project)?.pathManager
): CoroutineScope by CoroutineScope(Dispatchers.IO) {

    companion object {

        private val properties: Properties = run {
            val cl = JuggServer::class.java.classLoader
            val properties = Properties()
            properties.load(cl.getResourceAsStream("config.properties"))
            return@run properties
        }

        private val serverUrl: String? = properties.getProperty("jugg.reportServer")
        private val reportEventUrl = "$serverUrl/report_event"
        private val checkUpdateUrl = "$serverUrl/check_update"
        private val reportIssueUrl = "$serverUrl/report_issue"

        private fun getVersion(): String {
            val cl = JuggServer::class.java.classLoader
            val manifest = Manifest(cl.getResourceAsStream("META-INF/MANIFEST.MF"))
            return manifest.mainAttributes.getValue("Version") ?: "unknown"
        }
    }

    private var logger: Logger = JuggLogger.getInstance(project, "JuggReporter")

    private val username: String = System.getProperty("user.name")

    // read Version in MANIFEST.MF
    val version: String by lazy(Companion::getVersion)

    private val projectId: String by lazy { getName(project.name) }
    private val requestToken = (project.basePath + "_" + username).md5.substring(0, 8)

    private var sessionId: Int = 1
    private var sessionSubId: Int = 0

    private val client = OkHttpClient()

    private val checkUpdateHandler = CheckUpdateHandler(project, version, JuggLogger.getInstance(project, "CheckUpdateHandler"))

    init {
        logger.debug("init finished, projectId: $projectId, userName: $username, requestToken: $requestToken, serverUrl: $serverUrl")
    }

    fun afterFullCompile() {
        sessionId += 1
    }

    fun onCompile() {
        sessionSubId += 1
    }

    private var reportLock = Mutex() // report only one event in the same time

    fun checkUpdate() = launch {
        if (serverUrl == null) {
            logger.debug("checkUpdate skip: serverUrl is null")
            return@launch
        }

        val result = try {
            val request: Request = Request.Builder()
                .url("$checkUpdateUrl?version=$version&requestToken=$requestToken")
                .get()
                .build()

            val response = client.newCall(request).execute()
            val body = response.body?.string()
            logger.debug("check update response: [${response.code}] $body")

            try {
                Gson().fromJson(body, VersionData::class.java)
            } catch (e: Exception) {
                logger.debug("check update error when parsing JSON: ${e.message}")
                VersionData.empty
            }
        } catch (e: Exception) {
            logger.debug("check update error: ${e.message}")
            VersionData.empty
        }

        checkUpdateHandler.handle(result)
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

    fun reportAndUploadLogs(): Deferred<UploadResult> {
        return async {
            logger.debug("reportAndUploadLogs start")
            val pathManager = pathManager ?: return@async UploadResult.fail("pathManager is null")
            // zip log directory
            val fileName = "${requestToken}_${System.currentTimeMillis()}".md5.substring(0, 8)
            val destFile = File(pathManager.tmpDir, "$fileName.zip")
            try {
                logger.debug("reportAndUploadLogs destFile: $destFile")
                val logDir = pathManager.logDir
                if (!logDir.exists()) {
                    val errorMessage = "log dir not exists: ${logDir.absolutePath}"
                    logger.warn(errorMessage)
                    return@async UploadResult.fail(errorMessage)
                }
                logDir.zipTo(destFile)
                val uploadResult = uploadFile(destFile)
                if (!uploadResult.isSuccess) {
                    logger.warn("reportAndUploadLogs failed: ${uploadResult.errorMessage}")
                } else {
                    logger.debug("reportAndUploadLogs success")
                }
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

    private fun File.zipTo(destFile: File) {
        if (destFile.exists()) {
            destFile.delete()
        }
        destFile.createNewFile()

        destFile.outputStream().use { output ->
            ZipOutputStream(output).use { zip ->
                this.listFiles()?.forEach {
                    try {
                        val path = it.relativeTo(this).path
                        zip.putNextEntry(ZipEntry(path))
                        it.inputStream().use { input ->
                            input.copyTo(zip)
                        }
                    } catch (e: Exception) {
                        // exception when zip .lck on windows, just ignore
                        logger.warn("add zip entry $it failed", e)
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
        ideVersion = AsDeployerCompat.ideVersion.toString()
        username = this@JuggServer.username
        projectId = this@JuggServer.projectId
        sessionId = "${this@JuggServer.sessionId}_${this@JuggServer.sessionSubId}"
        return this
    }

    private fun doReport(data: ReportEventData) {
        try {
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
        val pathManager = pathManager ?: return defaultName
        val gitManager = GitManager.createGitManagerAndTrySearchParent(pathManager.projectDir)
        if (!gitManager.hasInitGit) {
            return defaultName
        }
        return gitManager.name ?: defaultName
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