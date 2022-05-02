package com.sickworm.intellij.jugg.deploy

import com.android.tools.deployer.JuggDeployData
import com.android.tools.deployer.DeployItem
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.vfs.VirtualFile
import com.sickworm.intellij.jugg.project.JuggException
import com.sickworm.intellij.jugg.project.ChangedFile
import com.sickworm.intellij.jugg.compiler.CompileFile
import com.sickworm.intellij.jugg.compiler.CompileOutput
import com.sickworm.intellij.jugg.project.CompileContextManager
import org.jetbrains.annotations.TestOnly
import java.io.File
import java.util.zip.CRC32

/**
 * Works like a git. Operates with add, commit
 */
class DeployDataManager(compileContextManager: CompileContextManager, logger: Logger) {

    /**
     * uncompiled files. All operation must be thread-safe
     */
    private var uncompiledFiles = mutableMapOf<String, ChangedFile>()

    /**
     * Staging files for deployment. All operation must be thread-safe
     */
    private var stagingFiles = mutableMapOf<String, DeployItem>()

    /**
     * Deployed files
     * TODO persist
     */
    private var deployedFiles = mutableMapOf<String, DeployItem>()

    /**
     * persisted deploy information
     */
    private val deployDataDb = DeployDataDb(compileContextManager, logger)

    private var crc32 = CRC32()

    @Synchronized
    fun addChangedFile(file: ChangedFile) {
        uncompiledFiles[file.file.stdPath] = file
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
    fun addDeployFile(compiledFile: CompileOutput) {
        stagingFiles[compiledFile.file.stdAbsPath] = compiledFile.toDeployItem()
    }

    @Synchronized
    fun getDeployData(): JuggDeployData {
        if (uncompiledFiles.isNotEmpty()) {
            throw JuggException.notAllCompiled(uncompiledFiles.values)
        }

        return deployDataDb.buildDeployData(stagingFiles.values)
    }

    private fun CompileOutput.toDeployItem(): DeployItem {
        val bytes = file.readBytes()
        val crc = crc32.run {
            reset()
            update(bytes)
            value
        }
        val name = if (type == CompileOutput.Type.Dex) {
            file.relativeTo(baseDir).stdPath
                .replace('/', '.')
                .replace(file.name, file.nameWithoutExtension)
        } else {
            file.relativeTo(baseDir).stdPath
        }
        return DeployItem(name, type, crc, bytes)
    }

    @Synchronized
    fun commit(juggDeployData: JuggDeployData) {
        deployedFiles.putAll(stagingFiles)
        stagingFiles.clear()
        deployDataDb.update(juggDeployData)
    }

    @TestOnly
    @Synchronized
    fun reset() {
        uncompiledFiles.clear()
        stagingFiles.clear()
        deployedFiles.clear()
    }

    private val File.stdAbsPath get() = absolutePath.replace(File.separatorChar, '/')
    private val File.stdPath get() = path.replace(File.separatorChar, '/')
}