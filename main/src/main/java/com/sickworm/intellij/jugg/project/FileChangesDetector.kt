package com.sickworm.intellij.jugg.project

import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.AsyncFileListener
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.events.VFileCopyEvent
import com.intellij.openapi.vfs.newvfs.events.VFileDeleteEvent
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.openapi.vfs.newvfs.events.VFilePropertyChangeEvent
import com.sickworm.intellij.jugg.logger.JuggLogger
import java.io.File
import java.nio.file.Paths

/**
 * Listen file changes in project
 */
class FileChangesDetector(
    private val project: Project,
    private val projectDir: File,
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
        val changedFiles = events.mapNotNull(::toFile)
        if (changedFiles.isEmpty()) return
        listener?.onFileChanges(changedFiles)
    }

    private fun toFile(event: VFileEvent?): File? {
        if (event == null) {
            return null
        }

//        logger.debug("file event ${event::class.java.name} $event")
        if (event is VFilePropertyChangeEvent) {
            return null
        }

        val virtualFile = if (event is VFileCopyEvent) {
            VirtualFileManager.getInstance().findFileByNioPath(Paths.get(event.path))
        } else {
            event.file
        }

        if (virtualFile == null) {
            return null
        }

        val file = VfsUtil.virtualToIoFile(virtualFile)
        val isMyProjectFile = file.absolutePath.startsWith(projectDir.absolutePath)
        if (!isMyProjectFile) {
            return null
        }
        return file
    }

    override fun dispose() {
        logger.debug("${project.basePath} dispose")
    }

}
