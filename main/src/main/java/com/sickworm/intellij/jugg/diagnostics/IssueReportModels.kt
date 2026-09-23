package com.sickworm.intellij.jugg.diagnostics

import java.io.File
import java.net.URI

/** Keeps one report destination and its privacy policy fixed from bundle creation through upload and retry. */
class IssueReportDestination private constructor(
    val backendServerUrl: String?,
) {
    val uploadUrl: String = backendServerUrl?.trimEnd('/')?.plus("/report_issue") ?: PUBLIC_REPORT_URL
    val redactLogs: Boolean get() = backendServerUrl == null

    companion object {
        const val PUBLIC_REPORT_URL = "https://jugg.sickworm.com/report_issue"
        val Public = IssueReportDestination(null)

        fun backend(serverUrl: String): IssueReportDestination {
            require(serverUrl.isNotBlank()) { "Backend URL must not be blank" }
            val endpointHost = runCatching { URI(serverUrl.trimEnd('/') + "/report_issue").host?.trimEnd('.') }.getOrNull()
            if (endpointHost.equals("jugg.sickworm.com", ignoreCase = true)) {
                return Public
            }
            return IssueReportDestination(serverUrl)
        }
    }
}

enum class IssueReportSensitivity {
    LOW,
    MEDIUM,
    HIGH,
}

data class IssueReportEntry(
    val path: String,
    val size: Long,
    val sensitivity: IssueReportSensitivity,
    val redaction: String = "completed",
)

data class IssueReportCandidate(
    val entry: IssueReportEntry,
    val file: File,
    val isSelectedByDefault: Boolean,
) {
    val path: String get() = entry.path
}

data class IssueReportBundle(
    val reportId: String,
    val file: File,
    val entries: List<IssueReportEntry>,
    val destination: IssueReportDestination,
)

data class IssueReportUploadResult(
    val isSuccess: Boolean,
    val reportId: String?,
    val errorMessage: String?,
)
