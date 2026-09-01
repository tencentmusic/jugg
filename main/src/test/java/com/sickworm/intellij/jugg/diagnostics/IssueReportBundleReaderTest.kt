package com.sickworm.intellij.jugg.diagnostics

import com.intellij.openapi.diagnostic.Logger
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.kotlin.mock
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class IssueReportBundleReaderTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `reader loads the exact prepared bundle`() {
        val outputDir = temporaryFolder.newFolder("output")
        val bundle = prepareBundle(outputDir)

        val prepared = IssueReportBundleReader(outputDir).load(bundle.reportId)

        assertEquals(bundle.reportId, prepared.bundle.reportId)
        assertEquals(bundle.entries, prepared.bundle.entries)
        assertEquals(
            bundle.entries.map { it.path }.toSet() + "diagnostics/manifest.json",
            prepared.archiveEntries.map { it.path }.toSet(),
        )
        assertEquals(IssueReportBundleReader.sha256(bundle.file), prepared.sha256)
    }

    @Test
    fun `reader rejects a bundle changed after confirmation`() {
        val outputDir = temporaryFolder.newFolder("output")
        val bundle = prepareBundle(outputDir)
        val reader = IssueReportBundleReader(outputDir)
        val expectedSha256 = reader.load(bundle.reportId).sha256
        bundle.file.appendText("changed")

        assertFailsWith<IllegalArgumentException> {
            reader.load(bundle.reportId, expectedSha256)
        }
    }

    @Test
    fun `verified content remains the confirmed bytes when the file changes later`() {
        val outputDir = temporaryFolder.newFolder("output")
        val bundle = prepareBundle(outputDir)
        val prepared = IssueReportBundleReader(outputDir).load(bundle.reportId)
        bundle.file.writeText("changed")

        assertEquals(prepared.sha256, IssueReportBundleReader.sha256(prepared.content))
    }

    @Test
    fun `reader rejects an invalid report id`() {
        val outputDir = temporaryFolder.newFolder("output")

        assertFailsWith<IllegalArgumentException> {
            IssueReportBundleReader(outputDir).load("../outside")
        }
    }

    @Test
    fun `reader rejects manifest entries that do not match the zip`() {
        val outputDir = temporaryFolder.newFolder("output")
        val bundle = prepareBundle(outputDir)
        val originalEntries = ZipFile(bundle.file).use { zip ->
            zip.entries().asSequence().associate { entry ->
                entry.name to zip.getInputStream(entry).readBytes()
            }
        }
        ZipOutputStream(bundle.file.outputStream()).use { zip ->
            originalEntries.filterKeys { it != "diagnostics/environment.json" }.forEach { (path, bytes) ->
                zip.putNextEntry(java.util.zip.ZipEntry(path))
                zip.write(bytes)
                zip.closeEntry()
            }
        }

        assertFailsWith<IllegalStateException> {
            IssueReportBundleReader(outputDir).load(bundle.reportId)
        }
    }

    private fun prepareBundle(outputDir: java.io.File): IssueReportBundle {
        val projectDir = temporaryFolder.newFolder("project")
        val userHome = temporaryFolder.newFolder("home")
        val builder = IssueReportBundleBuilder(outputDir, projectDir, userHome, mock<Logger>())
        val candidates = builder.prepare(
            environment = mapOf("runtimeType" to "standalone"),
            projectSummary = emptyMap(),
            logFiles = emptyList(),
            standaloneLogDir = null,
            logcat = "",
        )
        return builder.build(candidates.map { it.path }.toSet())
    }
}
