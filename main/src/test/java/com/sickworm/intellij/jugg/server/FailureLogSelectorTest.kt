package com.sickworm.intellij.jugg.server

import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.assertEquals

class FailureLogSelectorTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `selects two latest real compile logs`() {
        val dir = temporaryFolder.newFolder()
        val oldest = dir.resolve("compile_2026-09-20_10-00-00.0.log").apply {
            writeText("oldest")
            setLastModified(1_000)
        }
        val second = dir.resolve("compile_2026-09-20_11-00-00.0.log").apply {
            writeText("second")
            setLastModified(2_000)
        }
        val latest = dir.resolve("compile_2026-09-20_12-00-00.0.log").apply {
            writeText("latest")
            setLastModified(3_000)
        }
        dir.resolve("compile_latest.log").writeText("shortcut")
        dir.resolve("compile_latest-1.log").writeText("shortcut")
        dir.resolve("compile_2026-09-20_12-00-00.0.log.lck").writeText("lock")
        dir.resolve("compile_not_a_file.log").mkdirs()

        assertEquals(listOf(latest, second), selectRecentFailureLogs(dir))
        assertEquals("oldest", oldest.readText())
    }
}
