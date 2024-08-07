package com.sickworm.intellij.jugg.project

import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.jugg.compiler.CompileFile
import com.sickworm.intellij.jugg.compiler.ICompileContext
import com.sickworm.intellij.jugg.project.data.ModuleInfo
import com.sickworm.intellij.jugg.compiler.relativePath
import com.sickworm.intellij.jugg.gradle.compile.isChild
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

    /*** custom build files given by Jugg backend distinct by projects. */
    private var customBuildFileList: List<File> = emptyList()
    private var compiledModules = emptyList<ModuleInfo>()

    override fun init(compileContext: ICompileContext) {
        logger.debug("init FileChangesHandler")
        val notCompiledModuleNames = findNotCompiledWithApplicationModules(compileContext)
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
        return file.mapNotNull(::toChangeFile)
    }

    override fun updateBuildFileList(relativePathList: List<String>) {
        logger.debug("updateBuildFileList: $relativePathList")
        customBuildFileList = relativePathList.map {
            File(projectDir, it)
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

        checkBuildGradleAndLibraryFiles(file)?.let {
            return it
        }
        checkBuildProperties(file)?.let {
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

    private fun checkBuildGradleAndLibraryFiles(file: File): ChangedFile? {
        val isInJuggDir = file.isChild(juggRootDir)
        if (isInJuggDir) {
            return null
        }

        customBuildFileList.forEach {
            if (file.absolutePath == it.absolutePath) {
                return ChangedFile(
                    CompileFile.Type.BuildFile,
                    file,
                    juggRootDir,
                    ModuleInfo.virtualModule
                )
            }
        }

        val isGradleFile = file.name.endsWith(".gradle") || file.name.endsWith(".gradle.kts")
        val isLibraryFile = file.name.endsWith(".jar") || file.name.endsWith(".aar")
        if (!isGradleFile && !isLibraryFile) {
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

    private fun checkBuildProperties(file: File): ChangedFile? {
        val isPropertiesFile = (file.name == "local.properties") || (file.name == "gradle.properties")
        if (!isPropertiesFile) {
            return null
        }

        val projectRootDir = getProjectRootDir()
        if (projectRootDir != null && file.parentFile.absolutePath == projectRootDir.absolutePath) {
            return ChangedFile(
                CompileFile.Type.BuildFile,
                file,
                projectRootDir,
                ModuleInfo.virtualModule,
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
                val moduleBuildDir = module.buildPathInfo.buildDir
                val localModuleBuildDir = File(module.moduleRootDir, "build")
                if (file.isChild(moduleBuildDir) || file.isChild(localModuleBuildDir)) {
                    // AndroidManifest.xml in build dir is generated by gradle, ignore
                    return null
                }

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
