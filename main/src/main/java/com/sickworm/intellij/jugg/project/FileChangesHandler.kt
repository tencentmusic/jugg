package com.sickworm.intellij.jugg.project

import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.jugg.compiler.CompileFile
import com.sickworm.intellij.jugg.compiler.ICompileContext
import com.sickworm.intellij.jugg.project.data.ModuleInfo
import com.sickworm.intellij.jugg.compiler.relativePathForPrintSafe
import com.sickworm.intellij.jugg.git.FileMatcher
import com.sickworm.intellij.jugg.git.IFileMatcher
import com.sickworm.intellij.jugg.gradle.compile.isChild
import com.sickworm.intellij.jugg.ide.bean.JuggSettings
import java.io.File

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


    private var allModules = emptyList<ModuleInfo>()
    private var compiledModules = emptyList<ModuleInfo>()

    @Suppress("ConvertArgumentToSet")
    override fun init(compileContext: ICompileContext) {
        logger.debug("init FileChangesHandler")
        allModules = compileContext.modules.values.toList()

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

        compiledModules = allModules - ignoreModules
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

        if (allModules.isNotEmpty()) {
            appendCompiledModules()
        }
    }

    private fun appendCompiledModules() {
        doNotIgnoreModulePaths.forEach { doNotIgnoreModulePath ->
            val isNotInCompiledModules = compiledModules.all {
                it.moduleStdPath != doNotIgnoreModulePath
            }
            if (isNotInCompiledModules) {
                val relativeModule = allModules.find {
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
    }

    private fun toChangeFile(file: File): ChangedFile? {
        // file not exists
        if (!file.exists()) {
            return null
        }
        // is directory
        if (file.isDirectory) {
            return null
        }

        checkBuildFiles(file)?.let {
            return it
        }
        checkAndroidManifest(file)?.let {
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

        if (file.isInBuildDir) {
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

        if (file.isInBuildDir) {
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
        val file = this
        allModules.forEach inner@{ module ->
            val moduleRootDir = module.moduleRootDir
            if (file.isChild(moduleRootDir)) {
                val moduleBuildDir = module.buildPathInfo.buildDir
                val localModuleBuildDir = File(module.moduleRootDir, "build")
                if (file.isChild(moduleBuildDir) || file.isChild(localModuleBuildDir)) {
                    // file in build dir is generated by gradle, ignore
                    return true
                }
            }
        }

        return false
    }

    private val abiFolders = listOf("armeabi", "armeabi-v7a", "arm64-v8a", "x86", "x86_64")

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

        if (file.isInBuildDir) {
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
        if (compiledModules.isEmpty()) {
            logger.warn("getModules compiledModules not set to FileChangesManager, this should not happened")
            return emptyList()
        }

        return compiledModules
    }
}
