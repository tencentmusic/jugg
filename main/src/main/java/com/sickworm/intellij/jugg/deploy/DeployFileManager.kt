package com.sickworm.intellij.jugg.deploy

import com.android.tools.idea.run.ApkInfo
import com.intellij.openapi.diagnostic.Logger
import com.jetbrains.rd.util.concurrentMapOf
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
    private var uncompiledFiles = concurrentMapOf<String, ChangedFile>()

    /**
     * compiled files. All operation must be thread-safe
     */
    private var compiledFiles = concurrentMapOf<String, ChangedFile>()

    /**
     * Staging files for deployment. All operation must be thread-safe
     */
    private var stagingFiles = concurrentMapOf<String, CompileOutput>()

    /**
     * Deployed files. All operation must be thread-safe
     */
    private val deployedFiles = concurrentMapOf<String, CompileOutput>()

    /**
     * build [JuggDeployData]
     */
    private val deployDataGenerator = DeployDataGenerator(logger.getInstance("DeployDataGenerator"), databaseDir)

    /**
     * get source file by source file name in dex file
     */
    private val sourceFileManager = SourceFileManager(logger.getInstance("SourceFileManager"), databaseDir)

    private var moduleInfos: Map<String, ModuleInfo> = emptyMap()

    @Synchronized
    fun init(apks: List<ApkInfo>, deployedFiles: List<CompileOutput>, resetFilesBeforeTimeMill: Long?) {
        logger.debug("init deploy file manager, apks: ${apks.size}, deployedFiles: ${deployedFiles.size}, resetFilesBeforeTimeMill: $resetFilesBeforeTimeMill")
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
            compiledFiles.remove(it.file.stdPath)
        }

        sourceFileManager.updateFiles(newFiles, emptyList())
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
        logger.debug("updateUncompiledFiles, successFiles: ${successFiles.map { it.file.name } }" +
                ", failedFiles: ${failedFiles.map { it.file.name } }")
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
    fun getUndeployedFiles(): List<ChangedFile> {
        return getUncompiledFiles() + getCompiledFiles()
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
    fun getDeployData(isWarmUp: Boolean = false): JuggDeployData {
        val deployItems = stagingFiles.values.map { it.toDeployItem() }
        return deployDataGenerator.buildDeployData(deployItems, isWarmUp, isNeedCheckRecompile = false)
    }

    @Synchronized
    fun getDeployedFiles(): List<CompileOutput> {
        return deployedFiles.values.toList()
    }

    @Synchronized
    fun commit(juggDeployData: JuggDeployData) {
        logger.debug("commit juggDeployData, staging file size: ${stagingFiles.size}, deployed file size: ${deployedFiles.size}")
        deployDataGenerator.commitDeployedData(juggDeployData)
        deployedFiles.putAll(stagingFiles)
        stagingFiles.clear()
        compiledFiles.clear()
    }

    @TestOnly
    @Synchronized
    fun reset(resetFilesBeforeTimeMill: Long? = null) {
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
    fun resetAfterReinstall() {
        logger.debug("resetAfterReinstall start, staging file size: ${stagingFiles.size}, deployed file size: ${deployedFiles.size}")
        deployDataGenerator.clearDeployedData()
        val stagingFileRelativeSet = stagingFiles.map { it.value.relativeFile.path }.toSet()
        val deployedFiles = deployedFiles.values.filter {
            it.relativeFile.path !in stagingFileRelativeSet
        }
        deployedFiles.forEach {
            stagingFiles[it.file.stdAbsPath] = it
        }
        logger.debug("resetAfterReinstall done, staging file size: ${stagingFiles.size}")
    }

    @Synchronized
    fun updateModuleInfos(moduleInfos: Map<String, ModuleInfo>) {
        this.moduleInfos = moduleInfos
        uncompiledFiles = uncompiledFiles
            .filter {
                moduleInfos[it.value.module.name] != null // filter out module not in moduleInfos
            }.mapValues {
                val newModuleInfo = moduleInfos[it.value.module.name]!!
                it.value.copy(module = newModuleInfo)
            }.toMutableMap()

        val sourceDirs = moduleInfos.values.flatMap {
            it.sourceDirs
        }
        sourceFileManager.init(sourceDirs)
    }

    @Synchronized
    fun getRecompileFiles(compiledFilesThisTime: List<ChangedFile>, isRecompilation: Boolean): RecompileFiles {
        val deployItems = stagingFiles.values
            .filter { it.type == CompileOutput.Type.Dex }
            .map { it.toDeployItem() }
        val juggDeployData = deployDataGenerator.buildDeployData(deployItems,
            isNeedCheckRecompile = true, isRecompilation = isRecompilation)

        val startTime = System.currentTimeMillis()
        val recompileFiles = RecompileFiles(
            getEffectedSourceFiles(juggDeployData.effectedSourceFileNames, compiledFilesThisTime),
            getDesugarInterfaceWithDefaultMethodFiles(juggDeployData.desugaredInterfacesWithDefaultMethods)
        )
        val costTime = System.currentTimeMillis() - startTime
        logger.debug("find recompile files cost: $costTime ms")
        return recompileFiles
    }

    @Synchronized
    fun getAllDesugarClasspath(compileFiles: List<CompileFile>, toDir: File) {
        val filteredClassFiles = compileFiles.filter { it.type == CompileFile.Type.Class }
        val defaultInterfaces = deployDataGenerator.getAllInterfacesWithDefaultMethod(filteredClassFiles)
        val files = getDesugarInterfaceWithDefaultMethodFiles(defaultInterfaces)
        files.forEach {
            val relativePath = it.file.relativeTo(it.baseDir).path
            val destFile = File(toDir, relativePath)
            it.file.copyTo(destFile, overwrite = true)
        }
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
        }.filter {
            // ExternalSyntheticLambda is desugar inner class, just redex main class file is enough
            !it.contains("\$ExternalSyntheticLambda")
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
                        logger.debug("found interface with default method file: $destFile")
                        iterator.remove()
                        val changedFile = ChangedFile(CompileFile.Type.Class, destFile, classPath, moduleInfo)
                        redexClassesFiles.add(changedFile)
                    }
                }
            }
        }
        if (interfaceRelativePaths.isEmpty()) {
            val costTime = System.currentTimeMillis() - startTime
            logger.debug("find interface with default method files cost: $costTime ms")
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
            if (!libraryFile.isFile || libraryFile.extension != "jar") {
                return@libraryLoop
            }

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
                            logger.debug("found default method file in library ${libraryFile.absolutePath}/${relativePath}")
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
                    logger.warn("getDesugarInterfaceWithDefaultMethodFiles: failed to find interface with default method file in " +
                            "library ${libraryFile.absolutePath}/${relativePath}, error: ${e.message}")
                }
            }
        }

        if (interfaceRelativePaths.isNotEmpty()) {
            logger.warn("failed to find interface with default method files: $interfaceRelativePaths")
        }

        val costTime = System.currentTimeMillis() - startTime
        logger.debug("find interface with default method files cost: $costTime ms")
        return redexClassesFiles
    }

    // I have forgotten why I need both stdAbsPath and stdPath, but it seems to be ok to use stdAbsPath only.
    private val File.stdAbsPath get() = absolutePath.replace(File.separatorChar, '/')
}

class RecompileFiles(
    val effectedSourceFiles: List<File>,
    val redexClasses: List<ChangedFile>,
)

private val crc32 = CRC32()

private val File.stdPath get() = path.replace(File.separatorChar, '/')

fun CompileOutput.toDeployItem(): DeployItem {
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