package com.sickworm.intellij.jugg.project.runtime

import com.sickworm.intellij.jugg.ide.bean.JuggSettings

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.sickworm.intellij.jugg.deploy.IDeployHistoryManager
import com.sickworm.intellij.jugg.project.runtime.JuggPathManager
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.AtomicMoveNotSupportedException

/** Consumes global setting revisions under the caller's project write lock, before baseline recovery/use. */
class JuggSettingsProjectEffects(private val paths: JuggPathManager, private val history: IDeployHistoryManager) {
    fun apply(): Boolean {
        val global = JuggSettings.snapshot()
        val file = paths.settingsAppliedFile
        val applied = if (file.exists()) JsonParser.parseString(file.readText()).asJsonObject else JsonObject()
        val backup = global["backupClasspathRevision"]?.asLong ?: 0L
        val compat = global["deviceCompatRevision"]?.asLong ?: 0L
        val backupChanged = backup > (applied["backupClasspathRevision"]?.asLong ?: 0L)
        val compatChanged = compat > (applied["deviceCompatRevision"]?.asLong ?: 0L)
        if (!backupChanged && !compatChanged) return false
        if (backupChanged) {
            history.deleteDeployHistory()
            check(!paths.compileContextDbDir.resolve("complete_flag").exists() &&
                    !paths.deployHistoryDbDir.resolve("deploy_history.json").exists()) { "Failed to invalidate deployment history." }
        }
        if (compatChanged && !backupChanged && history.lastDeployOverlayIds.isNotEmpty()) history.isCleanAndReinstall = true
        val next = JsonObject().apply {
            addProperty("backupClasspathRevision", backup)
            addProperty("deviceCompatRevision", compat)
        }
        Files.createDirectories(file.parentFile.toPath())
        val temp = Files.createTempFile(file.parentFile.toPath(), "settings-applied-", ".tmp")
        try {
            Files.writeString(temp, next.toString())
            try {
                Files.move(temp, file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temp, file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temp)
        }
        return true
    }
}
