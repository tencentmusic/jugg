package com.sickworm.intellij.jugg.project

import com.intellij.openapi.diagnostic.Logger
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.kotlin.mock
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ExpiredArtifactCleanerTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `cleanup removes expired files and empty directories while retaining newer files`() {
        val root = temporaryFolder.newFolder()
        val expiredDir = root.resolve("expired").apply { mkdirs() }
        val expiredFile = expiredDir.resolve("old.zip").apply { writeText("old") }
        val retainedFile = root.resolve("new.zip").apply { writeText("new") }
        val nowMs = TimeUnit.DAYS.toMillis(10)
        expiredFile.setLastModified(nowMs - TimeUnit.DAYS.toMillis(7))
        retainedFile.setLastModified(nowMs - TimeUnit.DAYS.toMillis(7) + 1)

        val result = ExpiredArtifactCleaner.cleanupExpiredFiles(
            root,
            mock<Logger>(),
            retentionDays = 7,
            nowMs = nowMs,
        )

        assertFalse(expiredFile.exists())
        assertFalse(expiredDir.exists())
        assertTrue(retainedFile.exists())
        assertEquals(2, result.scannedFiles)
        assertEquals(1, result.expiredFiles)
        assertEquals(1, result.deletedFiles)
        assertEquals(1, result.deletedEmptyDirs)
    }
}
