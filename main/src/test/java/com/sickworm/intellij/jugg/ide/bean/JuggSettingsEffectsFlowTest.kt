package com.sickworm.intellij.jugg.ide.bean

import com.sickworm.intellij.jugg.deploy.DeployHistoryManager
import com.sickworm.intellij.jugg.mock.TestGlobal
import com.sickworm.intellij.jugg.project.change.FileChangesHandler
import com.sickworm.intellij.jugg.project.runtime.*
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.TemporaryFolder

/** L2: user edits, global persistence, project locking, and durable history effects. */
class JuggSettingsEffectsFlowTest {
    @get:Rule val temporary = TemporaryFolder()
    private val originalRoot = JuggGlobalPathManager.rootDir
    @Before fun setUp() { JuggGlobalPathManager.rootDir = temporary.newFolder("global") }
    @After fun tearDown() { JuggGlobalPathManager.rootDir = originalRoot; JuggSettings.reload() }

    @Test fun `backup changes invalidate each project once and survive host restart`() {
        val paths = JuggPathManager(temporary.newFolder("project"))
        val history = history(paths)
        val baseline = paths.compileContextDbDir.resolve("complete_flag").apply { parentFile.mkdirs(); writeText("") }
        assertFalse(apply(paths, history))
        assertTrue(baseline.exists())
        JuggSettingsEditor().set("isEnableBackupClasspath", "true", confirm = true)
        assertTrue(apply(paths, history))
        assertFalse(baseline.exists())
        baseline.parentFile.mkdirs(); baseline.writeText("")
        assertFalse(apply(paths, history(paths)))
        assertTrue(baseline.exists())
        val other = JuggPathManager(temporary.newFolder("other"))
        val otherBaseline = other.compileContextDbDir.resolve("complete_flag").apply { parentFile.mkdirs(); writeText("") }
        assertTrue(apply(other, history(other)))
        assertFalse(otherBaseline.exists())
    }

    @Test fun `compat edits persist reinstall requirement for the next host`() {
        val paths = JuggPathManager(temporary.newFolder("project"))
        history(paths).lastDeployOverlayIds = mapOf("device" to "previous-overlay")
        JuggSettingsEditor().set("deviceCompatRecordJson", """[{"displayName":"Pixel"}]""")
        assertTrue(apply(paths, history(paths)))
        assertTrue(history(paths).isCleanAndReinstall)
        assertFalse(apply(paths, history(paths)))
    }

    @Test fun `failed project effect is not acknowledged`() {
        val paths = JuggPathManager(temporary.newFolder("project"))
        val history = org.mockito.Mockito.mock(com.sickworm.intellij.jugg.deploy.IDeployHistoryManager::class.java)
        JuggSettingsEditor().set("isEnableBackupClasspath", "true", confirm = true)
        org.mockito.Mockito.doThrow(IllegalStateException("disk blocked")).`when`(history).deleteDeployHistory()
        kotlin.test.assertFailsWith<IllegalStateException> { apply(paths, history) }
        assertFalse(paths.settingsAppliedFile.exists())
        org.mockito.Mockito.doNothing().`when`(history).deleteDeployHistory()
        assertTrue(apply(paths, history))
    }

    private fun apply(paths: JuggPathManager, history: com.sickworm.intellij.jugg.deploy.IDeployHistoryManager) =
        FileExecutionLockManager(paths, RuntimeIdentity("test", "settings-flow")).withProjectLock("Apply settings") {
            JuggSettingsProjectEffects(paths, history).apply()
        }

    private fun history(paths: JuggPathManager) = DeployHistoryManager(paths,
        FileChangesHandler(paths.projectDir, paths.juggRootDir, TestGlobal.logger), TestGlobal.logger)
}
