package com.sickworm.intellij.aidp

import com.android.tools.idea.util.toIoFile
import com.intellij.openapi.Disposable
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.AsyncFileListener
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.events.VFileCopyEvent
import com.intellij.openapi.vfs.newvfs.events.VFileDeleteEvent
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.openapi.vfs.newvfs.events.VFilePropertyChangeEvent
import com.sickworm.intellij.aidp.compiler.CompileFile
import org.jetbrains.jps.model.java.JavaResourceRootType
import org.jetbrains.jps.model.java.JavaSourceRootType
import org.jetbrains.kotlin.config.ResourceKotlinRootType
import org.jetbrains.kotlin.config.SourceKotlinRootType
import java.io.File
import java.nio.file.Path

/**
 * 文件变化监听
 */
class FileChangesManager(private val project: Project,
                         private val projectDir: String): Disposable {

    private val logger = AidpLogger.getInstance(project, "#AIDP-FileChangesManager")

    private var listener: FileChangesListener? = null

    private var sourceRoots: List<VirtualFile> = emptyList()
    private var resourceRoots: List<VirtualFile> = emptyList()
    private var assetRoots: List<VirtualFile> = emptyList()

    fun startListen(listener: FileChangesListener) {
        logger.info("start listen project $projectDir")
        this.listener = listener

        initFileRoots()
        logger.debug("""
            |start listen.
            |    source roots:
            |        ${sourceRoots.map { File(it.path).relativeTo(File(projectDir)) }.joinToString("\n        ")}
            |    resource roots:
            |        ${resourceRoots.map { File(it.path).relativeTo(File(projectDir)) }.joinToString("\n        ")}
            |    asset roots:
            |        ${assetRoots.map { File(it.path).relativeTo(File(projectDir)) }.joinToString("\n        ")}
            |""".trimMargin())

        listenFileChanges()
        Disposer.register(project, this)
    }

    private fun initFileRoots() {
        val sourceRoots = mutableListOf<VirtualFile>()
        val resourceRoots = mutableListOf<VirtualFile>()
        val assetRoots = mutableListOf<VirtualFile>()

        // TODO GradleBuildModel.get(ModuleManager.getInstance(project).modules[1]).android().sourceSets()
        ModuleManager.getInstance(project).modules.forEach { module ->
            val moduleManager = ModuleRootManager.getInstance(module)
            val subSourceRoots = moduleManager.getSourceRoots(
                setOf(JavaSourceRootType.SOURCE, SourceKotlinRootType))
            sourceRoots.addAll(subSourceRoots)

            val subResourceRoots = moduleManager.getSourceRoots(
                setOf(JavaResourceRootType.RESOURCE, ResourceKotlinRootType))
            subResourceRoots.forEach {
                if (it.name == "res") {
                    resourceRoots.add(it)
                } else if (it.name == "assets") {
                    assetRoots.add(it)
                }
            }
        }

        this.sourceRoots = sourceRoots
        this.resourceRoots = resourceRoots
        this.assetRoots = assetRoots
    }

    override fun dispose() {
        logger.info("$projectDir dispose")
    }

    private fun listenFileChanges() {
        val vfsListener = object: AsyncFileListener {
            override fun prepareChange(events: MutableList<out VFileEvent>): AsyncFileListener.ChangeApplier? {
                return object: AsyncFileListener.ChangeApplier {
                    override fun afterVfsChange() {
                        val changeFiles = events.mapNotNull(::filterDeployFile)
                        if (changeFiles.isEmpty()) return
                        logger.info("onFileChanges $changeFiles")
                        listener?.onFileChanges(changeFiles)
                    }
                }
            }
        }
        VirtualFileManager.getInstance().addAsyncFileListener(vfsListener, this)
    }

    /**
     * filter events
     */
    private fun filterDeployFile(event: VFileEvent?): ChangedFile? {
        if (event == null) {
            return null
        }

        logger.debug("file event ${event::class.java.name} $event")
        if (event is VFileDeleteEvent || event is VFilePropertyChangeEvent) {
            return null
        }

        val virtualFile = if (event is VFileCopyEvent) {
            VirtualFileManager.getInstance().findFileByNioPath(Path.of(event.path))
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

        val baseSourceDir = sourceRoots.find { virtualFile.path.startsWith(it.path) }
        if (baseSourceDir != null) {
            logger.debug("source file changed, event $event")
            val type = when (virtualFile.extension) {
                "java" -> CompileFile.Type.Java
                "kt" -> CompileFile.Type.Kotlin
                else -> {
                    logger.debug("file event $event, extension ignore, don't need deploy")
                    return null
                }
            }
            return ChangedFile(virtualFile, baseSourceDir.toIoFile(), type)
        }

        val baseResourceDir = resourceRoots.find { virtualFile.path.startsWith(it.path) }
        if (baseResourceDir != null) {
            logger.debug("resource file changed, event $event")
            return ChangedFile(virtualFile, baseResourceDir.toIoFile(), CompileFile.Type.Res)
        }

        val baseAssetDir = assetRoots.find { virtualFile.path.startsWith(it.path) }
        if (baseAssetDir != null) {
            logger.debug("asset file changed, event $event")
            return ChangedFile(virtualFile, baseAssetDir.toIoFile(), CompileFile.Type.Overlay)
        }

        return null
    }
}

interface FileChangesListener {
    fun onFileChanges(changedFiles: List<ChangedFile>)
}

data class ChangedFile(
    val file: VirtualFile,
    val baseDir: File,
    val type: CompileFile.Type
)