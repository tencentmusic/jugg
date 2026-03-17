package com.sickworm.intellij.jugg.project

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
class FileChangesDetector(
    private val project: Project,
) :
    IFileChangesDetector,
    Disposable
{

    private val logger = JuggLogger.getInstance(project, "FileChangesDetector")

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
        val changedFiles = mutableListOf<File>()
        val deletedFiles = mutableListOf<File>()

        events.forEach { event ->
            when (event) {
                is VFileMoveEvent -> {
                    logger.trace("VFileMoveEvent: ${event.path} -> ${event.oldPath}")
                    deletedFiles.add(File(event.oldPath))
                    changedFiles.add(File(event.path))
                }
                is VFilePropertyChangeEvent -> {
                    logger.trace("VFilePropertyChangeEvent: ${event.path} -> ${event.propertyName}")
                    if (event.propertyName == VirtualFile.PROP_NAME) { // rename file
                        deletedFiles.add(File(event.oldPath))
                        changedFiles.add(File(event.path))
                    }
                }
                is VFileDeleteEvent -> {
                    logger.trace("VFileDeleteEvent: ${event.path}")
                    deletedFiles.add(File(event.path))
                }
                else -> {
                    logger.trace("Other VFileEvent: ${event.path}")
                    changedFiles.add(File(event.path))
                }
            }
        }

        if (changedFiles.isEmpty() && deletedFiles.isEmpty()) return
        listener?.onFileChanges(changedFiles, deletedFiles)
    }

    private val String.virtualFile: VirtualFile?
        get() = VirtualFileManager.getInstance().findFileByNioPath(Paths.get(this))

    override fun dispose() {
        logger.debug("${project.basePath} dispose")
    }

}
