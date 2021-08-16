package com.sickworm.intellij.jugg.deploy

import com.android.tools.deployer.AidpDeployData
import com.android.tools.deployer.AidpDeployItem
import com.android.tools.idea.run.ApkInfo
import com.intellij.openapi.vfs.VirtualFile
import com.sickworm.intellij.jugg.AidpException
import com.sickworm.intellij.jugg.project.ChangedFile
import com.sickworm.intellij.jugg.compiler.CompileFile
import com.sickworm.intellij.jugg.compiler.CompileOutput
import java.io.File
import java.util.zip.CRC32

/**
 * Works like a git. Operates with add, commit
 */
class AidpDeployDataManager {

    /**
     * uncompiled files. All operation must be thread-safe
     */
    private var uncompiledFiles = mutableMapOf<String, ChangedFile>()

    /**
     * Staging files for deployment. All operation must be thread-safe
     */
    private var stagingFiles = mutableMapOf<String, CompileOutput>()

    /**
     * Deployed files
     */
    private var deployedFiles = mutableMapOf<String, CompileOutput>()

    private var crc32 = CRC32()

    @Synchronized
    fun addChangedFile(file: ChangedFile) {
        uncompiledFiles[file.file.standardizedPath] = file
    }

    @Synchronized
    fun markAsCompiled(file: CompileFile) {
        uncompiledFiles.remove(file.file.stdAbsPath)
    }

    @Synchronized
    fun getUncompiledFiles(): List<ChangedFile> {
        return uncompiledFiles.values.toList()
    }

    @Synchronized
    fun addDeployFile(classFile: CompileOutput) {
        stagingFiles[classFile.file.stdAbsPath] = classFile
    }

    @Synchronized
    fun getDeployData(apks: List<ApkInfo>): AidpDeployData {
        if (uncompiledFiles.isNotEmpty()) {
            throw AidpException.notAllCompiled(uncompiledFiles.values)
        }

        val items = stagingFiles.values
        val changedClassFiles = items.filter { it.type == CompileOutput.Type.Dex }
        // TODO do it in addDeployFile
        val changedClasses = changedClassFiles.map { it.toDeployItem() }

        val changedOverlayFiles = items.filter { it.type == CompileOutput.Type.Overlay }
        val changedOverlays = changedOverlayFiles.map { it.toDeployItem() }

        return AidpDeployData(
            apks,
            changedClasses,
            changedOverlays
        )
    }

    private fun CompileOutput.toDeployItem(): AidpDeployItem {
        val bytes = file.readBytes()
        val crc = crc32.run {
            reset()
            update(bytes)
            value
        }
        val name = if (type == CompileOutput.Type.Dex) {
            file.relativeTo(baseDir).stdPath
                .replace(File.separatorChar, '.')
                .replace(file.name, file.nameWithoutExtension)
        } else {
            file.relativeTo(baseDir).stdPath
        }
        return AidpDeployItem(name, crc, bytes)
    }

    @Synchronized
    fun commit() {
        deployedFiles.putAll(stagingFiles)
        stagingFiles.clear()
    }

    private val File.stdAbsPath get() = absolutePath.replace(File.separatorChar, '/')
    private val File.stdPath get() = path.replace(File.separatorChar, '/')
    private val VirtualFile.standardizedPath get() = path.replace(File.separatorChar, '/')
}