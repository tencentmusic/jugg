package com.sickworm.intellij.jugg.cmdline.standalone

import com.google.gson.Gson
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

data class StandaloneActivationState(
    val lastKnownGoodBuildId: String?,
    val failedBuildId: String?,
)

/** Records standalone readiness and permits one automatic rollback per failed build. */
class StandaloneActivationManager(rootDir: File, private val installer: StandaloneRuntimeInstaller) {
    private val stateFile = rootDir.resolve("hot_update/standalone_activation_state.json")

    fun onReady(buildId: String) {
        val current = readState()
        writeState(StandaloneActivationState(buildId, current?.failedBuildId))
    }

    fun onStartFailed(buildId: String): Boolean {
        val current = readState()
        if (current?.failedBuildId == buildId || installer.readPreviousManifest() == null) return false
        writeState(StandaloneActivationState(current?.lastKnownGoodBuildId, buildId))
        installer.rollback()
        return true
    }

    fun readState(): StandaloneActivationState? {
        if (!stateFile.isFile) return null
        return runCatching { Gson().fromJson(stateFile.readText(), StandaloneActivationState::class.java) }.getOrNull()
    }

    private fun writeState(state: StandaloneActivationState) {
        stateFile.parentFile.mkdirs()
        val temp = stateFile.parentFile.resolve("${stateFile.name}.${System.nanoTime()}.tmp")
        try {
            temp.writeText(Gson().toJson(state))
            try {
                Files.move(temp.toPath(), stateFile.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temp.toPath(), stateFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            temp.delete()
        }
    }
}
