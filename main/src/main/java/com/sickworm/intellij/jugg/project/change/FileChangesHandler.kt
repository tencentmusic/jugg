package com.sickworm.intellij.jugg.project.change

import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.jugg.compiler.CompileFile
import com.sickworm.intellij.jugg.compiler.ICompileContext
import com.sickworm.intellij.jugg.compiler.external.isInExternalBuildCacheDirectory
import com.sickworm.intellij.jugg.compiler.external.resolveExternalBuilds
import com.sickworm.intellij.jugg.project.info.ModuleInfo
import com.sickworm.intellij.jugg.compiler.relativePathForPrintSafe
import com.sickworm.intellij.jugg.git.FileMatcher
import com.sickworm.intellij.jugg.git.IFileMatcher
import com.sickworm.intellij.jugg.gradle.compile.isChild
import com.sickworm.intellij.jugg.gradle.compile.pathEquals
import com.sickworm.intellij.jugg.ide.bean.JuggSettings
import com.sickworm.intellij.jugg.project.runtime.JuggPathManager
import java.io.File
import java.nio.file.Path

/**
 * Filter file changes that is related to source file of this [projectDir]
 */
class FileChangesHandler(
    private val projectDir: File,
    private val juggRootDir: File,
    private val logger: Logger,
) :
    IFileChangesHandler
{

    companion object {
        private val defaultMatchRule = """
            *.gradle
            *.gradle.kts
            *.jar
            *.aar
            *.aidl
            local.properties
            gradle.properties
            libs.versions.toml
        """.trimIndent().split("\n").toList()
    }

    private var buildFileMatcher: IFileMatcher = FileMatcher().also {
        it.init(projectDir, defaultMatchRule)
    }

    private var doNotIgnoreModulePaths = emptyList<String>()


    @Volatile
    private var scope = Scope(
        allModules = emptyList(),
        compiledModules = emptyList(),
        buildDirs = emptyList(),
        scanRoots = listOf(projectDir.normalizedPath),
        excludedExternalBuildDirs = emptyList(),
    )
    @Volatile
    private var listenedContext: ICompileContext? = null

    @Suppress("ConvertArgumentToSet")
    override fun init(compileContext: ICompileContext) {
        if (listenedContext !== compileContext) {
            listenedContext = compileContext
            compileContext.listenUpdate {
                if (listenedContext === compileContext) {
                    updateScope(compileContext)
                }
            }
        }
        updateScope(compileContext)
    }

    private fun updateScope(compileContext: ICompileContext) {
        logger.debug("init FileChangesHandler")
        val allModules = compileContext.modules.values.toList()
        val buildDirs = allModules.flatMap { module ->
            // buildPathInfo roots may point to fetched classpath storage after a remote build.
            val localBuildDir = module.buildPathInfo.copy(
                projectRootDir = module.projectRootDir,
                moduleRootDir = module.moduleRootDir,
            ).buildDir
            listOf(localBuildDir, File(module.moduleRootDir, "build"))
        }.map {
            it.absoluteFile.normalize()
        }.distinctBy {
            it.path
        }

        var ignoreModules = emptyList<ModuleInfo>()
        if (JuggSettings.isIgnoreWontCompileModules) {
            val notCompiledModuleNames = findNotCompiledWithApplicationModules(compileContext)
            ignoreModules = allModules.filter { module ->
                if (doNotIgnoreModulePaths.contains(module.moduleStdPath)) {
                    return@filter false
                }
                if (notCompiledModuleNames.contains(module.name)) {
                    return@filter true
                }
                return@filter false
            }
        }

        val compiledModules = allModules - ignoreModules
        scope = createScope(allModules, compiledModules, buildDirs)
        val sourceDirs = compiledModules.flatMap { it.sourceDirs }
        val resourceDirs = compiledModules.flatMap { it.resourceDirs }
        val assetDirs = compiledModules.flatMap { it.assetsDirs }
        logger.debug("""
            |File changes scope:
            |    source dirs:
            |        ${sourceDirs.relativePathForPrintSafe(projectDir) }
            |    resource dirs:
            |        ${resourceDirs.relativePathForPrintSafe(projectDir) }
            |    asset dirs:
            |        ${assetDirs.relativePathForPrintSafe(projectDir) }
            |    ignore modules(won't compile):
            |        ${ignoreModules.joinToString(", ") { "${it.moduleStdPath}(${it.name})" }}
            |""".trimMargin())
    }

    private fun findNotCompiledWithApplicationModules(compileContext: ICompileContext): Set<String> {
        val notCompiledModuleNames = compileContext.modules.keys.toMutableSet()
        val applicationModule = compileContext.applicationModule
        if (applicationModule == null) {
            logger.debug("findNotCompiledWithApplicationModules applicationModule is null, exit finding")
            return emptySet()
        }

        notCompiledModuleNames.remove(applicationModule.name)
        var parentModules = listOf(applicationModule)
        var depthLimit = 100 // avoid dead loop
        while (parentModules.isNotEmpty() && depthLimit-- > 0) {
            val nextParentModules = mutableListOf<ModuleInfo>()
            parentModules.forEach parentModulesLoop@{ parentModule ->
                parentModule.moduleDependencies.forEach { moduleDependency ->
                    val hasKey = notCompiledModuleNames.remove(moduleDependency.moduleName)
                    if (!hasKey) {
                        // already checked, continue
                        return@forEach
                    }
                    val dependModuleInfo = compileContext.modules[moduleDependency.moduleName]
                    if (dependModuleInfo != null) {
                        nextParentModules.add(dependModuleInfo)
                    }
                }
            }
            parentModules = nextParentModules
        }

        logger.debug("findNotCompiledWithApplicationModules result: $notCompiledModuleNames")
        return notCompiledModuleNames
    }

    override fun filter(file: List<File>): List<ChangedFile> {
        val result = mutableListOf<ChangedFile>()
        file.forEach {
            if (it.isDirectory) {
                if (!shouldExpandDirectory(it)) {
                    return@forEach
                }
                it.listFiles()?.toList()?.let { subFiles ->
                    val subResult = filter(subFiles)
                    result.addAll(subResult)
                }
            } else {
                val changeFile = toChangeFile(it)
                if (changeFile != null) {
                    result.add(changeFile)
                }
            }
        }
        return result.distinctBy { it.file.path }
    }

    override fun updateBuildFileRules(rules: List<String>, doNotIgnoreModulePaths: List<String>) {
        logger.debug("updateBuildFileRules: $rules, doNotIgnoreModulePaths: $doNotIgnoreModulePaths")
        val newRules = defaultMatchRule + rules
        buildFileMatcher.init(projectDir, newRules)
        this.doNotIgnoreModulePaths = doNotIgnoreModulePaths

        if (scope.allModules.isNotEmpty()) {
            appendCompiledModules()
        }
    }

    private fun appendCompiledModules() {
        val currentScope = scope
        var compiledModules = currentScope.compiledModules
        doNotIgnoreModulePaths.forEach { doNotIgnoreModulePath ->
            val isNotInCompiledModules = compiledModules.all {
                it.moduleStdPath != doNotIgnoreModulePath
            }
            if (isNotInCompiledModules) {
                val relativeModule = currentScope.allModules.find {
                    it.moduleStdPath == doNotIgnoreModulePath
                }
                if (relativeModule == null) {
                    logger.debug("doNotIgnoreModulePath not found for $doNotIgnoreModulePath")
                } else {
                    compiledModules = compiledModules + relativeModule
                    logger.debug("doNotIgnoreModulePath add $doNotIgnoreModulePath, " +
                            "srcDirs: ${relativeModule.sourceDirs}, " +
                            "resourceDirs: ${relativeModule.resourceDirs}, " +
                            "assetDirs: ${relativeModule.assetsDirs}")
                }
            }
        }
        scope = createScope(currentScope.allModules, compiledModules, currentScope.buildDirs)
    }

    private fun createScope(
        allModules: List<ModuleInfo>,
        compiledModules: List<ModuleInfo>,
        buildDirs: List<File>,
    ): Scope {
        val externalSourceDirs = compiledModules.flatMap { module ->
            module.externalBuildInfos.flatMap { it.inputDirs }.map { it.directory }
        }
        val scanRoots = (listOf(projectDir) + compiledModules.map { it.moduleRootDir } + externalSourceDirs)
            .map { it.normalizedPath }
            .distinct()
        val excludedExternalBuildDirs = compiledModules.flatMap { module ->
            module.externalBuildInfos.flatMap { it.excludedDirs }
        }.map { it.normalizedPath }.distinct()
        return Scope(allModules, compiledModules, buildDirs, scanRoots, excludedExternalBuildDirs)
    }

    private fun shouldExpandDirectory(directory: File): Boolean {
        if (directory.isInBuildDir || directory.hasExcludedExternalBuildDirectory()) {
            return false
        }
        val directoryPath = directory.normalizedPath
        return scope.scanRoots.any { scanRoot ->
            directoryPath.startsWith(scanRoot) || scanRoot.startsWith(directoryPath)
        }
    }

    private fun toChangeFile(file: File): ChangedFile? {
        // is directory
        if (file.isDirectory) {
            return null
        }
        if (file.isInBuildDir) {
            return null
        }
        if (!file.exists()) {
            return null
        }

        checkBuildFiles(file)?.let {
            return it
        }
        checkAndroidManifest(file)?.let {
            return it
        }
        checkComposeResource(file)?.let {
            return it
        }
        checkExternalBuildSource(file)?.let {
            return it
        }
        checkSource(file)?.let {
            return it
        }
        // check after source to exclude files in resource and assets
        checkNativeLib(file)?.let {
            return it
        }

        return null
    }

    private fun checkExternalBuildSource(file: File): ChangedFile? {
        if (file.hasExcludedExternalBuildDirectory()) {
            return null
        }
        val target = resolveExternalBuilds(getModules(), file).firstOrNull() ?: return null
        val baseDir = target.matchedInputDir?.directory
            ?: file.absoluteFile.normalize().parentFile
            ?: return null
        return ChangedFile(CompileFile.Type.ExternalBuildSource, file, baseDir, target.module)
    }

    private fun checkComposeResource(file: File): ChangedFile? {
        if (file.name == ".DS_Store") {
            return null
        }
        val normalizedFile = file.normalize()
        getModules().forEach { module ->
            val resourceDir = module.composeResourceInfo
                ?.resourceDirectories
                ?.firstOrNull {
                    normalizedFile.path.startsWith(it.directory.normalize().path + File.separator)
                }
                ?: return@forEach
            return ChangedFile(CompileFile.Type.ComposeResource, file, resourceDir.directory, module)
        }
        return null
    }

    private fun checkSource(file: File): ChangedFile? {
        getModules().forEach { module ->
            val baseSourceDir = module.sourceDirs.find {
                file.normalize()
                file.path.startsWith(it.path)
            }
            if (baseSourceDir != null) {
                val type = when (file.extension) {
                    "java" -> CompileFile.Type.Java
                    "kt" -> CompileFile.Type.Kotlin
                    else -> {
                        logger.debug("source file ${file.name} has invalid extension, ignore")
                        return null
                    }
                }
                return ChangedFile(type, file, baseSourceDir, module)
            }

            val baseResourceDir = module.resourceDirs.find { file.path.startsWith(it.path) }
            if (baseResourceDir != null) {
                if (file.name == ".DS_Store") {
                    logger.debug("resource file ${file.name} has invalid extension, ignore")
                    return null
                }
                return ChangedFile(CompileFile.Type.Resource, file, baseResourceDir, module)
            }

            val baseAssetDir = module.assetsDirs.find { file.path.startsWith(it.path) }
            if (baseAssetDir != null) {
                return ChangedFile(CompileFile.Type.Asset, file, baseAssetDir, module)
            }
        }

        return null
    }

    private fun checkBuildFiles(file: File): ChangedFile? {
        val isInJuggDir = file.isChild(juggRootDir)
        if (isInJuggDir) {
            return null
        }

        val gradleJuggDir = JuggPathManager(projectDir).stableGradleDir
        if (file.isChild(gradleJuggDir)) {
            return null
        }

        val isMatched = buildFileMatcher.isMatch(file)
        if (!isMatched) {
            return null
        }

        getModules().forEach inner@{ module ->
            val moduleRootDir = module.moduleRootDir
            if (file.isChild(moduleRootDir)) {
                return ChangedFile(
                    CompileFile.Type.BuildFile,
                    file,
                    moduleRootDir,
                    module
                )
            }
        }

        val projectRootDir = getProjectRootDir()
        if (projectRootDir != null && file.isChild(projectRootDir)) {
            return ChangedFile(
                CompileFile.Type.BuildFile,
                file,
                projectRootDir,
                ModuleInfo.virtualModule
            )
        }

        return null
    }

    private fun checkAndroidManifest(file: File): ChangedFile? {
        val isAndroidManifest = file.name == "AndroidManifest.xml"
        if (!isAndroidManifest) {
            return null
        }
        val isInJuggDir = file.isChild(juggRootDir)
        if (isInJuggDir) {
            return null
        }

        getModules().forEach inner@{ module ->
            val moduleRootDir = module.moduleRootDir
            if (file.isChild(moduleRootDir)) {
                return ChangedFile(
                    CompileFile.Type.AndroidManifest,
                    file,
                    moduleRootDir,
                    module
                )
            }
        }

        return null
    }

    private val File.isInBuildDir: Boolean get() {
        val file = absoluteFile.normalize()
        return scope.buildDirs.any { buildDir ->
            file.pathEquals(buildDir) || file.isChild(buildDir)
        }
    }

    private val abiFolders = listOf("armeabi", "armeabi-v7a", "arm64-v8a", "x86", "x86_64")

    private fun File.hasExcludedExternalBuildDirectory(): Boolean {
        if (isInExternalBuildCacheDirectory()) {
            return true
        }
        val path = normalizedPath
        return scope.excludedExternalBuildDirs.any { path.startsWith(it) }
    }

    private fun checkNativeLib(file: File): ChangedFile? {
        // simply check the extension and parent file
        val isNativeLib = file.extension == "so"
        if (!isNativeLib) {
            return null
        }
        val isAbiFolder = file.parentFile.name in abiFolders
        if (!isAbiFolder) {
            return null
        }

        val isInJuggDir = file.isChild(juggRootDir)
        if (isInJuggDir) {
            return null
        }

        getModules().forEach inner@{ module ->
            val moduleRootDir = module.moduleRootDir
            if (file.isChild(moduleRootDir)) {
                return ChangedFile(CompileFile.Type.NativeLib, file, file.parentFile.parentFile, ModuleInfo.virtualModule)
            }
        }

        val projectRootDir = getProjectRootDir()
        if (projectRootDir != null && file.isChild(projectRootDir)) {
            return ChangedFile(CompileFile.Type.NativeLib, file, file.parentFile.parentFile, ModuleInfo.virtualModule)
        }
        return null
    }

    private fun getProjectRootDir(): File? {
        return getModules().firstOrNull()?.projectRootDir
    }

    private fun getModules(): Collection<ModuleInfo> {
        val modules = scope.compiledModules
        if (modules.isEmpty()) {
            logger.warn("getModules compiledModules not set to FileChangesManager, this should not happened")
            return emptyList()
        }

        return modules
    }

    private val File.normalizedPath: Path
        get() = toPath().toAbsolutePath().normalize()

    private data class Scope(
        val allModules: List<ModuleInfo>,
        val compiledModules: List<ModuleInfo>,
        val buildDirs: List<File>,
        val scanRoots: List<Path>,
        val excludedExternalBuildDirs: List<Path>,
    )
}
