package com.sickworm.intellij.jugg.project

import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.jugg.compiler.CompileFile
import com.sickworm.intellij.jugg.compiler.ICompileContext
import com.sickworm.intellij.jugg.project.data.ModuleInfo
import com.sickworm.intellij.jugg.compiler.relativePath
import com.sickworm.intellij.jugg.git.IFileMatcher
import com.sickworm.intellij.jugg.gradle.compile.isChild
import com.sickworm.intellij.jugg.platform.PlatformApi
import java.io.File

/**
 * Manage file changes in project
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
        """.trimIndent().split("\n").toList()
    }

    private var buildFileMatcher: IFileMatcher = PlatformApi.createFileMatcher().also {
        it.init(projectDir, defaultMatchRule)
    }


    private var allModules = emptyList<ModuleInfo>()
    private var compiledModules = emptyList<ModuleInfo>()

    override fun init(compileContext: ICompileContext) {
        logger.debug("init FileChangesHandler")
        val notCompiledModuleNames = findNotCompiledWithApplicationModules(compileContext)
        allModules = compileContext.modules.values.toList()
        compiledModules = compileContext.modules.values.filter { !notCompiledModuleNames.contains(it.name) }
        val sourceDirs = compiledModules.flatMap { it.sourceDirs }
        val resourceDirs = compiledModules.flatMap { it.resourceDirs }
        val assetDirs = compiledModules.flatMap { it.assetsDirs }
        logger.debug("""
            |File changes scope:
            |    source dirs:
            |        ${sourceDirs.relativePath(projectDir) }
            |    resource dirs:
            |        ${resourceDirs.relativePath(projectDir) }
            |    asset dirs:
            |        ${assetDirs.relativePath(projectDir) }
            |    ignore modules(won't compile):
            |        $notCompiledModuleNames
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
        return file.mapNotNull(::toChangeFile)
    }

    override fun updateBuildFileRules(rules: List<String>) {
        logger.debug("updateBuildFileRules: $rules")
        val newRules = defaultMatchRule + rules
        buildFileMatcher.init(projectDir, newRules)
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

        return null
    }

    private fun checkSource(file: File): ChangedFile? {
        getModules().forEach { module ->
            val baseSourceDir = module.sourceDirs.find {
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
