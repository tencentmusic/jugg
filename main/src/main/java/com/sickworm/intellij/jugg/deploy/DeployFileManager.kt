package com.sickworm.intellij.jugg.deploy

import com.android.tools.idea.run.ApkInfo
import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.jugg.project.ChangedFile
import com.sickworm.intellij.jugg.compiler.CompileFile
import com.sickworm.intellij.jugg.compiler.CompileOutput
import com.sickworm.intellij.jugg.compiler.ModuleInfo
import com.sickworm.intellij.jugg.deploy.data.DeployDataGenerator
import com.sickworm.intellij.jugg.deploy.data.SourceFileManager
import com.sickworm.intellij.jugg.deploy.run.DeployItem
import com.sickworm.intellij.jugg.deploy.run.JuggDeployData
import com.sickworm.intellij.jugg.gradle.compile.isChild
import com.sickworm.intellij.jugg.logger.getInstance
import org.jetbrains.annotations.TestOnly
import java.io.File
import java.util.zip.CRC32
import java.util.zip.ZipFile

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
     * Deployed files. All operation must be thread-safe
     */
    private val deployedFiles = mutableMapOf<String, CompileOutput>()

    /**
     * build [JuggDeployData]
     */
    private val deployDataGenerator = DeployDataGenerator(logger.getInstance("DeployDataGenerator"), databaseDir)

    /**
     * get source file by source file name in dex file
     */
    private val sourceFileManager = SourceFileManager(logger.getInstance("SourceFileManager"), databaseDir)

    private var crc32 = CRC32()

    private var moduleInfos: Map<String, ModuleInfo> = mutableMapOf()

    @Synchronized
    fun init(apks: List<ApkInfo>, deployedFiles: List<CompileOutput>, resetFilesBeforeTimeMill: Long?) {
        reset(resetFilesBeforeTimeMill)
        val deployItems = deployedFiles.map { it.toDeployItem() }
        deployDataGenerator.init(apks, deployItems)

        this.deployedFiles.clear()
        deployedFiles.forEach {
            this.deployedFiles[it.file.stdAbsPath] = it
        }
    }

    @Synchronized
    fun addChangedFile(files: List<ChangedFile>) {
        logger.debug("add changed files: $files")
        val newFiles = files.filter {
            uncompiledFiles.containsKey(it.file.stdPath).not()
        }
        files.forEach {
            uncompiledFiles[it.file.stdPath] = it // update ChangedFile.compiledTimes
        }
        sourceFileManager.updateFiles(newFiles.map { it.file }, emptyList())
    }

    @Synchronized
    fun removeChangedFile(files: List<File>) {
        files.forEach { file ->
            uncompiledFiles.iterator().let { iterator ->
                iterator.forEach { (stdPath, changedFile) ->
                    if (stdPath == file.stdPath) {
                        logger.debug("remove changed file: $file")
                        iterator.remove()
                    } else if (changedFile.file.isChild(file)) {
                        logger.debug("remove changed file for dir deleted: ${changedFile.file}")
                        iterator.remove()
                    }
                }
            }
            compiledFiles.iterator().let { iterator ->
                iterator.forEach { (stdPath, changedFile) ->
                    if (stdPath == file.stdPath) {
                        logger.debug("remove compiled file: $file")
                        iterator.remove()
                    } else if (changedFile.file.isChild(file)) {
                        logger.debug("remove compiled file for dir deleted: ${changedFile.file}")
                        iterator.remove()
                    }
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
                // e.g. R.java
                logger.debug("try to update file compile status, but it's not in uncompiled list. File: $it")
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
                // e.g. R.java
                logger.debug("try to update file compile status, but it's not in uncompiled list. File: $it")
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
        val deployItems = stagingFiles.values.map { it.toDeployItem() }
        val deployData = deployDataGenerator.buildDeployData(deployItems, isWarmUp)
        return if (isFallbackAllHotFix) {
            deployData.copy(hotFixModifiedClasses = deployData.hotFixModifiedClasses + deployData.hotReloadModifiedClasses, hotReloadModifiedClasses = emptyList())
        } else {
            deployData
        }
    }

    @Synchronized
    fun getDeployedFiles(): List<CompileOutput> {
        return deployedFiles.values.toList()
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

    @Synchronized
    fun commit(juggDeployData: JuggDeployData) {
        deployDataGenerator.commitDeployedData(juggDeployData)
        stagingFiles.clear()
        compiledFiles.clear()
        deployedFiles.putAll(stagingFiles)
    }

    @TestOnly
    @Synchronized
    fun reset(resetFilesBeforeTimeMill: Long?) {
        logger.debug("reset deploy file manager, resetFilesBeforeTimeMill=$resetFilesBeforeTimeMill")
        val remainUncompiledFiles = uncompiledFiles.filter {
            resetFilesBeforeTimeMill != null && it.value.file.lastModified() > resetFilesBeforeTimeMill
        }

        uncompiledFiles.clear()
        compiledFiles.clear()
        stagingFiles.clear()

        if (remainUncompiledFiles.isNotEmpty()) {
            logger.debug("reset deploy file manager, remain uncompiled files: $remainUncompiledFiles")
            uncompiledFiles.putAll(remainUncompiledFiles)
        }
    }

    @Synchronized
    fun updateModuleInfos(moduleInfos: Map<String, ModuleInfo>) {
        this.moduleInfos = moduleInfos
        val sourceDirs = moduleInfos.values.flatMap {
            it.sourceDirs
        }
        sourceFileManager.init(sourceDirs)
    }

    @Synchronized
    fun getRecompileFiles(compiledFilesThisTime: List<ChangedFile>): RecompileFiles {
        val deployItems = stagingFiles.values
            .filter { it.type == CompileOutput.Type.Dex }
            .map { it.toDeployItem() }
        val juggDeployData = deployDataGenerator.buildDeployData(deployItems)

        val startTime = System.currentTimeMillis()
        val recompileFiles = RecompileFiles(
            getEffectedSourceFiles(juggDeployData.effectedSourceFileNames, compiledFilesThisTime),
            getDesugarInterfaceWithDefaultMethodFiles(juggDeployData.desugaredInterfacesWithDefaultMethods)
        )
        val costTime = System.currentTimeMillis() - startTime
        logger.debug("find recompile files cost: $costTime ms")
        return recompileFiles
    }

    /**
     * Get source files that effected by [compiledFiles].
     * e.g. A.java invokes B.func(), B.func() is changed and compiled, then A.java is effected, and it will be returned.
     */
    private fun getEffectedSourceFiles(effectedSourceFiles: List<String>, compiledFilesThisTime: List<ChangedFile>): List<File> {
        if (effectedSourceFiles.isEmpty()) {
            logger.debug("getEffectedSourceFiles: no effected source files")
            return emptyList()
        }

        val sourceFiles = sourceFileManager.getFiles(effectedSourceFiles)
        if (sourceFiles.size < effectedSourceFiles.size) {
            val missingFiles = effectedSourceFiles.filter { fileName ->
                !sourceFiles.any { it.name == fileName }
            }
            logger.warn("missing source files: $missingFiles")
        }

        val compiledFilesThisTimeSet = compiledFilesThisTime.map { it.file.stdPath }.toSet()
        val unCompiledEffectedFiles = sourceFiles.filter { !compiledFilesThisTimeSet.contains(it.stdPath) }
        if (unCompiledEffectedFiles.isEmpty()) {
            logger.debug("getEffectedSourceFiles: no uncompiled source files")
            return emptyList()
        }

        logger.debug("getEffectedSourceFiles: effectedSourceFiles ${effectedSourceFiles}, source files $unCompiledEffectedFiles")

        return unCompiledEffectedFiles
    }

    private fun getDesugarInterfaceWithDefaultMethodFiles(interfaceNames: List<String>): List<ChangedFile> {
        if (interfaceNames.isEmpty()) {
            logger.debug("getDesugarInterfaceWithDefaultMethodFiles: no desugar interface with default method files")
            return emptyList()
        }

        val startTime = System.currentTimeMillis()
        val interfaceRelativePaths = interfaceNames.map {
            it.classNameToPath
        }.toMutableList()
        logger.debug("getDesugarInterfaceWithDefaultMethodFiles: interfaceRelativePaths $interfaceRelativePaths")

        val redexClassesFiles = mutableListOf<ChangedFile>()
        moduleInfos.values.forEach moduleLoop@{ moduleInfo ->
            moduleInfo.buildPathInfo.allClassPath.forEach {  classPath ->
                if (!classPath.isDirectory) {
                    return@forEach
                }
                val iterator = interfaceRelativePaths.iterator()
                while (iterator.hasNext()) {
                    val relativePath = iterator.next()
                    val destFile = File(classPath, relativePath)
                    if (destFile.exists()) {
                        logger.debug("found desugared class file: $destFile")
                        iterator.remove()
                        val changedFile = ChangedFile(CompileFile.Type.Class, destFile, tmpDir, ModuleInfo.virtualModule)
                        redexClassesFiles.add(changedFile)
                    }
                }
            }
        }
        if (interfaceRelativePaths.isEmpty()) {
            val costTime = System.currentTimeMillis() - startTime
            logger.debug("find desugared class files cost: $costTime ms")
            return redexClassesFiles
        }

        val libraryFiles = mutableSetOf<File>()
        moduleInfos.values.forEach { moduleInfo ->
            moduleInfo.libraryDependencies.forEach {
                libraryFiles.add(it.file)
            }
        }
        logger.debug("getDesugarInterfaceWithDefaultMethodFiles: libraryPaths ${libraryFiles.size}")

        libraryFiles.forEach libraryLoop@{ libraryFile ->
            val iterator = interfaceRelativePaths.iterator()
            while (iterator.hasNext()) {
                val relativePath = iterator.next()
                try {
                    if (!libraryFile.exists()) {
                        continue
                    }

                    ZipFile(libraryFile).use { zipFile ->
                        val entry = zipFile.getEntry(relativePath)
                        if (entry != null) {
                            logger.debug("found desugared class file in library ${libraryFile.absolutePath}/${relativePath}")
                            val destFile = File(tmpDir, relativePath)
                            destFile.parentFile?.mkdirs()
                            zipFile.getInputStream(entry).use { inputStream ->
                                destFile.outputStream().use { outputStream ->
                                    inputStream.copyTo(outputStream)
                                }
                                iterator.remove()
                                val changedFile = ChangedFile(CompileFile.Type.Class, destFile, tmpDir, ModuleInfo.virtualModule)
                                redexClassesFiles.add(changedFile)
                            }
                        }
                    }
                } catch (e: Exception) {
                    logger.warn("getDesugarInterfaceWithDefaultMethodFiles: failed to find desugared class file in library ${libraryFile.absolutePath}/${relativePath}, error: ${e.message}")
                }
            }
        }

        if (interfaceRelativePaths.isNotEmpty()) {
            logger.warn("failed to find desugar class files: $interfaceRelativePaths")
        }

        val costTime = System.currentTimeMillis() - startTime
        logger.debug("find desugared class files cost: $costTime ms")
        return redexClassesFiles
    }

    private val File.stdAbsPath get() = absolutePath.replace(File.separatorChar, '/')
    private val File.stdPath get() = path.replace(File.separatorChar, '/')
}

class RecompileFiles(
    val effectedSourceFiles: List<File>,
    val redexClasses: List<ChangedFile>,
)