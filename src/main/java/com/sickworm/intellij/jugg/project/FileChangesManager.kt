package com.sickworm.intellij.jugg.project

import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.AsyncFileListener
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.events.VFileCopyEvent
import com.intellij.openapi.vfs.newvfs.events.VFileDeleteEvent
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.openapi.vfs.newvfs.events.VFilePropertyChangeEvent
import com.sickworm.intellij.jugg.compiler.CompileFile
import com.sickworm.intellij.jugg.compiler.ICompileContext
import com.sickworm.intellij.jugg.compiler.ModuleInfo
import com.sickworm.intellij.jugg.relativePath
import java.io.File
import java.nio.file.Paths

/**
 * Manage file changes in project
 */
class FileChangesManager(
    private val project: Project,
    private val projectDir: String,
    private val virtualFileManager: VirtualFileManager = VirtualFileManager.getInstance() // for mock
): Disposable {

    private val logger = JuggLogger.getInstance(project, "#Jugg-FileChangesManager")

    private var listener: FileChangesListener? = null

    private var compileContext: ICompileContext? = null

    fun startListen(compileContext: ICompileContext, listener: FileChangesListener) {
        logger.info("start listen project $projectDir")
        this.compileContext = compileContext
        this.listener = listener

        initRootDirs(compileContext)
        initIdeEvent()
    }

    private fun initRootDirs(compileContext: ICompileContext) {
        val sourceDirs = compileContext.modules.values.flatMap { it.sourceDirs }
        val resourceDirs = compileContext.modules.values.flatMap { it.resourceDirs }
        val assetDirs = compileContext.modules.values.flatMap { it.assetsDirs }
        logger.debug("""
            |start listen.
            |    source dirs:
            |        ${sourceDirs.map { it.path }.relativePath(projectDir) }
            |    resource dirs:
            |        ${resourceDirs.map { it.path }.relativePath(projectDir) }
            |    asset dirs:
            |        ${assetDirs.map { it.path }.relativePath(projectDir) }
            |""".trimMargin())
    }

    private fun initIdeEvent() {
        val vfsListener = AsyncFileListener { events ->
            object: AsyncFileListener.ChangeApplier {
                override fun afterVfsChange() {
                    notifyFileChanges(events)
                }
            }
        }
        virtualFileManager.addAsyncFileListener(vfsListener, this)
        Disposer.register(project, this)
    }

    override fun dispose() {
        logger.info("$projectDir dispose")
    }

    private fun notifyFileChanges(events: MutableList<out VFileEvent>) {
        val changeFiles = events.mapNotNull(::filterDeployFile)
        if (changeFiles.isEmpty()) return
        listener?.onFileChanges(changeFiles)
    }

    /**
     * filter events
     */
    private fun filterDeployFile(event: VFileEvent?): ChangedFile? {
        if (event == null) {
            return null
        }

        logger.trace("file event ${event::class.java.name} $event")
        if (event is VFileDeleteEvent || event is VFilePropertyChangeEvent) {
            return null
        }

        val virtualFile = if (event is VFileCopyEvent) {
            VirtualFileManager.getInstance().findFileByNioPath(Paths.get(event.path))
        } else {
            event.file
        }

        // file not exists
        if (virtualFile == null || !virtualFile.exists()) {
            return null
        }
        // is directory
        if (virtualFile.isDirectory) {
            return null
        }

        if (compileContext == null) {
            logger.warn("compileContext not set to FileChangesManager, this should not happened")
            return null
        }

        val modules = compileContext?.modules?.values?: emptyList()
        modules.forEach { module ->
            val baseSourceDir = module.sourceDirs.find {
                virtualFile.path.startsWith(it.path)
            }
            if (baseSourceDir != null) {
                logger.info("source file changed: ${virtualFile.name}")
                val type = when (virtualFile.extension) {
                    "java" -> CompileFile.Type.Java
                    "kt" -> CompileFile.Type.Kotlin
                    else -> {
                        logger.warn("file ${virtualFile.name} has invalid extension, ignore")
                        return null
                    }
                }
                return ChangedFile(type, virtualFile, baseSourceDir, module)
            }

            val baseResourceDir = module.resourceDirs.find { virtualFile.path.startsWith(it.path) }
            if (baseResourceDir != null) {
                logger.info("resource file changed: ${virtualFile.name}")
                return ChangedFile(CompileFile.Type.Resource, virtualFile, baseResourceDir, module)
            }

            val baseAssetDir = module.assetsDirs.find { virtualFile.path.startsWith(it.path) }
            if (baseAssetDir != null) {
                logger.info("asset file changed: ${virtualFile.name}")
                return ChangedFile(CompileFile.Type.Asset, virtualFile, baseAssetDir, module)
            }
        }

        return null
    }
}

interface FileChangesListener {
    fun onFileChanges(changedFiles: List<ChangedFile>)
}

data class ChangedFile(
    val type: CompileFile.Type,
    val file: VirtualFile,
    val baseDir: File,
    val module: ModuleInfo,
)