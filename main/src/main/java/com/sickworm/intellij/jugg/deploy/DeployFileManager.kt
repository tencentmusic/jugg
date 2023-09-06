package com.sickworm.intellij.jugg.deploy

import com.android.tools.idea.run.ApkInfo
import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.jugg.project.ChangedFile
import com.sickworm.intellij.jugg.compiler.CompileFile
import com.sickworm.intellij.jugg.compiler.CompileOutput
import com.sickworm.intellij.jugg.deploy.data.DeployDataGenerator
import com.sickworm.intellij.jugg.deploy.data.SourceFileManager
import com.sickworm.intellij.jugg.deploy.run.DeployItem
import com.sickworm.intellij.jugg.deploy.run.JuggDeployData
import com.sickworm.intellij.jugg.gradle.compile.isChild
import org.jetbrains.annotations.TestOnly
import java.io.File
import java.util.zip.CRC32

/**
 * Manage runtime deploy file status and provides [JuggDeployData]
 */
class DeployFileManager(
    private val logger: Logger,
    private val tmpDir: File,
    databaseDir: File,
) {

    /**
     * uncompiled files. All operation must be thread-safe
     */
    private var uncompiledFiles = mutableMapOf<String, ChangedFile>()

    /**
     * compiled files. All operation must be thread-safe
     */
    private var compiledFiles = mutableMapOf<String, ChangedFile>()

    /**
     * Staging files for deployment. All operation must be thread-safe
     */
    private var stagingFiles = mutableMapOf<String, CompileOutput>()

    /**
     * build [JuggDeployData]
     */
    private val deployDataGenerator = DeployDataGenerator(logger, databaseDir)

    /**
     * get source file by source file name in dex file
     */
    private val sourceFileManager = SourceFileManager(logger, databaseDir)

    private var crc32 = CRC32()

    @Synchronized
    fun init(apks: List<ApkInfo>, deployedFiles: List<CompileOutput>) {
        reset()
        val deployItems = deployedFiles.map { it.toDeployItem() }
        deployDataGenerator.init(apks, deployItems)
    }

    @Synchronized
    fun addChangedFile(files: List<ChangedFile>) {
        logger.debug("add changed files: $files")
        files.forEach {
            uncompiledFiles[it.file.stdPath] = it // update ChangedFile.compiledTimes
        }
        val newFiles = files.filter {
            uncompiledFiles.containsKey(it.file.stdPath).not()
        }
        sourceFileManager.updateFiles(newFiles.map { it.file }, emptyList())
    }

    @Synchronized
    fun removeChangedFile(files: List<File>) {
        files.forEach { file ->
            uncompiledFiles.forEach { (stdPath, changedFile) ->
                if (stdPath == file.stdPath) {
                    logger.debug("remove changed file: $file")
                    uncompiledFiles.remove(stdPath)
                } else if (changedFile.file.isChild(file)) {
                    logger.debug("remove changed file for dir deleted: ${changedFile.file}")
                    uncompiledFiles.remove(stdPath)
                }
            }
            compiledFiles.forEach { (stdPath, changedFile) ->
                if (stdPath == file.stdPath) {
                    logger.debug("remove compiled file: $file")
                    compiledFiles.remove(stdPath)
                } else if (changedFile.file.isChild(file)) {
                    logger.debug("remove compiled file for dir deleted: ${changedFile.file}")
                    compiledFiles.remove(stdPath)
                }
            }
        }
        sourceFileManager.updateFiles(emptyList(), files)
    }

    @Synchronized
    fun updateUncompiledFiles(successFiles: List<CompileFile>, failedFiles: List<CompileFile>) {
        successFiles.forEach {
            val fileKey = it.file.stdAbsPath
            val changedFile = uncompiledFiles[fileKey]
            if (changedFile == null) {
                logger.warn("try to update file compile status, but it's not in uncompiled list. File: $it")
                return@forEach
            }
            changedFile.compiledTimes++
            uncompiledFiles.remove(fileKey)
            compiledFiles[fileKey] = changedFile
        }
        failedFiles.forEach {
            val fileKey = it.file.stdAbsPath
            val changedFile = uncompiledFiles[fileKey]
            if (changedFile == null) {
                logger.warn("try to update file compile status, but it's not in uncompiled list. File: $it")
                return@forEach
            }
            changedFile.compiledTimes++
        }
    }

    @Synchronized
    fun getUncompiledFiles(): List<ChangedFile> {
        return uncompiledFiles.values.toList()
    }

    @Synchronized
    fun getCompiledFiles(): List<ChangedFile> {
        return compiledFiles.values.toList()
    }

    @Synchronized
    fun getStagingFiles(): List<CompileOutput> {
        return stagingFiles.values.toList()
    }

    @Synchronized
    fun addDeployFiles(compileOutputFiles: List<CompileOutput>) {
        compileOutputFiles.forEach {
            stagingFiles[it.file.stdAbsPath] = it
        }
    }

    @Synchronized
    fun getDeployData(isWarmUp: Boolean = false, isFallbackAllHotFix: Boolean = false): JuggDeployData {
        if (isWarmUp) {
            // add fake overlay to trigger full deployment
            addJuggWarmUpOverlay()
        }
        val deployItems = stagingFiles.values.map { it.toDeployItem() }
        val deployData = deployDataGenerator.buildDeployData(deployItems, isWarmUp)
        return if (isFallbackAllHotFix) {
            deployData.copy(hotFixModifiedClasses = deployData.hotFixModifiedClasses + deployData.hotReloadModifiedClasses, hotReloadModifiedClasses = emptyList())
        } else {
            deployData
        }
    }

    private fun CompileOutput.toDeployItem(): DeployItem {
        val bytes = file.readBytes()
        val crc = crc32.run {
            reset()
            update(bytes)
            value
        }
        val name = if (type == CompileOutput.Type.Dex) {
            relativeFile.stdPath
                .replace('/', '.')
                .replace(file.name, file.nameWithoutExtension)
        } else {
            relativeFile.stdPath
        }
        return DeployItem(name, type, crc, bytes)
    }

    private fun addJuggWarmUpOverlay(): DeployItem {
        val file = File(tmpDir, "jugg_warm_up_overlay")
        if (!file.exists()) {
            file.createNewFile()
            file.writeText("jugg_warm_up_overlay", Charsets.UTF_8)
        }
        val compileOutput = CompileOutput(CompileOutput.Type.Overlay, file, file.parentFile)
        addDeployFiles(listOf(compileOutput))
        return compileOutput.toDeployItem()
    }

    @Synchronized
    fun commit(juggDeployData: JuggDeployData) {
        deployDataGenerator.commitDeployedData(juggDeployData)
        stagingFiles.clear()
        compiledFiles.clear()
    }

    @TestOnly
    @Synchronized
    fun reset() {
        uncompiledFiles.clear()
        compiledFiles.clear()
        stagingFiles.clear()
    }

    @Synchronized
    fun updateSourceDirs(sourceFileDirs: List<File>) {
        sourceFileManager.init(sourceFileDirs)
    }

    /**
     * Get source files that effected by [compiledFiles].
     * e.g. A.java invokes B.func(), B.func() is changed and compiled, then A.java is effected, and it will be returned.
     */
    @Synchronized
    fun getEffectedSourceFiles(): List<File> {
        val deployItems = stagingFiles.values
            .filter { it.type == CompileOutput.Type.Dex }
            .map { it.toDeployItem() }
        val juggDeployData = deployDataGenerator.buildDeployData(deployItems)
        val effectedSourceFiles = juggDeployData.effectedSourceFileNames
        if (effectedSourceFiles.isEmpty()) {
            logger.debug("getEffectedSourceFiles: no effected source files")
            return emptyList()
        }

        val sourceFiles = sourceFileManager.getFiles(effectedSourceFiles)
        if (sourceFiles.size < effectedSourceFiles.size) {
            val missingFiles = sourceFiles.filter {
                it.name !in effectedSourceFiles
            }
            logger.warn("getEffectedSourceFiles: source files size is less than effected source files size. " +
                "missing source files: $missingFiles")
        }

        val unCompiledFiles = sourceFiles.filter { !compiledFiles.containsKey(it.stdPath) }
        if (unCompiledFiles.isEmpty()) {
            logger.debug("getEffectedSourceFiles: no uncompiled source files")
            return emptyList()
        }

        logger.debug("getEffectedSourceFiles: effectedSourceFiles ${effectedSourceFiles}, source files $unCompiledFiles")

        return unCompiledFiles
    }

    private val File.stdAbsPath get() = absolutePath.replace(File.separatorChar, '/')
    private val File.stdPath get() = path.replace(File.separatorChar, '/')
}

class StagingFile(val file: File, val type: CompileOutput.Type)