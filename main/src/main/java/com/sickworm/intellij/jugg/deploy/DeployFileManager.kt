package com.sickworm.intellij.jugg.deploy

import com.sickworm.intellij.jugg.apk.ApkInfo
import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.jugg.project.ChangedFile
import com.sickworm.intellij.jugg.compiler.CompileFile
import com.sickworm.intellij.jugg.compiler.CompileOutput
import com.sickworm.intellij.jugg.compiler.DesugarInfo
import com.sickworm.intellij.jugg.deploy.data.ClassSourceReader
import com.sickworm.intellij.jugg.project.data.ModuleInfo
import com.sickworm.intellij.jugg.deploy.data.DeployDataGenerator
import com.sickworm.intellij.jugg.deploy.data.EffectedClassNode
import com.sickworm.intellij.jugg.deploy.data.ResourceApkGenerator
import com.sickworm.intellij.jugg.deploy.data.SourceFileManager
import com.sickworm.intellij.jugg.deploy.run.DeployItem
import com.sickworm.intellij.jugg.deploy.run.JuggDeployData
import com.sickworm.intellij.jugg.gradle.compile.isChild
import com.sickworm.intellij.jugg.jvmti_agent.BuildConfig
import com.sickworm.intellij.jugg.logger.TimeLogger
import com.sickworm.intellij.jugg.logger.getInstance
import com.sickworm.intellij.jugg.project.JuggInternalException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.jetbrains.annotations.TestOnly
import java.io.File
import java.util.zip.CRC32
import java.util.zip.ZipFile

/**
 * Manage runtime deploy file status and provides [JuggDeployData]
 */
class DeployFileManager(
    projectDir: File,
    private val logger: Logger,
    private val tmpDir: File,
    databaseDir: File,
    private val coroutineScope: CoroutineScope,
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

    private val resourceApkGenerator = ResourceApkGenerator(
        deployDataGenerator.deployDataDatabase,
        databaseDir.resolve("resource_apks"),
        logger,
    )

    /**
     * get source file by source file name in dex file
     */
    private val sourceFileManager = SourceFileManager(projectDir, databaseDir, logger.getInstance("SourceFileManager"))

    private var moduleInfos: Map<String, ModuleInfo> = emptyMap()

    @Synchronized
    fun init(apks: List<ApkInfo>, deployedFiles: List<CompileOutput>, resetFilesBeforeTimeMill: Long?) {
        logger.debug("init deploy file manager, apks: ${apks.size}, deployedFiles: ${deployedFiles.size}, resetFilesBeforeTimeMill: $resetFilesBeforeTimeMill")
        reset(resetFilesBeforeTimeMill)
        val deployItems = deployedFiles.map { it.toDeployItem() }
        deployDataGenerator.init(apks, deployItems)
        resourceApkGenerator.deleteResourceApk()

        this.deployedFiles.clear()
        deployedFiles.forEach {
            this.deployedFiles[it.file.stdAbsPath] = it
        }
    }

    @Synchronized
    fun addChangedFile(files: List<ChangedFile>) {
        logger.debug("add changed files, size: ${files.size}, paths: $files")
        val newFiles = files.filter {
            uncompiledFiles.containsKey(it.file.stdPath).not()
        }
        files.forEach {
            uncompiledFiles[it.file.stdPath] = it // update ChangedFile.compiledTimes
            compiledFiles.remove(it.file.stdPath)
        }

        coroutineScope.launch {
            sourceFileManager.updateFiles(newFiles, emptyList())
        }
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

        coroutineScope.launch {
            sourceFileManager.updateFiles(emptyList(), files.filter { !it.exists() })
        }
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

    /**
     * @return is no file changes since last compile finished (no matter success or failed)
     */
    @Synchronized
    fun isNoFileChanges(): Boolean {
        val undeployedFiles = getUndeployedFiles()
        return undeployedFiles.all { it.hasCompiledOnce }
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
    fun getDeployData(isWarmUp: Boolean = false, isEnableCompatDeploy: Boolean = false): JuggDeployData {
        val deployItems = stagingFiles.values.map { it.toDeployItem() }
        val deployData = deployDataGenerator.buildDeployData(deployItems, isWarmUp, isNeedCheckRecompile = false)
        if (isEnableCompatDeploy) {
            return appendCompatDeployFiles(deployData)
        }
        return deployData
    }

    fun appendCompatDeployFiles(deployData: JuggDeployData): JuggDeployData {
        var compatDeployData = deployData.copy(isCompatDeploy = true, isPushOverlayOnly = true)

        // filter origin overlay files to avoid deployed by Apply Changes
        compatDeployData = compatDeployData.copy(overlays = compatDeployData.overlays.filter {
            it.type != CompileOutput.Type.Res && it.type != CompileOutput.Type.Asset
        })

        if (!deployData.isEmpty) {
            // no need push flag file if empty (dry deploy)
            val enableFlag = DeployItem(
                name = BuildConfig.ENABLE_COMPAT_DEPLOY_FLAG_FILE,
                type = CompileOutput.Type.Asset,
                checksum = CRC32().let {
                    it.update(ByteArray(0))
                    it.value
                },
                content = ByteArray(0),
                apkPath = DeployItem.FLAG_BASE_APK,
            )
            compatDeployData = compatDeployData.copy(overlays = compatDeployData.overlays + enableFlag)
        }

        if (deployData.overlays.isNotEmpty()) {
            val resourceApks = resourceApkGenerator.getResourceApkDeployItem(deployData.overlays, deployedFiles)
            compatDeployData = compatDeployData.copy(overlays = compatDeployData.overlays + resourceApks)
        }

        return compatDeployData
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

        // remove gradle files for library incremental compile is finished
        val removeBuildFileFiles = uncompiledFiles.filter {
            it.value.type == CompileFile.Type.BuildFile
        }
        removeBuildFileFiles.keys.forEach {
            logger.debug("remove gradle file: $it")
            uncompiledFiles.remove(it)
        }
    }

    @TestOnly
    @Synchronized
    fun reset(resetFilesBeforeTimeMill: Long? = null) {
        logger.debug("reset deploy file manager, resetFilesBeforeTimeMill=$resetFilesBeforeTimeMill")
        val remainUncompiledFiles = uncompiledFiles.filter {
            resetFilesBeforeTimeMill != null &&
                    it.value.file.exists() &&
                    it.value.file.lastModified() > resetFilesBeforeTimeMill
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
        resourceApkGenerator.deleteResourceApk()
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
                // filter out module not in moduleInfos
                moduleInfos[it.value.module.name] != null
                        // global build file belongs to virtual module, and virtual module is not in moduleInfos
                        || it.value.module.name == ModuleInfo.virtualModule.name
            }.mapValues {
                val newModuleInfo = moduleInfos[it.value.module.name] ?: ModuleInfo.virtualModule
                it.value.copy(module = newModuleInfo)
            }.toMutableMap()

        val sourceDirs = moduleInfos.values.flatMap {
            it.sourceDirs
        }
        sourceFileManager.init(sourceDirs)
    }

    @Synchronized
    fun getRecompileFiles(isMinified: Boolean): RecompileFiles {
        logger.debug("getRecompileFiles")
        val deployItems = stagingFiles.values
            .filter { it.type == CompileOutput.Type.Dex }
            .map { it.toDeployItem() }
        val juggDeployData = deployDataGenerator.buildDeployData(deployItems,
            isNeedCheckRecompile = true, isNeedCheckRecompileMinifyRemovedClass = isMinified)

        val startTime = System.currentTimeMillis()
        val recompileFiles = RecompileFiles(
            getEffectedSourceFiles(juggDeployData.effectedClassNodes),
            juggDeployData,
        )
        val costTime = System.currentTimeMillis() - startTime
        logger.debug("find recompile files cost: $costTime ms")
        return recompileFiles
    }

    @Synchronized
    fun getDesugarInfo(compileFiles: List<CompileFile>, moduleInfo: ModuleInfo, toDir: File, apkFile: File): DesugarInfo {
        TimeLogger.start("getDesugarInfo")
        val filteredClassFiles = compileFiles.filter { it.type == CompileFile.Type.Class }
        val desugarInfo = deployDataGenerator.getDesugarInfo(filteredClassFiles, apkFile)
        val defaultInterfaces = desugarInfo.allInterfacesWithDefaultMethod
        logger.debug("getAllDesugarClasspath all defaultInterfaces: $defaultInterfaces")
        val files = getDesugarInterfaceWithDefaultMethodFiles(defaultInterfaces, moduleInfo)
        logger.debug("getAllDesugarClasspath all files: ${files.map { it.file.path }}")
        files.forEach {
            val relativePath = it.file.relativeTo(it.baseDir).path
            val destFile = File(toDir, relativePath)
            it.file.copyTo(destFile, overwrite = true)
        }
        TimeLogger.end("getDesugarInfo", logger)

        return desugarInfo
    }

    /**
     * Get source files that effected by [compiledFiles].
     * e.g. A.java invokes B.func(), B.func() is changed and compiled, then A.java is effected, and it will be returned.
     */
    private fun getEffectedSourceFiles(effectClassNodes: List<EffectedClassNode>): List<File> {
        val effectedSourceFiles = effectClassNodes
            .fillMissingSourceFile()
            .map { it.sourceFileName }.distinct()

        if (effectedSourceFiles.isEmpty()) {
            logger.debug("getEffectedSourceFiles: no effected source files")
            return emptyList()
        }

        val sourceFiles = sourceFileManager.getFiles(effectedSourceFiles)
        if (sourceFiles.size < effectedSourceFiles.size) {
            val missingFiles = effectedSourceFiles.filter { fileName ->
                !sourceFiles.any { it.name == fileName }
            }
            logger.warn("getEffectedSourceFiles: missing source files: $missingFiles")
        }

        if (sourceFiles.isEmpty()) {
            logger.debug("getEffectedSourceFiles: no uncompiled source files")
            return emptyList()
        }

        logger.debug("getEffectedSourceFiles: effectedSourceFiles ${effectedSourceFiles}, source files $sourceFiles")
        return sourceFiles
    }

    /**
     * If .source is missing by minify, find it by .class which is not minified yet
     */
    private fun List<EffectedClassNode>.fillMissingSourceFile(): List<EffectedClassNode> {
        val existsSourceNode = mutableListOf<EffectedClassNode>()
        val missingSourceNode = mutableListOf<EffectedClassNode>()
        forEach {
            if (it.sourceFileName.endsWith(".kt") || it.sourceFileName.endsWith(".java")) {
                existsSourceNode.add(it)
            } else {
                missingSourceNode.add(it)
            }
        }
        if (missingSourceNode.isEmpty()) {
            return this
        }

        logger.debug("found missing source files: $missingSourceNode, total: ${this.size}")
        val missingClassNames = missingSourceNode.map { it.className }
        val allDependModules = moduleInfos.values.toList()
        val allDependLibraries = mutableSetOf<File>()
        moduleInfos.values.forEach { moduleInfoIt ->
            moduleInfoIt.libraryDependencies.forEach {
                allDependLibraries.add(it.file)
            }
        }
        val searchedClassFiles = getClassesFileByName(missingClassNames, allDependModules, allDependLibraries.toList())
        searchedClassFiles.forEach { searchedClassFile ->
            val (className, source) = ClassSourceReader(searchedClassFile.file).read()
            val node = missingSourceNode.find { it.className == className }
            if (node == null || className == null || source == null) {
                logger.warn("fillMissingSourceFile found invalid EffectedClassNode: $node, source: $source, className: $className, which should not happened")
                return@forEach
            }
            existsSourceNode.add(node.copy(sourceFileName = source))
            missingSourceNode.remove(node)
        }

        if (missingSourceNode.isNotEmpty()) {
            logger.debug("Found all source files: $existsSourceNode")
            logger.warn("Failed to find source files for: ${missingSourceNode.map { it.className }}")
        }

        return existsSourceNode + missingSourceNode
    }


    private fun getDesugarInterfaceWithDefaultMethodFiles(interfaceNames: List<String>, moduleInfo: ModuleInfo): List<ChangedFile> {
        val dependModules = moduleInfo.moduleDependencies.mapNotNull {
            moduleInfos[it.moduleName]
        }
        val dependLibraries = moduleInfo.libraryDependencies.map {
            it.file
        }
        // search in module dependency first
        val foundInterfaces = getClassesFileByName(interfaceNames, dependModules, dependLibraries)
        if (foundInterfaces.size >= interfaceNames.size) {
            logger.debug("Found all interface with default method files in module(${moduleInfo.name}) dependencies")
            return foundInterfaces
        }

        val remainInterfaces = interfaceNames.filter {
            val expectFileName = File(it.classNameToPath).name
            foundInterfaces.any { foundInterface ->
                foundInterface.file.name == expectFileName
            }.not()
        }
        logger.debug("Failed to find interface with default method files in module(${moduleInfo.name}) dependencies: $remainInterfaces")

        val allDependModules = moduleInfos.values.toList()
        val allDependLibraries = mutableSetOf<File>()
        moduleInfos.values.forEach { moduleInfoIt ->
            moduleInfoIt.libraryDependencies.forEach {
                allDependLibraries.add(it.file)
            }
        }

        // search in all module last
        val foundInterfacesInAllModules = getClassesFileByName(
            remainInterfaces, allDependModules, allDependLibraries.toList())

        if (foundInterfacesInAllModules.size >= remainInterfaces.size) {
            logger.debug("Found all interface with default method files in all dependencies")
            return foundInterfaces + foundInterfacesInAllModules
        }

        val lastRemainInterface = remainInterfaces.filter {
            val expectFileName = File(it.classNameToPath).name
            foundInterfacesInAllModules.any { foundInterface ->
                foundInterface.file.name == expectFileName
            }.not()
        }
        logger.warn("Failed to find interface with default method files in all dependencies: $lastRemainInterface, compilation result may be wrong.")

        return foundInterfaces + foundInterfacesInAllModules
    }

    private fun getClassesFileByName(classNames: List<String>,
                                     dependModules: List<ModuleInfo>,
                                     dependLibraries: List<File>): List<ChangedFile> {
        if (classNames.isEmpty()) {
            logger.debug("getClassesFileByName: no class")
            return emptyList()
        }

        val startTime = System.currentTimeMillis()
        val classRelativePaths = classNames.map {
            it.classNameToPath
        }.filter {
            // ExternalSyntheticLambda is desugar inner class, just redex main class file is enough
            !it.contains("\$ExternalSyntheticLambda")
        }.toMutableList()
        logger.debug("getClassesFileByName: classRelativePaths $classRelativePaths")

        val foundClassesFiles = mutableListOf<ChangedFile>()
        dependModules.forEach moduleLoop@{ moduleInfo ->
            moduleInfo.buildPathInfo.allClassPath.forEach {  classPath ->
                if (!classPath.isDirectory) {
                    return@forEach
                }
                val iterator = classRelativePaths.iterator()
                while (iterator.hasNext()) {
                    val relativePath = iterator.next()
                    val destFile = File(classPath, relativePath)
                    if (destFile.exists()) {
                        logger.debug("found class with default method file: $destFile")
                        iterator.remove()
                        val changedFile = ChangedFile(CompileFile.Type.Class, destFile, classPath, moduleInfo)
                        foundClassesFiles.add(changedFile)
                    }
                }
            }
        }
        if (classRelativePaths.isEmpty()) {
            val costTime = System.currentTimeMillis() - startTime
            logger.debug("find class with default method files cost: $costTime ms")
            return foundClassesFiles
        }

        logger.debug("getClassesFileByName: libraryPaths ${dependLibraries.size}")

        dependLibraries.forEach libraryLoop@{ libraryFile ->
            if (!libraryFile.isFile || libraryFile.extension != "jar") {
                return@libraryLoop
            }

            val iterator = classRelativePaths.iterator()
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
                                foundClassesFiles.add(changedFile)
                            }
                        }
                    }
                } catch (e: Exception) {
                    logger.warn("getClassesFileByName: failed to find class with default method file in " +
                            "library ${libraryFile.absolutePath}/${relativePath}, error: ${e.message}")
                }
            }
        }

        if (classRelativePaths.isNotEmpty()) {
            logger.debug("failed to find class with default method files: $classRelativePaths")
        }

        val costTime = System.currentTimeMillis() - startTime
        logger.debug("find class with default method files cost: $costTime ms")
        return foundClassesFiles
    }

    fun isEnableDesugared(): Boolean {
        return deployDataGenerator.isEnableDesugared()
    }

    // I have forgotten why I need both stdAbsPath and stdPath, but it seems to be ok to use stdAbsPath only.
    private val File.stdAbsPath get() = absolutePath.replace(File.separatorChar, '/')
}

class RecompileFiles(
    val effectedSourceFiles: List<File>,
    val juggDeployData: JuggDeployData,
)

private val crc32 = CRC32()

private val File.stdPath get() = path.replace(File.separatorChar, '/')

fun CompileOutput.toDeployItem(deployName: String = deployItemName): DeployItem {
    val bytes = file.readBytes()
    val crc = crc32.run {
        reset()
        update(bytes)
        value
    }
    when (type) {
        CompileOutput.Type.Dex -> {
            return DeployItem(deployName, type, crc, bytes, DeployItem.FLAG_CLASS)
        }
        CompileOutput.Type.Res, CompileOutput.Type.Asset, CompileOutput.Type.NativeLib -> {
            if (apkPath == null) {
                throw JuggInternalException.outputDidNotSpecificApkPath(this.toString())
            }
            return DeployItem(deployName, type, crc, bytes, apkPath)
        }
        else -> {
            return DeployItem(deployName, type, crc, bytes, DeployItem.FLAG_BASE_APK) // will not apply to device
        }
    }
}

val CompileOutput.deployItemName: String get() {
    return if (type == CompileOutput.Type.Dex) {
        relativeFile.stdPath
            .replace('/', '.')
            .replace(file.name, file.nameWithoutExtension)
    } else {
        relativeFile.stdPath
    }
}