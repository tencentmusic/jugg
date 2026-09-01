package com.sickworm.intellij.jugg.server

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.jugg.compiler.listFilesRecursively
import com.sickworm.intellij.jugg.git.GitManager
import com.sickworm.intellij.jugg.ide.bean.JuggSettings
import com.sickworm.intellij.jugg.logger.getInstance
import com.sickworm.intellij.jugg.project.runtime.JuggGlobalPathManager
import com.sickworm.intellij.jugg.project.runtime.JuggPathManager
import com.sickworm.intellij.jugg.project.runtime.RuntimeInfo
import com.sickworm.intellij.jugg.server.protocols.HotUpdateData
import com.sickworm.intellij.jugg.server.protocols.ServerRule
import com.sickworm.intellij.jugg.server.protocols.VersionData
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Request

import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean


/**
 * JuggServer coordinates plugin-to-backend interactions including event reporting, update checks, log upload, and remote config retrieval.
 * Collaboration: Uses [JuggServerChooser] for server failover, [OkHttpClient] for HTTP requests, and injected [RuntimeInfo] for host-neutral identity.
 * Data Contract: Request identity is derived from [projectId], [username], and [requestToken]; [afterFullCompile] increments [sessionId], and [onCompile] increments [sessionSubId].
 */
class JuggServer(
    private val projectName: String,
    private val pathManager: JuggPathManager,
    coroutineScope: CoroutineScope,
    private val runtimeInfo: RuntimeInfo,
    loggerArg: Logger,
    private val eventLocalStore: JuggEventLocalStore = JuggEventLocalStore(
        JuggGlobalPathManager.actionDbFile,
        loggerArg.getInstance("JuggEventLocalStore"),
    ),
): CoroutineScope {

    private var logger: Logger = loggerArg.getInstance("JuggServer")

    override val coroutineContext = coroutineScope.coroutineContext +
        SupervisorJob(coroutineScope.coroutineContext[Job]) +
        CoroutineExceptionHandler { _, throwable -> logger.warn("server task failed", throwable) }

    private val juggServerChooser = JuggServerChooser(logger)
    private val serverUrl: String? get() = JuggSettings.serverUrl


    private val username: String = getUserName()

    val version: String = runtimeInfo.runtimeVersion

    private val projectId: String by lazy { getName(projectName) }
    private val requestToken = (pathManager.projectDir.path + "_" + username).md5.substring(0, 8)

    private var sessionId: Int = 1
    private var sessionSubId: Int = 0

    private val client = OkHttpClient()
    private val initialized = AtomicBoolean()

    fun initialize() {
        if (!initialized.compareAndSet(false, true)) return
        logger.debug("init finished, version: $version, projectId: $projectId, userName: $username, requestToken: $requestToken, serverUrl: $serverUrl")
        if (juggServerChooser.hasAvailableServer()) {
            launch {
                juggServerChooser.updateServerIfExpired(isForce = true)
            }
        }
    }

    fun afterFullCompile() {
        sessionId += 1
    }

    fun onCompile() {
        sessionSubId += 1
    }

    private var reportLock = Mutex() // report only one event in the same time

    fun checkUpdate(onComplete: (VersionData) -> Unit): Job = launch {
        if (!juggServerChooser.hasAvailableServer()) {
            return@launch
        }
        val serverUrl = serverUrl?.takeIf { it.isNotBlank() }
        if (serverUrl == null) {
            logger.debug("checkUpdate skip: serverUrl is null or blank")
            return@launch
        }

        try {
            val request: Request = Request.Builder()
                .url("$serverUrl/check_update?version=$version&requestToken=$requestToken&projectName=${URLEncoder.encode(projectId, "UTF-8")}")
                .get()
                .build()

            val response = client.newCall(request).execute()
            val body = response.body?.string()
            logger.debug("check update response: [${response.code}] $body")

            val result = Gson().fromJson(body, VersionData::class.java)
            onComplete.invoke(result)
        } catch (e: Exception) {
            logger.debug("check update error: ${e.message}")
            if (juggServerChooser.updateServerWithForbidCurrentUrl(serverUrl)) {
                logger.debug("check update error, update server, current: $serverUrl")
                checkUpdate(onComplete)
            }
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

    private fun ReportEventData.fillCommonData(): ReportEventData {
        version = this@JuggServer.version
        ideVersion = this@JuggServer.runtimeInfo.hostVersion
        username = this@JuggServer.username
        projectId = this@JuggServer.projectId
        sessionId = "${this@JuggServer.sessionId}_${this@JuggServer.sessionSubId}"
        return this
    }

    private fun doReport(data: ReportEventData) {
        try {
            eventLocalStore.append(data)
        } catch (e: Exception) {
            logger.debug("Persist report ${data.action} locally failed, continue remote report.", e)
        }
        if (!juggServerChooser.hasAvailableServer()) {
            return
        }
        try {
            juggServerChooser.updateServerIfExpired()

            val serverUrl = serverUrl?.takeIf { it.isNotBlank() }
            if (serverUrl == null) {
                logger.debug("report ${data.action} skip: serverUrl is null or blank")
                return
            }

            val content = Gson().toJson(data)
            val request: Request = Request.Builder()
                .url("$serverUrl/report_event")
                .post(content.toRequestBody("application/json".toMediaTypeOrNull()))
                .build()

            val response = client.newCall(request).execute()
            logger.debug("report ${data.action} response: [${response.code}] ${response.body?.string()}")
        } catch (e: Exception) {
            logger.debug("report ${data.action} failed: ${e.message}")
        }
    }

    private fun getName(defaultName: String): String {
        val gitManager = GitManager.createGitManagerAndTrySearchParent(pathManager.projectDir)
        if (!gitManager.hasInitGit) {
            return defaultName
        }
        return gitManager.name ?: defaultName
    }

    private fun getUserName(): String {
        val defaultName = System.getProperty("user.name") ?: "jugg_user_unknown"
        val projectDir = pathManager.projectDir
        return GitManager.createGitManagerAndTrySearchParent(projectDir).userName ?: defaultName
    }

    fun updateServer(servers: List<ServerRule>?) {
        launch {
            juggServerChooser.updateServer(servers)
        }
    }

    fun setCustomServer(serverUrl: String? = null) {
        if (serverUrl == null) {
            juggServerChooser.setCustomServer()
        } else {
            juggServerChooser.setCustomServer(serverUrl)
        }
    }

    val customServerUrl: String
        get() = juggServerChooser.customServerUrl

    fun checkHotUpdate(isPositiveCheckout: Boolean): HotUpdateData? {
        if (!juggServerChooser.hasAvailableServer()) {
            return null
        }
        try {
            val serverUrl = serverUrl?.takeIf { it.isNotBlank() }
            if (serverUrl == null) {
                logger.debug("checkHotUpdate skip: serverUrl is null or blank")
                return null
            }

            val request: Request = Request.Builder()
                .url("$serverUrl/check_hot_update?version=$version" +
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
            throw e
        }
    }

    fun launchSafe(block: suspend CoroutineScope.() -> Unit) {
        launch {
            try {
                block()
            } catch (e: Throwable) {
                logger.warn("launchSafe error", e)
            }
        }
    }
}


/**
 * ReportEventData carries version, host version, username, and projectId.
 */
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
