package com.sickworm.intellij.jugg.project.change

import com.intellij.openapi.diagnostic.Logger
import java.io.File
import java.nio.file.FileSystems
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardWatchEventKinds.ENTRY_CREATE
import java.nio.file.StandardWatchEventKinds.ENTRY_DELETE
import java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY
import java.nio.file.StandardWatchEventKinds.OVERFLOW
import java.nio.file.ClosedWatchServiceException
import java.nio.file.WatchKey
import java.nio.file.WatchService
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Watches a project tree with NIO WatchService and emits debounced cross-platform change batches. */
class WatchServiceFileChangeMonitor(
    projectDir: File,
    private val logger: Logger,
    private val debounceMs: Long = 200L,
) : IFileChangeMonitor {

    private val rootPath = projectDir.toPath().toAbsolutePath().normalize()
    private val watchService: WatchService = FileSystems.getDefault().newWatchService()
    private val watchDirectories = mutableMapOf<WatchKey, Path>()
    private val pendingChangedPaths = linkedSetOf<Path>()
    private val pendingDeletedPaths = linkedSetOf<Path>()
    private val scheduler = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "jugg-watch-debounce").apply { isDaemon = true }
    }
    private val started = AtomicBoolean()
    private val closed = AtomicBoolean()
    private var listener: FileChangesListener? = null
    private var flushFuture: ScheduledFuture<*>? = null
    private var watchThread: Thread? = null

    override fun startListen(listener: FileChangesListener) {
        check(!closed.get()) { "WatchServiceFileChangeMonitor is closed" }
        this.listener = listener
        if (!started.compareAndSet(false, true)) return
        require(Files.isDirectory(rootPath)) { "Project directory does not exist: $rootPath" }
        registerTree(rootPath)
        watchThread = Thread(::watchLoop, "jugg-watch-service").apply {
            isDaemon = true
            start()
        }
    }

    private fun watchLoop() {
        while (started.get()) {
            val key = try {
                watchService.take()
            } catch (_: InterruptedException) {
                break
            } catch (_: ClosedWatchServiceException) {
                break
            } catch (e: Exception) {
                if (started.get()) logger.warn("WatchService stopped unexpectedly", e)
                break
            }
            try {
                processKey(key)
            } catch (e: Exception) {
                logger.warn("Process WatchService event failed", e)
            }
        }
    }

    private fun processKey(key: WatchKey) {
        val directory = synchronized(watchDirectories) { watchDirectories[key] }
        if (directory == null) {
            key.reset()
            return
        }
        key.pollEvents().forEach { event ->
            if (event.kind() == OVERFLOW) {
                listener?.onOverflow()
                return@forEach
            }
            @Suppress("UNCHECKED_CAST")
            val relativePath = (event.context() as Path)
            val path = directory.resolve(relativePath).toAbsolutePath().normalize()
            when (event.kind()) {
                ENTRY_DELETE -> recordDeleted(path)
                ENTRY_CREATE, ENTRY_MODIFY -> {
                    if (event.kind() == ENTRY_CREATE && Files.isDirectory(path)) registerTree(path)
                    recordChanged(path)
                }
            }
        }
        if (!key.reset()) synchronized(watchDirectories) { watchDirectories.remove(key) }
    }

    private fun registerTree(start: Path) {
        if (!Files.exists(start)) return
        Files.walkFileTree(start, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                if (dir != rootPath && dir.fileName?.toString() in ignoredDirectoryNames) {
                    return FileVisitResult.SKIP_SUBTREE
                }
                val key = dir.register(watchService, ENTRY_CREATE, ENTRY_MODIFY, ENTRY_DELETE)
                synchronized(watchDirectories) { watchDirectories[key] = dir }
                return FileVisitResult.CONTINUE
            }
        })
    }

    private fun recordChanged(path: Path) {
        synchronized(this) {
            pendingDeletedPaths.remove(path)
            pendingChangedPaths.add(path)
            scheduleFlush()
        }
    }

    private fun recordDeleted(path: Path) {
        synchronized(this) {
            pendingChangedPaths.remove(path)
            pendingDeletedPaths.add(path)
            scheduleFlush()
        }
    }

    private fun scheduleFlush() {
        flushFuture?.cancel(false)
        flushFuture = scheduler.schedule(::flushChanges, debounceMs, TimeUnit.MILLISECONDS)
    }

    private fun flushChanges() {
        val changedFiles: List<File>
        val deletedFiles: List<File>
        synchronized(this) {
            changedFiles = pendingChangedPaths.map(Path::toFile)
            deletedFiles = pendingDeletedPaths.map(Path::toFile)
            pendingChangedPaths.clear()
            pendingDeletedPaths.clear()
        }
        if (changedFiles.isNotEmpty() || deletedFiles.isNotEmpty()) {
            listener?.onFileChanges(changedFiles, deletedFiles)
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        started.set(false)
        flushFuture?.cancel(false)
        flushChanges()
        watchService.close()
        scheduler.shutdownNow()
        watchThread?.interrupt()
    }

    companion object {
        private val ignoredDirectoryNames = setOf(".git", ".gradle", ".idea", "build")
    }
}
