package com.sickworm.intellij.jugg.project.change

import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.AsyncFileListener
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.events.VFileDeleteEvent
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.openapi.vfs.newvfs.events.VFileMoveEvent
import com.intellij.openapi.vfs.newvfs.events.VFilePropertyChangeEvent
import com.sickworm.intellij.jugg.logger.JuggLogger
import java.io.File
import java.nio.file.Paths

/**
 * Listen file changes in project by Intellij callback
 */
class IdeaFileChangeMonitor(
    private val project: Project,
    private val projectDir: File,
) :
    IFileChangeMonitor,
    Disposable
{

    private val logger = JuggLogger.getInstance(project, "IdeaFileChangeMonitor")

    private var listener: FileChangesListener? = null

    override fun startListen(listener: FileChangesListener) {
        this.listener = listener
        initIdeEventOnce()
    }

    private var isInitIdeEvent = false

    @Synchronized
    private fun initIdeEventOnce() {
        if (isInitIdeEvent) return

        logger.debug("Start listen project ${project.basePath}")
        val vfsListener = AsyncFileListener { events ->
            object: AsyncFileListener.ChangeApplier {
                override fun afterVfsChange() {
                    notifyFileChanges(events)
                }
            }
        }
        VirtualFileManager.getInstance().addAsyncFileListener(vfsListener, this)
        Disposer.register(project, this)

        isInitIdeEvent = true
    }

    private fun notifyFileChanges(events: MutableList<out VFileEvent>) {
        logger.trace("[PERF] IdeaFileChangeMonitor.notifyFileChanges, thread=${Thread.currentThread().name}, eventSize=${events.size}")
        val changedFiles = mutableListOf<File>()
        val deletedFiles = mutableListOf<File>()

        events.forEach { event ->
            traceFile(event)
            when (event) {
                is VFileMoveEvent -> {
                    deletedFiles.add(File(event.oldPath))
                    changedFiles.add(File(event.path))
                }
                is VFilePropertyChangeEvent -> {
                    if (event.propertyName == VirtualFile.PROP_NAME) { // rename file
                        deletedFiles.add(File(event.oldPath))
                        changedFiles.add(File(event.path))
                    }
                }
                is VFileDeleteEvent -> {
                    deletedFiles.add(File(event.path))
                }
                else -> {
                    changedFiles.add(File(event.path))
                }
            }
        }

        if (changedFiles.isEmpty() && deletedFiles.isEmpty()) return
        listener?.onFileChanges(changedFiles, deletedFiles)
    }

    private fun traceFile(event: VFileEvent) {
        val path = event.path
        if (!path.startsWith(projectDir.path)) {
            return
        }
        if (event.path.contains("build")) {
            // dong print build/jugg/* e.g. build/jugg/log/compile_latest.log
            return
        }
        when (event) {
            is VFileMoveEvent -> {
                logger.trace("VFileMoveEvent: ${event.path} -> ${event.oldPath}")
            }
            is VFilePropertyChangeEvent -> {
                logger.trace("VFilePropertyChangeEvent: ${event.path} -> ${event.propertyName}")
            }
            is VFileDeleteEvent -> {
                logger.trace("VFileDeleteEvent: ${event.path}")
            }
            else -> {
                logger.trace("Modify VFileEvent: ${event.path}")
            }
        }
    }

    private val String.virtualFile: VirtualFile?
        get() = VirtualFileManager.getInstance().findFileByNioPath(Paths.get(this))

    override fun dispose() {
        logger.debug("${project.basePath} dispose")
    }

}
