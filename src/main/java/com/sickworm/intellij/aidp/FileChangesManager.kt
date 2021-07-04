package com.sickworm.intellij.aidp

import com.intellij.openapi.Disposable
import com.intellij.openapi.externalSystem.util.ExternalSystemApiUtil
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.AsyncFileListener
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileEvent
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import java.util.concurrent.Executors

/**
 * 文件变化监听
 */
class FileChangesManager(private val project: Project,
                         private val projectDir: String): Disposable {

    private val logger = AidpLogger.getInstance(project, "#AIDP-FileChangesManager")

    private val inspectFileExtensions = listOf("java", "kt")
    private val operateThread = Executors.newSingleThreadExecutor()
    private val changedFilesMap = mutableMapOf<String, ChangeFileInfo>()
    private var listener: FileChangesListener? = null

    private val sourceRoots: List<String> = mutableListOf<String>().apply {
        ModuleManager.getInstance(project).modules.forEach { module ->
            val basePath = ExternalSystemApiUtil.getExternalProjectPath(module)?: run {
                logger.warn("module externalProjectPath not found $module")
                return@forEach
            }
            // 过滤掉自动生成的 source 目录，一般在 build/generated
            val filteredSourceRoot = ModuleRootManager.getInstance(module).sourceRoots.filter { sourceRoot ->
                if (sourceRoot.path.length < basePath.length + 1) {
                    return@filter true
                }
                val relativePath = sourceRoot.path.substring(basePath.length + 1)
                !relativePath.startsWith("build")
            }.map {
                it.path
            }
            addAll(filteredSourceRoot)
        }
    }

    fun startListen(listener: FileChangesListener) {
        logger.info("startListen file changes for project $projectDir")
        this.listener = listener
        listenFileChanges()
        Disposer.register(project, this)
    }

    override fun dispose() {
        logger.info("$projectDir dispose")
        operateThread.shutdown()
    }

    private fun listenFileChanges() {
        val vfsListener = object: AsyncFileListener {
            override fun prepareChange(events: MutableList<out VFileEvent>): AsyncFileListener.ChangeApplier? {
                val filteredEvents = events.filter { isNeedDeploy(it) }
                if (filteredEvents.isEmpty()) return null

                return object: AsyncFileListener.ChangeApplier {
                    override fun afterVfsChange() {
                        operateThread.execute {
                            notifyFileChanges(filteredEvents)
                        }
                    }
                }
            }

            private fun notifyFileChanges(events: List<VFileEvent>) {
                val files = events
                    .mapNotNull { it.file }
                    .map { ChangeFileInfo(it) }
                synchronized(changedFilesMap) {
                    files.forEach { file ->
                        changedFilesMap[file.file.path] = file
                    }
                    logger.info("onFileChanges $files")
                }
                listener?.onFileChanges(files)
            }
        }
        VirtualFileManager.getInstance().addAsyncFileListener(vfsListener, this)
    }

    /**
     * 过滤非监听文件
     */
    private fun isNeedDeploy(event: VFileEvent?): Boolean {
        val virtualFile = event?.file
        // file not exists
        if (virtualFile == null || !virtualFile.exists()) {
            logger.debug("event $event, file not exists, don't need deploy")
            return false
        }

        // is directory
        if (virtualFile.isDirectory) {
            logger.debug("event $event, is directory, don't need deploy")
            return false
        }

        // not in source directory
        val isInSourceRoots = sourceRoots.find { virtualFile.path.startsWith(it) } != null
        if (!isInSourceRoots) {
            logger.debug("event $event, not in source root, don't need deploy")
            return false
        }

        // extension not match
        if (!inspectFileExtensions.contains(virtualFile.extension)) {
            logger.debug("event $event, extension ignore, don't need deploy")
            return false
        }

        return true
    }
}

interface FileChangesListener {
    fun onFileChanges(changeFiles: List<ChangeFileInfo>)
}

data class ChangeFileInfo(
    val file: VirtualFile
    )