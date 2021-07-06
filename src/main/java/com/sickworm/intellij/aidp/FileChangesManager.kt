package com.sickworm.intellij.aidp

import com.intellij.openapi.Disposable
import com.intellij.openapi.externalSystem.util.ExternalSystemApiUtil
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.AsyncFileListener
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.events.VFileContentChangeEvent
import com.intellij.openapi.vfs.newvfs.events.VFileDeleteEvent
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.openapi.vfs.newvfs.events.VFilePropertyChangeEvent
import org.jetbrains.jps.model.java.JavaResourceRootType
import org.jetbrains.jps.model.java.JavaSourceRootType
import org.jetbrains.kotlin.config.ResourceKotlinRootType
import org.jetbrains.kotlin.config.SourceKotlinRootType
import java.util.concurrent.Executors

/**
 * 文件变化监听
 */
class FileChangesManager(private val project: Project,
                         private val projectDir: String): Disposable {

    private val logger = AidpLogger.getInstance(project, "#AIDP-FileChangesManager")

    private var listener: FileChangesListener? = null

    private var sourceRoots: List<String> = emptyList()
    private var resourceRoots: List<String> = emptyList()

    private val sourceExtensions = listOf("java", "kt")

    fun startListen(listener: FileChangesListener) {
        logger.info("start listen project $projectDir")
        this.listener = listener

        initFileRoots()
        logger.debug("start listen source roots: $sourceRoots,\nresource roots: $resourceRoots\n")

        listenFileChanges()
        Disposer.register(project, this)
    }

    private fun initFileRoots() {
        val sourceRoots = mutableListOf<String>()
        val resourceRoots = mutableListOf<String>()

        ModuleManager.getInstance(project).modules.forEach { module ->
            val moduleManager = ModuleRootManager.getInstance(module)
            val subSourceRoots = moduleManager.getSourceRoots(
                setOf(JavaSourceRootType.SOURCE, SourceKotlinRootType))
            sourceRoots.addAll(subSourceRoots.map { it.path })

            val subResourceRoots = moduleManager.getSourceRoots(
                setOf(JavaResourceRootType.RESOURCE, ResourceKotlinRootType))
            resourceRoots.addAll(subResourceRoots.map { it.path })
        }

        this.sourceRoots = sourceRoots
        this.resourceRoots = resourceRoots
    }

    override fun dispose() {
        logger.info("$projectDir dispose")
    }

    private fun listenFileChanges() {
        val vfsListener = object: AsyncFileListener {
            override fun prepareChange(events: MutableList<out VFileEvent>): AsyncFileListener.ChangeApplier? {
                val filteredEvents = events.filter { isNeedDeploy(it) }
                if (filteredEvents.isEmpty()) return null

                return object: AsyncFileListener.ChangeApplier {
                    override fun afterVfsChange() {
                        notifyFileChanges(filteredEvents)
                    }
                }
            }

            private fun notifyFileChanges(events: List<VFileEvent>) {
                val files = events
                    .mapNotNull { it.file }
                    .map { ChangeFileInfo(it) }
                logger.info("onFileChanges $files")
                listener?.onFileChanges(files)
            }
        }
        VirtualFileManager.getInstance().addAsyncFileListener(vfsListener, this)
    }

    /**
     * 过滤非监听文件
     */
    private fun isNeedDeploy(event: VFileEvent?): Boolean {
        if (event == null) {
            return false
        }
        if (event is VFileDeleteEvent || event is VFilePropertyChangeEvent) {
            return false
        }

        logger.debug("file event ${event::class.java.name} $event")

        val virtualFile = event.file
        // file not exists
        if (virtualFile == null || !virtualFile.exists()) {
            return false
        }
        // is directory
        if (virtualFile.isDirectory) {
            return false
        }

        val isInSourceRoots = sourceRoots.any { virtualFile.path.startsWith(it) }
        if (isInSourceRoots) {
            // extension not match
            if (!sourceExtensions.contains(virtualFile.extension)) {
                logger.debug("file event $event, extension ignore, don't need deploy")
                return false
            }
            logger.debug("source file changed, event $event")
            return true
        }

        val isInResourceRoots = resourceRoots.any { virtualFile.path.startsWith(it) }
        if (isInResourceRoots) {
            logger.debug("resource file changed, event $event")
            return true
        }

        return false
    }
}

interface FileChangesListener {
    fun onFileChanges(changeFiles: List<ChangeFileInfo>)
}

data class ChangeFileInfo(
    val file: VirtualFile,
    val type: CompileFileInfo.Type = CompileFileInfo.getTypeByExtension(file.name)
)