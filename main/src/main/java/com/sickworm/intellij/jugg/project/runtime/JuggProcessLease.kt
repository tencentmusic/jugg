package com.sickworm.intellij.jugg.project.runtime

import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.util.UUID

/** A process-lifetime lease, independent of task ownership and project count. */
class JuggProcessLease private constructor(
    private val file: File,
    private val handle: RandomAccessFile,
    private val lock: FileLock,
) : AutoCloseable {
    override fun close() {
        lock.release()
        handle.close()
        file.delete()
    }

    companion object {
        fun acquire(type: String, root: File = JuggGlobalPathManager.rootDir,
                    userHome: File = File(System.getProperty("user.home"))): JuggProcessLease {
            require(type == "idea" || type == "standalone")
            val dir = JuggGlobalPathManager.processLeaseDir(userHome)
            check(dir.isDirectory || dir.mkdirs()) { "Cannot create process lease directory: $dir" }
            val file = File(dir, "${ProcessHandle.current().pid()}-${UUID.randomUUID()}.lock")
            val handle = RandomAccessFile(file, "rw")
            try {
                val lock = handle.channel.lock(0, 1, false)
                // Metadata lives outside the leased byte so Windows permits cross-process reads.
                handle.seek(1)
                handle.write("$type\n${ProcessHandle.current().pid()}\n${root.canonicalPath}".toByteArray(Charsets.UTF_8))
                return JuggProcessLease(file, handle, lock)
            } catch (e: Throwable) {
                handle.close()
                file.delete()
                throw e
            }
        }

        fun stopStandaloneProcesses(root: File = JuggGlobalPathManager.rootDir) {
            val rootKey = root.canonicalFile
            val processes = ProcessHandle.allProcesses().use { stream ->
                stream.filter { process ->
                    val args = process.info().arguments().orElse(emptyArray())
                    val processRoot = args.firstOrNull { it.startsWith("-Djugg.root.dir=") }
                        ?.substringAfter('=')?.let(::File)
                        ?: JuggGlobalPathManager.preferredRootDir(JuggGlobalPathManager.currentUserHome)
                    process.pid() != ProcessHandle.current().pid() &&
                        "com.sickworm.intellij.jugg.bootstrap.StandaloneBootstrap" in args &&
                        processRoot.canonicalFile == rootKey
                }.toArray().map { it as ProcessHandle }
            }
            processes.forEach { it.destroy() }
            val deadline = System.nanoTime() + 5_000_000_000L
            while (processes.any { it.isAlive } && System.nanoTime() < deadline) Thread.sleep(20)
            processes.filter { it.isAlive }.forEach { it.destroyForcibly() }
            processes.forEach { it.onExit().get(5, java.util.concurrent.TimeUnit.SECONDS) }
        }

        fun liveProcesses(type: String, root: File, userHome: File = File(System.getProperty("user.home"))): Set<Long> {
            val dir = JuggGlobalPathManager.processLeaseDir(userHome)
            val files = if (!dir.exists()) emptyArray() else checkNotNull(dir.listFiles()) { "Cannot list process leases: $dir" }
            return files.filter { it.extension == "lock" }.mapNotNull { file ->
                RandomAccessFile(file, "rw").use { handle ->
                    val probe = try { handle.channel.tryLock(0, 1, false) } catch (_: OverlappingFileLockException) { null }
                    if (probe != null) {
                        probe.release()
                        return@use null
                    }
                    handle.seek(1)
                    val bytes = ByteArray((handle.length() - 1).coerceAtLeast(0).toInt())
                    handle.readFully(bytes)
                    val content = bytes.toString(Charsets.UTF_8).split('\n')
                    if (content.getOrNull(0) == type && content.getOrNull(2) == root.canonicalPath) content.getOrNull(1)?.toLongOrNull() else null
                }
            }.toSet()
        }
    }
}
