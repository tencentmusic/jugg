package com.sickworm.intellij.jugg.diagnostics

import com.intellij.openapi.diagnostic.Logger
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.kotlin.mock
import java.util.zip.ZipFile
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IssueReportBundleBuilderTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `bundle contains only selected redacted candidates and matching manifest`() {
        val root = temporaryFolder.newFolder()
        val projectDir = root.resolve("secret-project").apply { mkdirs() }
        val userHome = root.resolve("user-home").apply { mkdirs() }
        val log = projectDir.resolve("compile.log").apply {
            writeText("project=$projectDir home=$userHome password=secret-value")
        }
        val hookDebugLog = root.resolve("jugg-hook-debug.log").apply { writeText("hook log") }
        val builder = IssueReportBundleBuilder(root.resolve("output"), projectDir, userHome, mock<Logger>())
        val candidates = builder.prepare(
            environment = mapOf("pluginVersion" to "3.2.0"),
            projectSummary = mapOf("moduleCount" to 2),
            logFiles = listOf(log),
            logcat = "device log",
            hookDebugLog = hookDebugLog,
            knownSecrets = setOf("secret-value"),
        )

        assertTrue(candidates.all { it.isSelectedByDefault })

        val bundle = builder.build(candidates.filterNot { it.path == "diagnostics/device/logcat.log" }.map { it.path }.toSet())

        assertTrue(bundle.reportId.matches(Regex("[0-9a-f]{8}")))
        ZipFile(bundle.file).use { zip ->
            val entries = zip.entries().asSequence().map { it.name }.toSet()
            assertFalse("diagnostics/device/logcat.log" in entries)
            assertTrue("diagnostics/manifest.json" in entries)
            assertTrue("diagnostics/cli/hook-debug.log" in entries)
            assertFalse("diagnostics/optional/hook-debug.log" in entries)
            assertEquals(bundle.entries.map { it.path }.toSet() + "diagnostics/manifest.json", entries)
            val logText = zip.getInputStream(zip.getEntry("diagnostics/logs/compile.log")).bufferedReader().readText()
            assertTrue("\${PROJECT_DIR}" in logText)
            assertTrue("\${USER_HOME}" in logText)
            assertTrue("[REDACTED]" in logText)
            assertFalse("secret-value" in logText)
        }
    }
}
