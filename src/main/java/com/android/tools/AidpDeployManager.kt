package com.android.tools

import com.android.tools.deployer.AidpDeployData
import com.android.tools.deployer.DexComparator
import com.android.tools.deployer.model.DexClass
import com.sickworm.intellij.aidp.pathSeparator
import java.io.File

/**
 * Works like a git. Operates with add, commit
 */
class AidpDeployDataManager(private val stagingDir: File) {

    private var stagingFiles = mutableMapOf<String, Item>()

    @Synchronized
    fun addClass(file: File, relativePath: String, isKeepOriginFile: Boolean) {
        val destFile = File(stagingDir.absolutePath, relativePath)
        destFile.parentFile?.let {
            if (!it.exists()) it.mkdirs()
        }
        destFile.let {
            if (it.exists()) it.delete()
        }
        if (isKeepOriginFile) {
            file.copyTo(destFile)
        } else {
            file.renameTo(destFile)
        }
        stagingFiles[destFile.absolutePath] = Item(FileType.CLASS, destFile)
    }

    fun getDeployData(): AidpDeployData {
        val changedClassFiles = stagingFiles.values.filter { it.type == FileType.CLASS }
        val changesClasses = changedClassFiles.map {
            val bytes = it.file.readBytes()
            DexClass(it.file.getClassNameByPath(), 0, bytes, null)
        }
        return AidpDeployData(
            DexComparator.ChangedClasses(
                emptyList(),
                changesClasses
            )
        )
    }

    fun commit() {

    }

    private class Item(
        val type: FileType,
        val file: File,
    )

    private enum class FileType {
        CLASS
    }

    private fun File.getClassNameByPath(): String {
        return relativeTo(stagingDir).path.replace(File.separatorChar, '.').replace(name, nameWithoutExtension)
    }
}