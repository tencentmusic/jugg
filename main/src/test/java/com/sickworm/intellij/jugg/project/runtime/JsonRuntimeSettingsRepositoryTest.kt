package com.sickworm.intellij.jugg.project.runtime

import com.google.gson.JsonPrimitive
import com.sickworm.intellij.jugg.mock.TestGlobal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Verifies raw JSON settings persistence and cross-runtime field merging. */
class JsonRuntimeSettingsRepositoryTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `missing settings file loads empty values without creating file`() {
        val globalRoot = temporaryFolder.newFolder("global")
        val settingsFile = File(globalRoot, "settings.json")
        val repository = JsonRuntimeSettingsRepository(settingsFile, globalRoot, TestGlobal.logger)

        assertTrue(repository.load().entrySet().isEmpty())
        assertFalse(settingsFile.exists())
    }

    @Test
    fun `migration fills only missing json fields`() {
        val globalRoot = temporaryFolder.newFolder("migration")
        val settingsFile = File(globalRoot, "settings.json")
        settingsFile.writeText("""{"compileOnSave":false}""")
        val repository = JsonRuntimeSettingsRepository(settingsFile, globalRoot, TestGlobal.logger)

        assertTrue(repository.mergeMissing(mapOf(
            "compileOnSave" to JsonPrimitive(true),
            "isUseProjectKotlinCompiler" to JsonPrimitive(false),
        )))

        val values = repository.load()
        assertFalse(values.get("compileOnSave").asBoolean)
        assertFalse(values.get("isUseProjectKotlinCompiler").asBoolean)
    }

    @Test
    fun `runtime field updates rebase on latest shared json`() {
        val globalRoot = temporaryFolder.newFolder("concurrent")
        val settingsFile = File(globalRoot, "settings.json")
        val ideaRepository = JsonRuntimeSettingsRepository(settingsFile, globalRoot, TestGlobal.logger)
        val standaloneRepository = JsonRuntimeSettingsRepository(settingsFile, globalRoot, TestGlobal.logger)

        assertTrue(ideaRepository.update("compileOnSave", JsonPrimitive(true)))
        assertTrue(standaloneRepository.update("isEnableDirectOverlayDeploy", JsonPrimitive(false)))

        val values = ideaRepository.load()
        assertTrue(values.get("compileOnSave").asBoolean)
        assertFalse(values.get("isEnableDirectOverlayDeploy").asBoolean)
    }

    @Test
    fun `failed migration keeps file absent and can retry later`() {
        val globalRoot = temporaryFolder.newFolder("retry")
        val blockedParent = temporaryFolder.newFile("blocked_parent")
        val settingsFile = File(blockedParent, "settings.json")
        val repository = JsonRuntimeSettingsRepository(settingsFile, globalRoot, TestGlobal.logger)
        val legacyValues = mapOf("compileOnSave" to JsonPrimitive(true))

        assertFalse(repository.mergeMissing(legacyValues))
        assertFalse(settingsFile.exists())

        assertTrue(blockedParent.delete())
        assertTrue(blockedParent.mkdirs())
        assertTrue(repository.mergeMissing(legacyValues))
        assertEquals(true, repository.load().get("compileOnSave").asBoolean)
    }
}
