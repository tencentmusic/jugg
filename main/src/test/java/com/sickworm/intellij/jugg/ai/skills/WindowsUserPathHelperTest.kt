package com.sickworm.intellij.jugg.ai.skills

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import kotlin.system.measureTimeMillis
import kotlin.test.assertFailsWith

class WindowsUserPathHelperTest {

    @Test
    fun containsPathEntry_shouldMatchIgnoringCaseAndTrailingSlash() {
        val current = "C:\\Users\\Admin\\.jugg\\bin;C:\\Windows"
        assertTrue(WindowsUserPathHelper.containsPathEntry(current, "c:\\users\\admin\\.jugg\\bin\\"))
        assertTrue(WindowsUserPathHelper.containsPathEntry(current, "C:\\Windows"))
        assertFalse(WindowsUserPathHelper.containsPathEntry(current, "C:\\Other"))
    }

    @Test
    fun containsPathEntry_shouldReturnFalseForBlankCurrent() {
        assertFalse(WindowsUserPathHelper.containsPathEntry(null, "C:\\Users\\Admin\\.jugg\\bin"))
        assertFalse(WindowsUserPathHelper.containsPathEntry("", "C:\\Users\\Admin\\.jugg\\bin"))
    }

    @Test
    fun prependPathEntry_shouldPrependTargetWhenMissing() {
        assertEquals(
            "C:\\Users\\Admin\\.jugg\\bin;C:\\Windows",
            WindowsUserPathHelper.prependPathEntry("C:\\Windows", "C:\\Users\\Admin\\.jugg\\bin"),
        )
    }

    @Test
    fun prependPathEntry_shouldUseTargetOnlyWhenCurrentBlank() {
        assertEquals(
            "C:\\Users\\Admin\\.jugg\\bin",
            WindowsUserPathHelper.prependPathEntry(null, "C:\\Users\\Admin\\.jugg\\bin"),
        )
    }

    @Test
    fun parseRegQueryPathValue_shouldExtractPathValue() {
        val output = """
            HKEY_CURRENT_USER\Environment
                Path    REG_SZ    C:\Users\Admin\.jugg\bin;C:\Windows
        """.trimIndent()
        assertEquals(
            "C:\\Users\\Admin\\.jugg\\bin;C:\\Windows",
            WindowsUserPathUpdater.parseRegQueryPathValue(output),
        )
    }

    @Test
    fun runCommandWithTimeout_shouldStopWaitingWhenProcessDoesNotExit() {
        val elapsed = measureTimeMillis {
            assertFailsWith<IOException> {
                runCommandWithTimeout(blockingCommand(), 100L)
            }
        }

        assertTrue("Timed out process should return promptly", elapsed < 3_000L)
    }

    private fun blockingCommand(): List<String> {
        return if (System.getProperty("os.name").lowercase().contains("windows")) {
            listOf("cmd", "/c", "ping -n 6 127.0.0.1 >nul")
        } else {
            listOf("sh", "-c", "sleep 5")
        }
    }
}
