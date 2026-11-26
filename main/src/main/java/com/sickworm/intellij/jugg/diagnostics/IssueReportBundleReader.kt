package com.sickworm.intellij.jugg.diagnostics

import com.google.gson.JsonParser
import java.io.File
import java.io.ByteArrayInputStream
import java.security.MessageDigest
import java.util.zip.ZipInputStream

data class PreparedIssueReport(
    val bundle: IssueReportBundle,
    val sha256: String,
    val content: ByteArray,
    val archiveEntries: List<IssueReportEntry>,
)

/** Loads a prepared diagnostics archive and verifies the exact content before upload. */
class IssueReportBundleReader(
    private val outputDir: File,
) {

    fun load(reportId: String, expectedSha256: String? = null): PreparedIssueReport {
        require(REPORT_ID.matches(reportId)) { "Invalid report ID" }
        val reportDir = File(outputDir, reportId).canonicalFile
        require(reportDir.parentFile == outputDir.canonicalFile) { "Invalid diagnostics directory" }
        val zipFile = File(reportDir, "$reportId.zip")
        require(zipFile.isFile) { "Diagnostics bundle does not exist" }
        val content = zipFile.readBytes()
        val actualSha256 = sha256(content)
        if (expectedSha256 != null) {
            require(SHA_256.matches(expectedSha256) && actualSha256.equals(expectedSha256, ignoreCase = true)) {
                "Diagnostics bundle changed after confirmation"
            }
        }
        val (entries, manifestSize) = readEntries(content, reportId)
        val archiveEntries = entries + IssueReportEntry(
            MANIFEST_PATH,
            manifestSize,
            IssueReportSensitivity.LOW,
        )
        return PreparedIssueReport(
            IssueReportBundle(reportId, zipFile, entries),
            actualSha256,
            content,
            archiveEntries,
        )
    }

    private fun readEntries(content: ByteArray, reportId: String): Pair<List<IssueReportEntry>, Long> {
        val zipContent = linkedMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(content)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                check(entry.name !in zipContent) { "Diagnostics bundle contains duplicate entries" }
                zipContent[entry.name] = zip.readBytes()
                zip.closeEntry()
            }
        }
        val manifestBytes = zipContent[MANIFEST_PATH] ?: error("Diagnostics manifest is missing")
        val manifest = manifestBytes.inputStream().bufferedReader().use { reader ->
            JsonParser.parseReader(reader).asJsonObject
        }
            check(manifest.get("reportId")?.asString == reportId) { "Diagnostics report ID does not match manifest" }
            val entries = manifest.getAsJsonArray("entries")?.map { element ->
                val entry = element.asJsonObject
                IssueReportEntry(
                    path = entry.get("path").asString,
                    size = entry.get("size").asLong,
                    sensitivity = IssueReportSensitivity.valueOf(entry.get("sensitivity").asString),
                    redaction = entry.get("redaction").asString,
                )
            } ?: emptyList()
            check(zipContent.keys == entries.map { it.path }.toSet() + MANIFEST_PATH) {
                "Diagnostics manifest does not match zip entries"
            }
            entries.forEach { entry ->
                check(zipContent.getValue(entry.path).size.toLong() == entry.size) {
                    "Diagnostics manifest size does not match ${entry.path}"
                }
            }
        return entries to manifestBytes.size.toLong()
    }

    companion object {
        private const val MANIFEST_PATH = "diagnostics/manifest.json"
        private val REPORT_ID = Regex("[0-9a-f]{8}")
        private val SHA_256 = Regex("[0-9a-fA-F]{64}")

        fun sha256(file: File): String {
            return sha256(file.readBytes())
        }

        fun sha256(content: ByteArray): String {
            val digest = MessageDigest.getInstance("SHA-256")
            digest.update(content)
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
