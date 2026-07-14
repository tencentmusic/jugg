package com.sickworm.intellij.jugg.project

import com.google.gson.Gson
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock

/** Identifies the runtime process that owns a project or global execution lock. */
internal data class RuntimeIdentity(
    val runtimeType: String,
    val runtimeVersion: String,
)

/** Describes the current owner of an execution lock for diagnostics and busy responses. */
internal data class ExecutionLockOwner(
    val runtimeType: String,
    val pid: Long,
    val runtimeVersion: String,
    val jobId: String,
    val command: String,
    val acquiredAt: Long,
    val projectDir: String,
)

/** Serializes project and global write transactions across threads and runtime processes. */
internal interface IExecutionLockManager {
    fun <T> withProjectLock(command: String, action: () -> T): T
    fun <T> withGlobalLock(command: String, action: () -> T): T
    fun readProjectLockOwner(): ExecutionLockOwner?
}

/** Serializes writes to Jugg-owned global resources across threads and runtime processes. */
internal class GlobalExecutionLock(
    runtimeIdentity: RuntimeIdentity,
    private val globalRootDir: File = JuggGlobalPathManager.rootDir,
) {
    private val fileLock = ExecutionFileLock(runtimeIdentity)

    fun <T> withLock(command: String, action: () -> T): T {
        val lockFile = JuggGlobalPathManager.globalLockFile(globalRootDir)
        return fileLock.withLock(
            lockFile = lockFile,
            ownerFile = File(lockFile.parentFile, "${lockFile.name}.owner.json"),
            command = command,
            scopeDir = globalRootDir,
            action = action,
        )
    }
}

/**
 * Uses a process-wide reentrant lock plus a NIO file lock to coordinate IDEA and standalone runtimes.
 * Nested calls on the owning thread reuse the outer file lock and preserve its diagnostic metadata.
 */
internal class FileExecutionLockManager(
    private val pathManager: JuggPathManager,
    runtimeIdentity: RuntimeIdentity,
    globalRootDir: File = JuggGlobalPathManager.rootDir,
) : IExecutionLockManager {

    private val fileLock = ExecutionFileLock(runtimeIdentity)
    private val globalExecutionLock = GlobalExecutionLock(runtimeIdentity, globalRootDir)

    override fun <T> withProjectLock(command: String, action: () -> T): T {
        return fileLock.withLock(
            lockFile = pathManager.runtimeLockFile,
            ownerFile = pathManager.runtimeLockOwnerFile,
            command = command,
            scopeDir = pathManager.projectDir,
            action = action,
        )
    }

    override fun <T> withGlobalLock(command: String, action: () -> T): T {
        return globalExecutionLock.withLock(command, action)
    }

    override fun readProjectLockOwner(): ExecutionLockOwner? {
        val owner = fileLock.readOwner(pathManager.runtimeLockOwnerFile) ?: return null
        if (fileLock.isHeld(pathManager.runtimeLockFile)) return owner
        pathManager.runtimeLockOwnerFile.delete()
        return null
    }
}

private class ExecutionFileLock(
    private val runtimeIdentity: RuntimeIdentity,
) {

    fun <T> withLock(
        lockFile: File,
        ownerFile: File,
        command: String,
        scopeDir: File,
        action: () -> T,
    ): T {
        val canonicalLockFile = lockFile.canonicalFile
        val jvmLock = jvmLocks.computeIfAbsent(canonicalLockFile.path) { ReentrantLock(true) }
        jvmLock.lockInterruptibly()
        try {
            if (jvmLock.holdCount > 1) {
                return action()
            }
            canonicalLockFile.parentFile?.mkdirs()
            RandomAccessFile(canonicalLockFile, "rw").channel.use { channel ->
                acquireFileLock(channel).use {
                    writeOwner(ownerFile, command, scopeDir)
                    try {
                        return action()
                    } finally {
                        ownerFile.delete()
                    }
                }
            }
        } finally {
            jvmLock.unlock()
        }
    }

    private fun acquireFileLock(channel: FileChannel): FileLock {
        while (true) {
            try {
                return channel.lock()
            } catch (_: OverlappingFileLockException) {
                // Hot-updated and embedded classes can have isolated JVM lock maps in different classloaders.
                Thread.sleep(FILE_LOCK_RETRY_MILLIS)
            }
        }
    }

    private fun writeOwner(ownerFile: File, command: String, scopeDir: File) {
        val owner = ExecutionLockOwner(
            runtimeType = runtimeIdentity.runtimeType,
            pid = ProcessHandle.current().pid(),
            runtimeVersion = runtimeIdentity.runtimeVersion,
            jobId = UUID.randomUUID().toString(),
            command = command,
            acquiredAt = System.currentTimeMillis(),
            projectDir = scopeDir.canonicalPath,
        )
        writeTextAtomically(ownerFile, Gson().toJson(owner))
    }

    fun readOwner(ownerFile: File): ExecutionLockOwner? {
        return try {
            if (!ownerFile.isFile) null else Gson().fromJson(ownerFile.readText(), ExecutionLockOwner::class.java)
        } catch (_: Exception) {
            null
        }
    }

    fun isHeld(lockFile: File): Boolean {
        lockFile.parentFile?.mkdirs()
        return try {
            RandomAccessFile(lockFile, "rw").channel.use { channel ->
                val lock = channel.tryLock() ?: return true
                lock.release()
                false
            }
        } catch (_: OverlappingFileLockException) {
            true
        } catch (_: Exception) {
            true
        }
    }

    private fun writeTextAtomically(target: File, content: String) {
        target.parentFile?.mkdirs()
        val temp = File(target.parentFile, "${target.name}.${UUID.randomUUID()}.tmp")
        FileOutputStream(temp).use { output ->
            output.write(content.toByteArray(Charsets.UTF_8))
            output.flush()
            output.fd.sync()
        }
        moveAtomically(temp, target)
    }

    private fun moveAtomically(source: File, target: File) {
        try {
            Files.move(
                source.toPath(),
                target.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private companion object {
        const val FILE_LOCK_RETRY_MILLIS = 25L
        val jvmLocks = ConcurrentHashMap<String, ReentrantLock>()
    }
}
