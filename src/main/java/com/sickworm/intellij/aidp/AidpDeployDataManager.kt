package com.sickworm.intellij.aidp

import com.android.tools.deployer.AidpDeployData
import com.android.tools.deployer.model.DexClass
import com.intellij.openapi.vfs.VirtualFile
import java.io.File
import java.util.zip.CRC32

/**
 * Works like a git. Operates with add, commit
 */
class AidpDeployDataManager(private val stagingDir: File) {

    /**
     * uncompiled files. All operation must be thread-safe
     */
    private var uncompiledFiles = mutableMapOf<String, ChangedFile>()

    /**
     * Staging files for deployment. All operation must be thread-safe
     */
    private var stagingFiles = mutableMapOf<String, DeployItem>()

    private var crc32 = CRC32()

    @Synchronized
    fun addChangedFile(file: ChangedFile) {
        uncompiledFiles[file.file.standardizedPath] = file
    }

    @Synchronized
    fun markAsCompiled(file: CompileFile) {
        uncompiledFiles.remove(file.file.standardizedPath)
    }

    @Synchronized
    fun getUncompiledFiles(): List<ChangedFile> {
        return uncompiledFiles.values.toList()
    }

    @Synchronized
    fun addClassFile(classFile: File, baseDir: File, isKeepOriginFile: Boolean) {
        val destStageFile = classFile.changeBaseDir(baseDir, stagingDir)
        destStageFile.parentFile?.let {
            if (!it.exists()) it.mkdirs()
        }
        destStageFile.let {
            if (it.exists()) it.delete()
        }
        if (isKeepOriginFile) {
            classFile.copyTo(destStageFile)
        } else {
            classFile.renameTo(destStageFile)
        }
        stagingFiles[destStageFile.absolutePath] = DeployItem(DeployType.CLASS_FILE, destStageFile)
    }

    @Synchronized
    fun getDeployData(): AidpDeployData {
        if (uncompiledFiles.isNotEmpty()) {
            throw AidpException.notAllCompiled(uncompiledFiles.values)
        }

        val items = stagingFiles.values
        val changedClassFiles = items.filter { it.type == DeployType.CLASS_FILE }
        val changesClasses = changedClassFiles.map {
            val bytes = it.file.readBytes()
            val crc = crc32.run {
                reset()
                update(bytes)
                value
            }
            DexClass(it.file.getClassNameByPath(), crc, bytes, null)
        }
        return AidpDeployData(
            changesClasses
        )
    }

    private fun File.getClassNameByPath(): String {
        return relativeTo(stagingDir).path
            .replace(File.separatorChar, '.')
            .replace(name, nameWithoutExtension)
    }

    @Synchronized
    fun commit() {
        stagingFiles.clear()
    }

    private class DeployItem(
        val type: DeployType,
        val file: File,
    )

    private enum class DeployType {
        CLASS_FILE,
        OVERLAY_FILE
    }

    private val File.standardizedPath get() = absolutePath.replace(File.separatorChar, '/')
    private val VirtualFile.standardizedPath get() = path.replace(File.separatorChar, '/')
}