package com.sickworm.intellij.jugg.project

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.sickworm.intellij.jugg.compiler.CompileFile
import com.sickworm.intellij.jugg.compiler.ICompileContext
import com.sickworm.intellij.jugg.compiler.ModuleInfo
import com.sickworm.intellij.jugg.compiler.relativePath
import com.sickworm.intellij.jugg.gradle.compile.isChild
import com.sickworm.intellij.jugg.logger.JuggLogger
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

    private var compileContext: ICompileContext? = null
    private var buildFileList: List<File> = emptyList()

    override fun init(compileContext: ICompileContext) {
        this.compileContext = compileContext

        val sourceDirs = compileContext.modules.values.flatMap { it.sourceDirs }
        val resourceDirs = compileContext.modules.values.flatMap { it.resourceDirs }
        val assetDirs = compileContext.modules.values.flatMap { it.assetsDirs }
        logger.debug("""
            |File changes scope:
            |    source dirs:
            |        ${sourceDirs.relativePath(projectDir) }
            |    resource dirs:
            |        ${resourceDirs.relativePath(projectDir) }
            |    asset dirs:
            |        ${assetDirs.relativePath(projectDir) }
            |""".trimMargin())
    }

    override fun filter(file: List<File>): List<ChangedFile> {
        return file.mapNotNull(::toChangeFile)
    }

    override fun updateBuildFileList(relativePathList: List<String>) {
        logger.debug("updateBuildFileList: $relativePathList")
        buildFileList = relativePathList.map {
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

        checkBuildGradle(file)?.let {
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
                        logger.warn("file ${file.name} has invalid extension, ignore")
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

    private fun checkBuildGradle(file: File): ChangedFile? {
        buildFileList.forEach {
            if (file.absolutePath == it.absolutePath) {
                logger.info("Detect custom build file changed: $file")
                return ChangedFile(
                    CompileFile.Type.Gradle,
                    file,
                    juggRootDir,
                    ModuleInfo.virtualModule
                )
            }
        }

        val isGradleFile = file.name.endsWith(".gradle") || file.name.endsWith(".gradle.kts")
        if (!isGradleFile) {
            return null
        }

        getModules().forEach inner@{ module ->
            val moduleRootDir = module.moduleRootDir
            if (file.isChild(moduleRootDir)) {
                logger.info("Detect gradle file changed: $file")
                return ChangedFile(
                    CompileFile.Type.Gradle,
                    file,
                    moduleRootDir,
                    module
                )
            }
        }

        val projectRootDir = getProjectRootDir()
        if (projectRootDir != null && file.isChild(projectRootDir)) {
            logger.info("Detect gradle file changed: $file")
            return ChangedFile(
                CompileFile.Type.Gradle,
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
            logger.info("Detect properties file changed: $file")
            return ChangedFile(
                CompileFile.Type.Gradle,
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
                if (file.isChild(moduleBuildDir)) {
                    // AndroidManifest.xml in local/remote build dir is generated by gradle, ignore
                    return null
                }
                val moduleBuildDir2 = File(moduleRootDir, "build")
                if (file.isChild(moduleBuildDir2)) {
                    // AndroidManifest.xml in local build dir is generated by gradle, ignore
                    return null
                }

                logger.info("Detect AndroidManifest.xml changed: $file")
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
        if (compileContext == null) {
            logger.warn("getModules compileContext not set to FileChangesManager, this should not happened")
            return emptyList()
        }

        return compileContext?.modules?.values?: emptyList()
    }
}
