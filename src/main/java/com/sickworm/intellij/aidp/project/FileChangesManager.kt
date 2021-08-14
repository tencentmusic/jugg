package com.sickworm.intellij.aidp

import com.android.tools.idea.gradle.dsl.api.ProjectBuildModel
import com.android.tools.idea.gradle.dsl.api.android.sourceSets.SourceDirectoryModel
import com.android.tools.idea.gradle.dsl.api.ext.GradlePropertyModel
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
import com.sickworm.intellij.aidp.toolWindow.AidpLogger
import org.jetbrains.jps.model.java.JavaResourceRootType
import org.jetbrains.jps.model.java.JavaSourceRootType
import org.jetbrains.kotlin.config.ResourceKotlinRootType
import org.jetbrains.kotlin.config.SourceKotlinRootType
import java.io.File
import java.nio.file.Paths

/**
 * Manage file changes in project
 */
class FileChangesManager(private val project: Project,
                         private val projectDir: String): Disposable {

    private val logger = AidpLogger.getInstance(project, "#AIDP-FileChangesManager")

    private var listener: FileChangesListener? = null

    private var sourceRoots: List<File> = emptyList()
    private var resourceRoots: List<File> = emptyList()
    private var assetRoots: List<File> = emptyList()

    fun startListen(listener: FileChangesListener) {
        logger.info("start listen project $projectDir")
        this.listener = listener

        initFileRoots()
        logger.debug("""
            |start listen.
            |    source roots:
            |        ${sourceRoots.map { it.path }.relativePath(projectDir) }
            |    resource roots:
            |        ${resourceRoots.map { it.path }.relativePath(projectDir) }
            |    asset roots:
            |        ${assetRoots.map { it.path }.relativePath(projectDir) }
            |""".trimMargin())

        listenFileChanges()
        Disposer.register(project, this)
    }

    private fun initFileRoots() {
        val sourceRoots = mutableListOf<File>()
        val resourceRoots = mutableListOf<File>()
        val assetRoots = mutableListOf<File>()

        ModuleManager.getInstance(project).modules.forEach { module ->
            val baseDir = module.guessModuleDirAdv()?.path
            if (baseDir == null) {
                logger.warn("gradle module $module dir not found")
                return@forEach
            }

            val moduleManager = ModuleRootManager.getInstance(module)
            val subSourceRoots = moduleManager.getSourceRoots(
                setOf(JavaSourceRootType.SOURCE, SourceKotlinRootType))
                .map { it.toIoFile() }
                .filter { !it.relativeTo(File(baseDir)).path.startsWith("build") } // ignore build source
            sourceRoots.addAll(subSourceRoots)

            val subResourceRoots = moduleManager.getSourceRoots(
                setOf(JavaResourceRootType.RESOURCE, ResourceKotlinRootType))
            subResourceRoots.forEach {
                if (it.name == "res") {
                    resourceRoots.add(it.toIoFile())
                } else if (it.name == "assets") {
                    assetRoots.add(it.toIoFile())
                }
            }
            val buildModel = ProjectBuildModel.get(project).getModuleBuildModel(module)
            if (buildModel == null) {
                logger.warn("gradle module $module not found")
                return@forEach
            }
            val sourceSets = buildModel.android().sourceSets()

            val javaSets: List<File> = sourceSets
                .map { it.java() }
                .flatMap { it.getFileList(baseDir) }
            sourceRoots.addAll(javaSets)
            val resSets: List<File> = sourceSets
                .map { it.res() }
                .flatMap { it.getFileList(baseDir) }
            resourceRoots.addAll(resSets)
            val assetsSets: List<File> = sourceSets
                .map { it.assets() }
                .flatMap { it.getFileList(baseDir) }
            assetRoots.addAll(assetsSets)
        }

        this.sourceRoots = sourceRoots
        this.resourceRoots = resourceRoots
        this.assetRoots = assetRoots
    }

    private fun SourceDirectoryModel.getFileList(baseDir: String): List<File> {
        val dirs = srcDirs().getValue(GradlePropertyModel.LIST_TYPE)?: emptyList()
        return dirs
            .mapNotNull { it.getValue(GradlePropertyModel.STRING_TYPE) }
            .map { File(baseDir, it) }
    }

    override fun dispose() {
        logger.info("$projectDir dispose")
    }

    private fun listenFileChanges() {
        val vfsListener = object: AsyncFileListener {
            override fun prepareChange(events: MutableList<out VFileEvent>): AsyncFileListener.ChangeApplier {
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
            return ChangedFile(type, virtualFile, baseSourceDir)
        }

        val baseResourceDir = resourceRoots.find { virtualFile.path.startsWith(it.path) }
        if (baseResourceDir != null) {
            logger.debug("resource file changed, event $event")
            return ChangedFile(CompileFile.Type.Resource, virtualFile, baseResourceDir)
        }

        val baseAssetDir = assetRoots.find { virtualFile.path.startsWith(it.path) }
        if (baseAssetDir != null) {
            logger.debug("asset file changed, event $event")
            return ChangedFile(CompileFile.Type.Asset, virtualFile, baseAssetDir)
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
)