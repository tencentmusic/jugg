package com.sickworm.intellij.aidp

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

    private var sourceRoots: List<File> = emptyList()
    private var resourceRoots: List<File> = emptyList()
    private var assetRoots: List<File> = emptyList()

    private val sourceExtensions = listOf("java", "kt")

    fun startListen(listener: FileChangesListener) {
        logger.info("start listen project $projectDir")
        this.listener = listener

        initFileRoots()
        logger.debug("""
            |start listen.
            |    source roots:
            |        ${sourceRoots.map { it.relativeTo(File(projectDir)) }.joinToString("\n        ")}
            |    resource roots:
            |        ${resourceRoots.map { it.relativeTo(File(projectDir)) }.joinToString("\n        ")}
            |    asset roots:
            |        ${assetRoots.map { it.relativeTo(File(projectDir)) }.joinToString("\n        ")}
            |""".trimMargin())

        listenFileChanges()
        Disposer.register(project, this)
    }

    private fun initFileRoots() {
        val sourceRoots = mutableListOf<File>()
        val resourceRoots = mutableListOf<File>()
        val assetRoots = mutableListOf<File>()

        // TODO GradleBuildModel.get(ModuleManager.getInstance(project).modules[1]).android().sourceSets()
        ModuleManager.getInstance(project).modules.forEach { module ->
            val moduleManager = ModuleRootManager.getInstance(module)
            val subSourceRoots = moduleManager.getSourceRoots(
                setOf(JavaSourceRootType.SOURCE, SourceKotlinRootType))
            sourceRoots.addAll(subSourceRoots.map { File(it.path) })

            val subResourceRoots = moduleManager.getSourceRoots(
                setOf(JavaResourceRootType.RESOURCE, ResourceKotlinRootType))
            subResourceRoots.forEach {
                if (it.name == "res") {
                    resourceRoots.add(File(it.path))
                } else if (it.name == "assets") {
                    assetRoots.add(File(it.path))
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
//        logger.debug("file event ${event::class.java.name} $event")
        if (event == null) {
            return null
        }
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
            // extension not match
            if (!sourceExtensions.contains(virtualFile.extension)) {
                logger.debug("file event $event, extension ignore, don't need deploy")
                return null
            }
            logger.debug("source file changed, event $event")
            return ChangedFile(virtualFile, baseSourceDir)
        }

        val baseResourceDir = resourceRoots.find { virtualFile.path.startsWith(it.path) }
        if (baseResourceDir != null) {
            logger.debug("resource file changed, event $event")
            return ChangedFile(virtualFile, baseResourceDir)
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
    val type: CompileFile.Type = CompileFile.getTypeByExtension(file.name)
)