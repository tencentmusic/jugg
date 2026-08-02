package com.sickworm.intellij.jugg.diagnostics

import com.google.gson.GsonBuilder
import com.intellij.openapi.diagnostic.Logger
import java.io.File
import java.time.OffsetDateTime
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * Builds a diagnostics archive exclusively from generated and redacted whitelist files.
 */
class IssueReportBundleBuilder(
    private val outputDir: File,
    private val projectDir: File,
    private val userHome: File,
    private val logger: Logger,
) {
    private val gson = GsonBuilder().setPrettyPrinting().create()
    private var preparedCandidates: List<IssueReportCandidate> = emptyList()
    private lateinit var reportDir: File
    private lateinit var reportId: String

    fun prepare(
        environment: Map<String, Any?>,
        projectSummary: Map<String, Any?>,
        logFiles: List<File>,
        logcat: String,
        hookDebugLog: File? = null,
        knownSecrets: Set<String> = emptySet(),
    ): List<IssueReportCandidate> {
        reportId = UUID.randomUUID().toString().substringBefore('-')
        reportDir = File(outputDir, reportId).apply { mkdirs() }
        val candidates = mutableListOf<IssueReportCandidate>()
        candidates += writeJsonCandidate("diagnostics/environment.json", environment, IssueReportSensitivity.LOW)
        candidates += writeJsonCandidate("diagnostics/project-summary.json", projectSummary, IssueReportSensitivity.MEDIUM)
        logFiles.filter { it.isFile }.forEach { logFile ->
            candidates += writeTextCandidate(
                "diagnostics/logs/${logFile.name}",
                redact(logFile.readText(), knownSecrets),
                IssueReportSensitivity.MEDIUM,
                true,
            )
        }
        if (logcat.isNotBlank()) {
            candidates += writeTextCandidate(
                "diagnostics/device/logcat.log",
                redact(logcat, knownSecrets),
                IssueReportSensitivity.HIGH,
                true,
            )
        }
        if (hookDebugLog?.isFile == true) {
            candidates += writeTextCandidate(
                "diagnostics/cli/hook-debug.log",
                redact(hookDebugLog.readText(), knownSecrets),
                IssueReportSensitivity.HIGH,
                true,
            )
        }
        preparedCandidates = candidates
        return candidates
    }

    fun build(selectedPaths: Set<String>): IssueReportBundle {
        check(::reportDir.isInitialized) { "prepare must be called before build" }
        val selected = preparedCandidates.filter { it.path in selectedPaths }
        val manifest = linkedMapOf(
            "schemaVersion" to 1,
            "reportId" to reportId,
            "createdAt" to OffsetDateTime.now().toString(),
            "entries" to selected.map { it.entry },
        )
        val manifestFile = writeFile("diagnostics/manifest.json", gson.toJson(manifest))
        val zipFile = File(reportDir, "$reportId.zip")
        ZipOutputStream(zipFile.outputStream().buffered()).use { zip ->
            (selected.map { it.file to it.path } + (manifestFile to "diagnostics/manifest.json")).forEach { (file, path) ->
                zip.putNextEntry(ZipEntry(path))
                file.inputStream().buffered().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
        val expectedPaths = selected.map { it.path }.toSet() + "diagnostics/manifest.json"
        ZipFile(zipFile).use { zip ->
            val actualPaths = zip.entries().asSequence().map { it.name }.toSet()
            check(actualPaths == expectedPaths) { "Diagnostics manifest does not match zip entries" }
            selected.forEach { candidate ->
                check(zip.getEntry(candidate.path).size == candidate.entry.size) {
                    "Diagnostics manifest size does not match ${candidate.path}"
                }
            }
        }
        logger.debug("Built diagnostics bundle: $zipFile")
        return IssueReportBundle(reportId, zipFile, selected.map { it.entry })
    }

    private fun writeJsonCandidate(
        path: String,
        value: Map<String, Any?>,
        sensitivity: IssueReportSensitivity,
    ): IssueReportCandidate = writeTextCandidate(path, gson.toJson(value), sensitivity, true)

    private fun writeTextCandidate(
        path: String,
        content: String,
        sensitivity: IssueReportSensitivity,
        isSelectedByDefault: Boolean,
    ): IssueReportCandidate {
        val file = writeFile(path, content)
        return IssueReportCandidate(
            IssueReportEntry(path, file.length(), sensitivity),
            file,
            isSelectedByDefault,
        )
    }

    private fun writeFile(path: String, content: String): File {
        val file = File(reportDir, path).apply { parentFile.mkdirs() }
        file.writeText(content)
        return file
    }

    private fun redact(content: String, knownSecrets: Set<String>): String {
        var redacted = content
            .replace(projectDir.absolutePath, "\${PROJECT_DIR}")
            .replace(userHome.absolutePath, "\${USER_HOME}")
        knownSecrets.filter { it.isNotBlank() }.forEach { secret ->
            redacted = redacted.replace(secret, "[REDACTED]")
        }
        return redacted
            .replace(Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}"), "[EMAIL]")
            .replace(
                Regex("(?i)(password|token|secret|cookie)\\s*[=:]\\s*[^\\s,;]+"),
                "\$1=[REDACTED]",
            )
    }
}
