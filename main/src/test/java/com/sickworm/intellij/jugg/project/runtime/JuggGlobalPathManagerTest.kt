package com.sickworm.intellij.jugg.project.runtime

import com.sickworm.intellij.jugg.mock.IsolatedHomeRule
import org.junit.Rule
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class JuggGlobalPathManagerTest {
    @get:Rule val isolatedHome = IsolatedHomeRule()

    @Test fun `startup selects the configured locator directory`() {
        val home = File(System.getProperty("user.home"))
        val target = File(home, "custom storage")
        File(home, ".juggdir").writeText("juggdir: ${target.absolutePath}\n")
        assertEquals(target, JuggGlobalPathManager.rootDir)
        assertTrue(target.isDirectory)
    }

    @Test fun `startup falls back when the preferred path is blocked`() {
        val home = File(System.getProperty("user.home"))
        File(home, ".jugg").writeText("blocked")
        System.setProperty("java.io.tmpdir", home.path)
        System.setProperty("user.name", "a/b")
        assertEquals(File(home, "jugg-a_b"), JuggGlobalPathManager.rootDir)
        assertTrue(JuggGlobalPathManager.rootDir.isDirectory)
    }

    @Test fun `resolution fails when both directories are blocked`() {
        val home = File(System.getProperty("user.home"))
        // Initialize a valid Runtime before checking another resolution failure.
        JuggGlobalPathManager.rootDir
        File(home, ".jugg").deleteRecursively()
        File(home, ".jugg").writeText("blocked")
        System.setProperty("java.io.tmpdir", home.path)
        System.setProperty("user.name", "blocked")
        File(home, "jugg-blocked").writeText("blocked")
        assertFailsWith<IllegalStateException> { JuggGlobalPathManager.resolveRootDir() }
    }
}
