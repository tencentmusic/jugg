package com.sickworm.intellij.jugg.diagnostics

import com.google.gson.JsonParser
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URI

/**
 * Uploads one diagnostics bundle to a validated HTTPS endpoint without fallback.
 */
class IssueReportUploader(
    private val client: OkHttpClient = OkHttpClient(),
) {
    fun upload(bundle: IssueReportBundle, url: String, verifiedContent: ByteArray? = null): IssueReportUploadResult {
        val endpoint = validateUrl(url)
        return try {
            val body = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart(
                    "file",
                    bundle.file.name,
                    verifiedContent?.toRequestBody("application/zip".toMediaType())
                        ?: bundle.file.asRequestBody("application/zip".toMediaType()),
                )
                .build()
            val request = Request.Builder().url(endpoint.toURL()).post(body).build()
            client.newCall(request).execute().use { response ->
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
        const val JUGG_REPORT_URL = "https://jugg.sickworm.com/report_issue"

        fun validateUrl(value: String): URI {
            val uri = runCatching { URI(value.trim()) }
                .getOrElse { throw IllegalArgumentException("Upload URL is invalid") }
            require(uri.isAbsolute && uri.scheme.equals("https", ignoreCase = true)) {
                "Upload URL must use HTTPS"
            }
            require(uri.rawUserInfo == null) { "Upload URL must not contain credentials" }
            require(uri.rawQuery == null) { "Upload URL must not contain a query" }
            require(uri.rawFragment == null) { "Upload URL must not contain a fragment" }
            require(!uri.host.isNullOrBlank()) { "Upload URL must contain a host" }
            return uri
        }
    }
}
