package com.sickworm.intellij.jugg.diagnostics

import com.google.gson.JsonParser
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import java.net.URI

/**
 * Uploads one diagnostics bundle to a validated endpoint without fallback.
 */
class IssueReportUploader(
    client: OkHttpClient = OkHttpClient(),
) {
    private val uploadClient = client.newBuilder().followRedirects(false).followSslRedirects(false).build()

    fun upload(
        bundle: IssueReportBundle,
        autoUpload: IssueReportAutoUpload? = null,
        projectName: String? = null,
        username: String? = null,
    ): IssueReportUploadResult {
        return try {
            val destination = bundle.destination
            require(!destination.redactLogs || bundle.entries.none { it.redaction == "none" }) {
                "Unredacted diagnostics cannot be uploaded to the public service"
            }
            val endpoint = validateUrl(destination.uploadUrl, destination.backendServerUrl != null)
            val body = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("file", bundle.file.name, bundle.file.asRequestBody("application/zip".toMediaType()))
                .apply {
                    if (autoUpload != null) {
                        addFormDataPart("is_auto_upload", "true")
                        addFormDataPart("failed_reason", autoUpload.failedReason)
                        addFormDataPart("plugin_version", autoUpload.pluginVersion)
                        addFormDataPart("report_id", bundle.reportId)
                        autoUpload.errorDetail?.let { addFormDataPart("error_detail", it) }
                    }
                    (autoUpload?.projectName ?: projectName?.takeIf { destination.backendServerUrl != null })
                        ?.let { addFormDataPart("project_name", it) }
                    (autoUpload?.username ?: username?.takeIf { destination.backendServerUrl != null })
                        ?.let { addFormDataPart("username", it) }
                }.build()
            val request = Request.Builder().url(endpoint.toURL()).post(body).build()
            uploadClient.newCall(request).execute().use { response ->
                val responseBody = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    return IssueReportUploadResult(false, null, "Upload failed: [${response.code}] $responseBody")
                }
                val reportId = runCatching {
                    JsonParser.parseString(responseBody).asJsonObject.get("reportId")?.asString
                }.getOrNull() ?: bundle.reportId
                IssueReportUploadResult(true, reportId, null)
            }
        } catch (e: Exception) {
            IssueReportUploadResult(false, null, e.message ?: "Upload failed")
        }
    }

    companion object {
        fun validateUrl(value: String, allowHttpForBackend: Boolean = false): URI {
            val uri = runCatching { URI(value.trim()) }
                .getOrElse { throw IllegalArgumentException("Upload URL is invalid") }
            require(uri.isAbsolute && (uri.scheme.equals("https", ignoreCase = true) ||
                    allowHttpForBackend && uri.scheme.equals("http", ignoreCase = true))) {
                "Upload URL must use HTTPS unless targeting a configured backend"
            }
            require(uri.rawUserInfo == null) { "Upload URL must not contain credentials" }
            require(uri.rawQuery == null) { "Upload URL must not contain a query" }
            require(uri.rawFragment == null) { "Upload URL must not contain a fragment" }
            require(!uri.host.isNullOrBlank()) { "Upload URL must contain a host" }
            return uri
        }
    }
}

/** Metadata supplied only for automatic failure reports. */
data class IssueReportAutoUpload(
    val failedReason: String,
    val errorDetail: String?,
    val projectName: String,
    val username: String,
    val pluginVersion: String,
)
