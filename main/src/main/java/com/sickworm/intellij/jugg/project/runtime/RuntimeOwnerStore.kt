package com.sickworm.intellij.jugg.project.runtime

import com.google.gson.Gson
import com.intellij.openapi.diagnostic.Logger
import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

/** Persists the last runtime process that acquired project write ownership. */
data class RuntimeOwner(
    val runtimeType: String,
    val runtimeVersion: String,
    val pid: Long,
    val claimedAt: Long,
)

/** Reports a persisted runtime owner transition after a project lock is acquired. */
data class RuntimeOwnerChangeEvent(
    val previousOwner: RuntimeOwner,
    val currentOwner: RuntimeOwner,
)

/** Stores last-runtime ownership separately from transient lock owner metadata. */
class RuntimeOwnerStore(
    private val ownerFile: File,
) {
    private val gson = Gson()

    @Synchronized
    internal fun claim(runtimeIdentity: RuntimeIdentity, logger: Logger): RuntimeOwnerChangeEvent? {
        val previousOwner = try {
            read()
        } catch (e: Exception) {
            logger.warn("read runtime owner failed, replacing corrupt owner metadata", e)
            null
        }
        val currentOwner = RuntimeOwner(
            runtimeType = runtimeIdentity.runtimeType,
            runtimeVersion = runtimeIdentity.runtimeVersion,
            pid = ProcessHandle.current().pid(),
            claimedAt = System.currentTimeMillis(),
        )
        if (previousOwner?.sameProcess(currentOwner) == true) {
            return null
        }
        write(currentOwner)
        return previousOwner?.let { RuntimeOwnerChangeEvent(it, currentOwner) }
    }

    fun read(): RuntimeOwner? {
        return if (!ownerFile.isFile) null else gson.fromJson(ownerFile.readText(Charsets.UTF_8), RuntimeOwner::class.java)
    }

    private fun write(owner: RuntimeOwner) {
        ownerFile.parentFile?.mkdirs()
        val tempFile = File(ownerFile.parentFile, "${ownerFile.name}.${UUID.randomUUID()}.tmp")
        FileOutputStream(tempFile).use { output ->
            output.write(gson.toJson(owner).toByteArray(Charsets.UTF_8))
            output.flush()
            output.fd.sync()
        }
        try {
            Files.move(
                tempFile.toPath(),
                ownerFile.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(tempFile.toPath(), ownerFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun RuntimeOwner.sameProcess(other: RuntimeOwner): Boolean {
        return runtimeType == other.runtimeType && runtimeVersion == other.runtimeVersion && pid == other.pid
    }
}
