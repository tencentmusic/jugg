package com.sickworm.intellij.jugg.project

import com.android.tools.idea.util.toIoFile
import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.AsyncFileListener
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.openapi.vfs.newvfs.events.VFileMoveEvent
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
        val changedFiles = events.flatMap { toFiles(it) }
        if (changedFiles.isEmpty()) return
        listener?.onFileChanges(changedFiles)
    }

    private fun toFiles(event: VFileEvent?): List<File> {
        if (event == null) {
            return emptyList()
        }

        val files: List<File> = when (event) {

            is VFileMoveEvent -> {
                listOf(File(event.oldPath), File(event.newPath))
            }

            is VFilePropertyChangeEvent -> {
                if (event.propertyName == VirtualFile.PROP_NAME) {
                    listOf(File(event.oldPath), File(event.newPath))
                } else {
                    emptyList()
                }
            }

            else -> {
                listOf(File(event.path))
            }
        }

        return files.filter {
            it.absolutePath.startsWith(projectDir.absolutePath + File.separator)
        }
    }

    private val String.virtualFile: VirtualFile?
        get() = VirtualFileManager.getInstance().findFileByNioPath(Paths.get(this))

    override fun dispose() {
        logger.debug("${project.basePath} dispose")
    }

}
