package com.sickworm.intellij.jugg.diagnostics

import java.io.File

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
)

data class IssueReportUploadResult(
    val isSuccess: Boolean,
    val reportId: String?,
    val errorMessage: String?,
)
