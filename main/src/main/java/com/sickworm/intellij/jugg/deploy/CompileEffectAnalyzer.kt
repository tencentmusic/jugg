package com.sickworm.intellij.jugg.deploy

import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.jugg.compiler.CompileFile
import com.sickworm.intellij.jugg.compiler.CompileOutput
import com.sickworm.intellij.jugg.compiler.DesugarInfo
import com.sickworm.intellij.jugg.compiler.obfuscation.ClassObfuscator
import com.sickworm.intellij.jugg.compiler.obfuscation.MinifyInfo
import com.sickworm.intellij.jugg.compiler.source.kotlin.KmModuleMergerForCompilation
import com.sickworm.intellij.jugg.deploy.data.ClassSourceReader
import com.sickworm.intellij.jugg.deploy.data.DeployDataGenerator
import com.sickworm.intellij.jugg.deploy.data.EffectedClassNode
import com.sickworm.intellij.jugg.deploy.data.SourceFileManager
import com.sickworm.intellij.jugg.deploy.data.sources
import com.sickworm.intellij.jugg.deploy.run.JuggDeployData
import com.sickworm.intellij.jugg.logger.TimeLogger
import com.sickworm.intellij.jugg.project.ChangedFile
import com.sickworm.intellij.jugg.project.data.ModuleInfo
import com.sickworm.intellij.jugg.project.JuggPathManager
import java.io.File

/**
 * Computes compile-time effected scope for incremental deploy.
 */
class CompileEffectAnalyzer(
    private val pathManager: JuggPathManager,
    private val deployDataGenerator: DeployDataGenerator,
    private val sourceFileManager: SourceFileManager,
    private val logger: Logger,
) {
    /**
     * Computes next-round recompile file set from staged dex and compiled source deltas.
     */
    fun getRecompileFiles(
        stagingFiles: List<CompileOutput>,
        compiledFiles: List<ChangedFile>,
        moduleInfos: Map<String, ModuleInfo>,
        isMinified: Boolean,
        isCompilingEffectedSourceFiles: Boolean,
        classObfuscator: ClassObfuscator?,
    ): RecompileFiles {
        val deployItems = stagingFiles
            .filter { it.type == CompileOutput.Type.Dex }
            .map { it.toDeployItem() }
        val changedSourcePaths = compiledFiles
            .filter { it.type == CompileFile.Type.Java || it.type == CompileFile.Type.Kotlin }
            .map { it.file.stdAbsPath }
            .distinct()
        val constRefChangedSourcePaths = if (isCompilingEffectedSourceFiles) {
            emptyList()
        } else {
            changedSourcePaths
        }

        val juggDeployData = deployDataGenerator.buildDeployData(
            deployItems,
            isNeedCheckRecompile = true,
            isNeedCheckRecompileMinifyRemovedClass = isMinified,
            isCompilingEffectedSourceFiles = isCompilingEffectedSourceFiles,
            constRefChangedSourcePaths = constRefChangedSourcePaths,
        )

        val obfuscatedClasses = juggDeployData.effectedClassNodes.map {
            val originClassName = classObfuscator?.getOriginClassSigName(it.className) ?: it.className
            it.copy(className = originClassName)
        }
        if (obfuscatedClasses.isNotEmpty()) {
            logger.debug(
                "getRecompileFiles: effectedClassesNodeForClass: ${obfuscatedClasses.map { it.className }}, " +
                    "obfuscatedClasses: ${obfuscatedClasses.map { it.className }}",
            )
        }

        if (juggDeployData.constRefEffectedSourcePaths.isNotEmpty()) {
            logger.debug("getRecompileFiles: constRef effected files=${juggDeployData.constRefEffectedSourcePaths}")
        }
        val constRefEffectedSourceFiles = juggDeployData.constRefEffectedSourcePaths.mapNotNull {
            File(it).takeIf(File::exists)
        }.distinctBy { it.stdAbsPath }

        val startTime = System.currentTimeMillis()
        val effectedSourceFiles = getEffectedSourceFiles(obfuscatedClasses.sources, moduleInfos)
        val topLevelFacadeEffectedSourcePaths = getTopLevelFacadeEffectedSourcePaths(
            compiledFiles = compiledFiles,
            effectedClassNodes = obfuscatedClasses,
        )
        val recompileFiles = RecompileFiles(
            (effectedSourceFiles + constRefEffectedSourceFiles).distinctBy { it.stdAbsPath },
            emptyList(),
            juggDeployData,
            topLevelFacadeEffectedSourcePaths,
        )
        val costTime = System.currentTimeMillis() - startTime
        logger.debug("find recompile files cost: $costTime ms")
        logger.trace("[PERF] CompileEffectAnalyzer.getRecompileFiles total end, thread=${Thread.currentThread().name}")
        return recompileFiles
    }

    fun getDesugarInfo(
        compileFiles: List<CompileFile>,
        moduleInfo: ModuleInfo,
        moduleInfos: Map<String, ModuleInfo>,
        toDir: File,
        apkFile: File,
    ): DesugarInfo {
        TimeLogger.start("getDesugarInfo")
        val filteredClassFiles = compileFiles.filter { it.type == CompileFile.Type.Class }
        val desugarInfo = deployDataGenerator.getDesugarInfo(filteredClassFiles, apkFile)
        val defaultInterfaces = desugarInfo.allInterfacesWithDefaultMethod
        logger.debug("getAllDesugarClasspath all defaultInterfaces: $defaultInterfaces")
        val files = getClassFilesByName(defaultInterfaces, moduleInfo, moduleInfos)
        logger.debug("getAllDesugarClasspath all files: ${files.map { it.file.path }}")
        files.forEach {
            val relativePath = it.file.relativeTo(it.baseDir).path
            val destFile = File(toDir, relativePath)
            it.file.copyTo(destFile, overwrite = true)
        }
        TimeLogger.end("getDesugarInfo", logger)
        return desugarInfo
    }

    fun getMinifyInfo(
        stagingFiles: List<CompileOutput>,
        moduleInfos: Map<String, ModuleInfo>,
    ): MinifyInfo? {
        val deployItems = stagingFiles
            .filter { it.type == CompileOutput.Type.Dex }
            .map { it.toDeployItem() }

        val juggDeployData = deployDataGenerator.buildDeployData(
            deployItems,
            isNeedCheckRecompile = true,
            isNeedCheckRecompileMinifyRemovedClass = true,
            isCompilingEffectedSourceFiles = false,
        )
        val minifyEffectedNodes = juggDeployData.effectedClassNodes
            .filter { it.effectedType != EffectedClassNode.EffectedType.SOURCE }
        if (minifyEffectedNodes.isEmpty()) {
            logger.debug("getMinifyInfo: no minify effected classes")
            return null
        }

        val inlineEffectedClasses = minifyEffectedNodes.map { node ->
            com.sickworm.intellij.jugg.compiler.obfuscation.InlineEffectedClass(
                className = node.className,
                effectedByClasses = node.effectedByClasses,
            )
        }
        val classFiles = mutableMapOf<String, File>()
        val originalClassNames = minifyEffectedNodes.map { node ->
            val className = if (node.className.startsWith("L") && node.className.endsWith(";")) {
                node.className.substring(1, node.className.length - 1).replace('/', '.')
            } else {
                node.className.replace('/', '.')
            }
            className
        }

        val missingClassFiles = getMissingMinifiedClassFiles(minifyEffectedNodes, moduleInfos)
        missingClassFiles.forEach { changedFile ->
            originalClassNames.forEach { originalClassName ->
                val expectedPath = originalClassName.replace('.', '/') + ".class"
                if (changedFile.file.path.replace('\\', '/').endsWith(expectedPath)) {
                    classFiles[originalClassName] = changedFile.file
                }
            }
        }

        return MinifyInfo(
            inlineEffectedClasses = inlineEffectedClasses,
            classFiles = classFiles,
        )
    }

    private fun getEffectedSourceFiles(effectClassNodes: List<EffectedClassNode>, moduleInfos: Map<String, ModuleInfo>): List<File> {
        val effectedSourceFiles = effectClassNodes
            .fillMissingSourceFile(moduleInfos)
            .map { it.sourceFileName }
            .distinct()
        if (effectedSourceFiles.isEmpty()) {
            return emptyList()
        }

        val sourceFiles = sourceFileManager.getFiles(effectedSourceFiles)
        if (sourceFiles.size < effectedSourceFiles.size) {
            val missingFiles = effectedSourceFiles.filter { fileName ->
                !sourceFiles.any { it.name == fileName }
            }
            logger.warn("getEffectedSourceFiles: missing source files: $missingFiles")
        }
        return sourceFiles
    }

    /**
     * Finds source files that need one more compile because Kotlin may resolve old top-level file facade
     * signatures from `.kotlin_module` before using the changed source declaration.
     */
    private fun getTopLevelFacadeEffectedSourcePaths(
        compiledFiles: List<ChangedFile>,
        effectedClassNodes: List<EffectedClassNode>,
    ): Set<String> {
        val sourceEffectNodes = effectedClassNodes.sources
        if (sourceEffectNodes.isEmpty()) {
            return emptySet()
        }
        val extensionClassesByClassPath = mutableMapOf<String, Set<String>>()
        return compiledFiles
            .asSequence()
            .filter { it.type == CompileFile.Type.Java || it.type == CompileFile.Type.Kotlin }
            .filter { changedFile ->
                val matchedNodes = sourceEffectNodes.filter { it.sourceFileName == changedFile.file.name }
                if (matchedNodes.isEmpty()) {
                    return@filter false
                }
                val kotlinClassPath = changedFile.module.buildPathInfo.kotlinClassPath
                val extensionClasses = extensionClassesByClassPath.getOrPut(kotlinClassPath.absolutePath) {
                    val kmModuleMerger = KmModuleMergerForCompilation(kotlinClassPath)
                    kmModuleMerger.loadAndMerge()
                    kmModuleMerger.getExtensionClasses().toSet()
                }
                if (extensionClasses.isEmpty()) {
                    return@filter false
                }
                val isEffectedByTopLevelFacade = matchedNodes.any { node ->
                    node.effectedByClasses.any { extensionClasses.contains(it) }
                }
                if (isEffectedByTopLevelFacade) {
                    logger.debug(
                        "getRecompileFiles: ${changedFile.file.name} is effected by top level facade, " +
                            "extensionClasses=$extensionClasses, effectNodes=$matchedNodes",
                    )
                }
                isEffectedByTopLevelFacade
            }
            .map { it.file.absolutePath }
            .toSet()
    }

    private fun List<EffectedClassNode>.fillMissingSourceFile(moduleInfos: Map<String, ModuleInfo>): List<EffectedClassNode> {
        val existsSourceNode = mutableListOf<EffectedClassNode>()
        val missingSourceNode = mutableListOf<EffectedClassNode>()
        forEach {
            if (it.sourceFileName.endsWith(".kt") || it.sourceFileName.endsWith(".java")) {
                existsSourceNode.add(it)
            } else if (!it.className.contains("\$\$ExternalSyntheticLambda")
                && !it.className.isBootClasspathClass
            ) {
                missingSourceNode.add(it)
            }
        }
        if (missingSourceNode.isEmpty()) {
            return this
        }

        val missingClassNames = missingSourceNode.map { it.className }
        val allDependModules = moduleInfos.values.toList()
        val allDependLibraries = moduleInfos.values
            .flatMap { module -> module.libraryDependencies.map { it.file } }
            .toSet()
            .toList()

        val searchedClassFiles = getClassFilesByName(missingClassNames, allDependModules, allDependLibraries)
        searchedClassFiles.forEach { searchedClassFile ->
            val (className, source) = ClassSourceReader(searchedClassFile.file).read()
            val node = missingSourceNode.find { it.className == className?.classSigName }
            if (node == null || className == null || source == null) {
                return@forEach
            }
            existsSourceNode.add(node.copy(sourceFileName = source))
            missingSourceNode.remove(node)
        }

        return existsSourceNode + missingSourceNode
    }

    private fun getMissingMinifiedClassFiles(
        classNodes: List<EffectedClassNode>,
        moduleInfos: Map<String, ModuleInfo>,
    ): List<ChangedFile> {
        val classNames = classNodes.map { it.className }.distinct()
        val allDependModules = moduleInfos.values.toList()
        val allDependLibraries = moduleInfos.values
            .flatMap { module -> module.libraryDependencies.map { it.file } }
            .toSet()
            .toList()
        return getClassFilesByName(classNames, allDependModules, allDependLibraries)
    }

    private fun getClassFilesByName(
        classNames: List<String>,
        moduleInfo: ModuleInfo,
        moduleInfos: Map<String, ModuleInfo>,
    ): List<ChangedFile> {
        val dependModules = moduleInfo.moduleDependencies.mapNotNull {
            moduleInfos[it.moduleName]
        }
        val dependLibraries = moduleInfo.libraryDependencies.map { it.file }
        val foundInterfaces = getClassFilesByName(classNames, dependModules, dependLibraries)
        if (foundInterfaces.size >= classNames.size) {
            return foundInterfaces
        }

        val remainInterfaces = classNames.filter {
            val expectFileName = File(it.classNameToPath).name
            foundInterfaces.any { foundInterface -> foundInterface.file.name == expectFileName }.not()
        }
        val allDependModules = moduleInfos.values.toList()
        val allDependLibraries = moduleInfos.values
            .flatMap { module -> module.libraryDependencies.map { it.file } }
            .toSet()
            .toList()
        val foundInterfacesInAllModules = getClassFilesByName(remainInterfaces, allDependModules, allDependLibraries)
        return foundInterfaces + foundInterfacesInAllModules
    }

    private fun getClassFilesByName(
        classNames: List<String>,
        dependModules: List<ModuleInfo>,
        dependLibraries: List<File>,
    ): List<ChangedFile> {
        return ClassFileLookupHelper.findClassFilesByName(
            classNames = classNames,
            dependModules = dependModules,
            dependLibraries = dependLibraries,
            tempDir = pathManager.tmpDir,
            logger = logger,
        ).map { lookupResult ->
            ChangedFile(
                type = CompileFile.Type.Class,
                file = lookupResult.file,
                baseDir = lookupResult.baseDir,
                module = lookupResult.module,
            )
        }
    }

}

/**
 * RecompileFiles groups source files/classes/deploy payload that must be recompiled and redexed during compatibility retries.
 */
class RecompileFiles(
    val effectedSourceFiles: List<File>,
    val redexClasses: List<ChangedFile>,
    val juggDeployData: JuggDeployData,
    val topLevelFacadeEffectedSourcePaths: Set<String> = emptySet(),
)
