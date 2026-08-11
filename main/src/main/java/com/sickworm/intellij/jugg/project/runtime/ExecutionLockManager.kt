package com.sickworm.intellij.jugg.project.runtime

import com.google.gson.Gson
import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.jugg.runtime.PluginInfoReader
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
import java.util.concurrent.Semaphore
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

/** Coordinates project runtime ownership across runtime processes. */
internal interface IExecutionLockManager {
    fun <T> withProjectLock(command: String, action: () -> T): T
    fun <T : Any> tryWithProjectLock(command: String, action: () -> T): T?
    fun readProjectLockOwner(): ExecutionLockOwner?
}

/**
 * Serializes a resource-owner mutation of Jugg-owned global files across runtime processes.
 *
 * The action must finish synchronously and only perform bounded local resource reads and writes. It must not launch
 * asynchronous work, invoke business callbacks, wait for network, processes, threads, or futures, acquire business
 * monitors, or attempt to acquire a Project Runtime Lock.
 */
internal fun <T> withGlobalResourceLock(
    command: String,
    globalRootDir: File = JuggGlobalPathManager.rootDir,
    action: () -> T,
): T {
    val lockFile = JuggGlobalPathManager.globalLockFile(globalRootDir)
    return ExecutionFileLock(RuntimeIdentity("resource", PluginInfoReader.getPluginVersion())).withLock(
        lockFile = lockFile,
        ownerFile = File(lockFile.parentFile, "${lockFile.name}.owner.json"),
        command = command,
        scopeDir = globalRootDir,
        action = { ExecutionLockOrderGuard.withGlobalResourceLockHeld(action) },
    )
}

/** Enforces the one-way Project Runtime Lock to Global Resource Lock acquisition order on the current thread. */
private object ExecutionLockOrderGuard {
    private val globalResourceLockDepth = ThreadLocal<Int>()

    fun <T> withGlobalResourceLockHeld(action: () -> T): T {
        val previousDepth = globalResourceLockDepth.get() ?: 0
        globalResourceLockDepth.set(previousDepth + 1)
        try {
            return action()
        } finally {
            if (previousDepth == 0) globalResourceLockDepth.remove() else globalResourceLockDepth.set(previousDepth)
        }
    }

    fun checkProjectLockAllowed(command: String) {
        check((globalResourceLockDepth.get() ?: 0) == 0) {
            "Cannot acquire Project Runtime Lock while current thread holds Global Resource Lock: $command"
        }
    }
}

/**
 * Shares one project lease inside this runtime while coordinating IDEA and standalone runtimes by file lock.
 */
internal class FileExecutionLockManager(
    private val pathManager: JuggPathManager,
    runtimeIdentity: RuntimeIdentity,
    logger: Logger? = null,
) : IExecutionLockManager {

    private val projectLock = RuntimeSharedExecutionFileLock(runtimeIdentity, logger)

    override fun <T> withProjectLock(command: String, action: () -> T): T {
        ExecutionLockOrderGuard.checkProjectLockAllowed(command)
        return projectLock.withLock(
            lockFile = pathManager.runtimeLockFile,
            ownerFile = pathManager.runtimeLockOwnerFile,
            command = command,
            scopeDir = pathManager.projectDir,
            action = action,
        )
    }

    override fun <T : Any> tryWithProjectLock(command: String, action: () -> T): T? {
        ExecutionLockOrderGuard.checkProjectLockAllowed(command)
        return projectLock.tryWithLock(
            lockFile = pathManager.runtimeLockFile,
            ownerFile = pathManager.runtimeLockOwnerFile,
            command = command,
            scopeDir = pathManager.projectDir,
            action = action,
        )
    }

    override fun readProjectLockOwner(): ExecutionLockOwner? {
        val owner = projectLock.readOwner(pathManager.runtimeLockOwnerFile) ?: return null
        if (projectLock.isHeld(pathManager.runtimeLockFile)) return owner
        pathManager.runtimeLockOwnerFile.delete()
        return null
    }
}

/** Keeps a reference-counted project lease that can be shared by concurrent tasks in one runtime. */
private class RuntimeSharedExecutionFileLock(
    private val runtimeIdentity: RuntimeIdentity,
    private val logger: Logger?,
) {
    private val stateLock = ReentrantLock(true)
    private var lease: SharedFileLease? = null
    private var referenceCount = 0

    fun <T> withLock(
        lockFile: File,
        ownerFile: File,
        command: String,
        scopeDir: File,
        action: () -> T,
    ): T {
        retain(lockFile, ownerFile, command, scopeDir)
        try {
            return action()
        } finally {
            release()
        }
    }

    fun <T : Any> tryWithLock(
        lockFile: File,
        ownerFile: File,
        command: String,
        scopeDir: File,
        action: () -> T,
    ): T? {
        if (!tryRetain(lockFile, ownerFile, command, scopeDir)) return null
        try {
            return action()
        } finally {
            release()
        }
    }

    fun readOwner(ownerFile: File): ExecutionLockOwner? = ExecutionFileLockSupport.readOwner(ownerFile)

    fun isHeld(lockFile: File): Boolean = ExecutionFileLockSupport.isHeld(lockFile)

    private fun retain(lockFile: File, ownerFile: File, command: String, scopeDir: File) {
        stateLock.lockInterruptibly()
        try {
            if (lease != null) {
                referenceCount++
                return
            }
            lease = acquireLease(lockFile, ownerFile, command, scopeDir)
            referenceCount = 1
        } finally {
            stateLock.unlock()
        }
    }

    private fun tryRetain(lockFile: File, ownerFile: File, command: String, scopeDir: File): Boolean {
        if (!stateLock.tryLock()) return false
        try {
            if (lease != null) {
                referenceCount++
                return true
            }
            lease = tryAcquireLease(lockFile, ownerFile, command, scopeDir) ?: return false
            referenceCount = 1
            return true
        } finally {
            stateLock.unlock()
        }
    }

    private fun release() {
        var releasedLease: SharedFileLease? = null
        stateLock.lock()
        try {
            check(referenceCount > 0)
            referenceCount--
            if (referenceCount == 0) {
                releasedLease = lease
                lease = null
            }
        } finally {
            stateLock.unlock()
        }
        releasedLease?.close()
    }

    private fun acquireLease(lockFile: File, ownerFile: File, command: String, scopeDir: File): SharedFileLease {
        val canonicalLockFile = lockFile.canonicalFile
        val runtimePermit = ExecutionFileLockSupport.runtimePermit(canonicalLockFile)
        var waitStart: Long? = null
        var owner: ExecutionLockOwner? = null
        if (!runtimePermit.tryAcquire()) {
            waitStart = System.currentTimeMillis()
            owner = ExecutionFileLockSupport.readOwner(ownerFile)
            logContention(command, owner)
            runtimePermit.acquire()
        }
        var acquiredLease = openLease(canonicalLockFile, ownerFile, runtimePermit) { channel ->
            ExecutionFileLockSupport.tryAcquireFileLock(channel)
        }
        if (acquiredLease == null) {
            if (waitStart == null) {
                waitStart = System.currentTimeMillis()
                owner = ExecutionFileLockSupport.readOwner(ownerFile)
                logContention(command, owner)
            }
            runtimePermit.acquire()
            acquiredLease = checkNotNull(openLease(canonicalLockFile, ownerFile, runtimePermit) { channel ->
                ExecutionFileLockSupport.acquireFileLock(channel)
            })
        }
        try {
            ExecutionFileLockSupport.writeOwner(runtimeIdentity, ownerFile, command, scopeDir)
            waitStart?.let { start ->
                logger?.debug("Runtime lock acquired after contention: runtime=${runtimeIdentity.runtimeType}, " +
                        "command=$command, ownerRuntime=${owner?.runtimeType ?: "unknown"}, " +
                        "waitMs=${System.currentTimeMillis() - start}")
            }
            return acquiredLease
        } catch (e: Throwable) {
            acquiredLease.close()
            throw e
        }
    }

    private fun logContention(command: String, owner: ExecutionLockOwner?) {
        logger?.debug("Runtime lock contention: runtime=${runtimeIdentity.runtimeType}, command=$command, " +
                "ownerRuntime=${owner?.runtimeType ?: "unknown"}, ownerPid=${owner?.pid ?: "unknown"}, " +
                "ownerCommand=${owner?.command ?: "unknown"}, ownerJobId=${owner?.jobId ?: "unknown"}")
    }

    private fun tryAcquireLease(
        lockFile: File,
        ownerFile: File,
        command: String,
        scopeDir: File,
    ): SharedFileLease? {
        val canonicalLockFile = lockFile.canonicalFile
        val runtimePermit = ExecutionFileLockSupport.runtimePermit(canonicalLockFile)
        if (!runtimePermit.tryAcquire()) return null
        val acquiredLease = openLease(canonicalLockFile, ownerFile, runtimePermit) { channel ->
            ExecutionFileLockSupport.tryAcquireFileLock(channel)
        } ?: return null
        try {
            ExecutionFileLockSupport.writeOwner(runtimeIdentity, ownerFile, command, scopeDir)
            return acquiredLease
        } catch (e: Throwable) {
            acquiredLease.close()
            throw e
        }
    }

    private fun openLease(
        lockFile: File,
        ownerFile: File,
        runtimePermit: Semaphore,
        acquire: (FileChannel) -> FileLock?,
    ): SharedFileLease? {
        lockFile.parentFile?.mkdirs()
        var channel: FileChannel? = null
        var transferred = false
        try {
            val openedChannel = RandomAccessFile(lockFile, "rw").channel
            channel = openedChannel
            val fileLock = acquire(openedChannel) ?: return null
            transferred = true
            return SharedFileLease(ownerFile, openedChannel, fileLock, runtimePermit)
        } finally {
            if (!transferred) {
                try {
                    channel?.close()
                } finally {
                    runtimePermit.release()
                }
            }
        }
    }
}

/** Owns the physical project file lock until the runtime reference count reaches zero. */
private class SharedFileLease(
    private val ownerFile: File,
    private val channel: FileChannel,
    private val fileLock: FileLock,
    private val runtimePermit: Semaphore,
) : AutoCloseable {
    override fun close() {
        try {
            ownerFile.delete()
        } finally {
            try {
                fileLock.release()
            } finally {
                try {
                    channel.close()
                } finally {
                    runtimePermit.release()
                }
            }
        }
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
        val jvmLock = ExecutionFileLockSupport.jvmLock(canonicalLockFile)
        jvmLock.lockInterruptibly()
        try {
            if (jvmLock.holdCount > 1) {
                return action()
            }
            canonicalLockFile.parentFile?.mkdirs()
            RandomAccessFile(canonicalLockFile, "rw").channel.use { channel ->
                ExecutionFileLockSupport.acquireFileLock(channel).use {
                    ExecutionFileLockSupport.writeOwner(runtimeIdentity, ownerFile, command, scopeDir)
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

    fun <T : Any> tryWithLock(
        lockFile: File,
        ownerFile: File,
        command: String,
        scopeDir: File,
        action: () -> T,
    ): T? {
        val canonicalLockFile = lockFile.canonicalFile
        val jvmLock = ExecutionFileLockSupport.jvmLock(canonicalLockFile)
        if (!jvmLock.tryLock()) return null
        try {
            if (jvmLock.holdCount > 1) return action()
            canonicalLockFile.parentFile?.mkdirs()
            RandomAccessFile(canonicalLockFile, "rw").channel.use { channel ->
                val fileLock = ExecutionFileLockSupport.tryAcquireFileLock(channel) ?: return null
                fileLock.use {
                    ExecutionFileLockSupport.writeOwner(runtimeIdentity, ownerFile, command, scopeDir)
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

    fun readOwner(ownerFile: File): ExecutionLockOwner? = ExecutionFileLockSupport.readOwner(ownerFile)

    fun isHeld(lockFile: File): Boolean = ExecutionFileLockSupport.isHeld(lockFile)
}

private object ExecutionFileLockSupport {
    private const val FILE_LOCK_RETRY_MILLIS = 25L
    private val jvmLocks = ConcurrentHashMap<String, ReentrantLock>()
    private val runtimePermits = ConcurrentHashMap<String, Semaphore>()

    fun jvmLock(lockFile: File): ReentrantLock {
        return jvmLocks.computeIfAbsent(lockFile.path) { ReentrantLock(true) }
    }

    fun runtimePermit(lockFile: File): Semaphore {
        return runtimePermits.computeIfAbsent(lockFile.path) { Semaphore(1, true) }
    }

    fun acquireFileLock(channel: FileChannel): FileLock {
        while (true) {
            try {
                return channel.lock()
            } catch (_: OverlappingFileLockException) {
                // Hot-updated and embedded classes can have isolated JVM lock maps in different classloaders.
                Thread.sleep(FILE_LOCK_RETRY_MILLIS)
            }
        }
    }

    fun tryAcquireFileLock(channel: FileChannel): FileLock? {
        return try {
            channel.tryLock()
        } catch (_: OverlappingFileLockException) {
            null
        }
    }

    fun writeOwner(runtimeIdentity: RuntimeIdentity, ownerFile: File, command: String, scopeDir: File) {
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

}
