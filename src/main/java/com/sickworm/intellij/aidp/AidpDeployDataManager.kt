package com.sickworm.intellij.aidp

import com.android.tools.deployer.AidpDeployData
import com.android.tools.deployer.model.DexClass
import java.io.File
import java.util.zip.CRC32

/**
 * Works like a git. Operates with add, commit
 */
class AidpDeployDataManager(private val stagingDir: File) {

    /**
     * Staging files for deployment. All operation must be thread-safe
     */
    private var stagingFiles = mutableMapOf<String, Item>()

    private var crc32 = CRC32()

    @Synchronized
    fun addChangedFile(file: File) {
        stagingFiles[file.absolutePath] = Item(Type.SOURCE_FILE, file)
    }

    @Synchronized
    fun markAsCompiled(file: File) {
        stagingFiles.remove(file.absolutePath)
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
        stagingFiles[destStageFile.absolutePath] = Item(Type.CLASS_FILE, destStageFile)
    }

    @Synchronized
    fun getUncompiledFiles(): List<File> {
        return stagingFiles.values.filter { it.type == Type.SOURCE_FILE }.map { it.file }
    }

    @Synchronized
    fun getDeployData(): AidpDeployData {
        val items = stagingFiles.values

        val notCompiledFiles = items.filter { it.type == Type.SOURCE_FILE }
        if (notCompiledFiles.isNotEmpty()) {
            throw AidpException.notAllCompiled(notCompiledFiles.map { it.file })
        }

        val changedClassFiles = items.filter { it.type == Type.CLASS_FILE }
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

    private class Item(
        val type: Type,
        val file: File,
    )

    private enum class Type {
        SOURCE_FILE,
        CLASS_FILE,
        OVERLAY_FILE
    }
}