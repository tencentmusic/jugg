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
    private val project: Project,
    private val juggRootDir: File,
    private val logger: Logger = JuggLogger.getInstance(project, "FileChangesManager"),
) :
    IFileChangesHandler
{

    private var compileContext: ICompileContext? = null

    override fun init(compileContext: ICompileContext) {
        this.compileContext = compileContext

        val sourceDirs = compileContext.modules.values.flatMap { it.sourceDirs }
        val resourceDirs = compileContext.modules.values.flatMap { it.resourceDirs }
        val assetDirs = compileContext.modules.values.flatMap { it.assetsDirs }
        val projectDir = project.basePath?: ""
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

    private fun toChangeFile(file: File): ChangedFile? {
        // file not exists
        if (!file.exists()) {
            return null
        }
        // is directory
        if (file.isDirectory) {
            return null
        }

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

    override fun checkBuildFileChanged(files: List<File>): Pair<Boolean, String> {
        if (checkBuildGradleChanged(files)) {
            return true to "build.gradle"
        }

        checkBuildPropertiesChanged(files)
            .takeIf { it.first }
            ?.let { return it }

        if (checkAndroidManifestChanged(files)) {
            return true to "AndroidManifest.xml"
        }

        return false to ""
    }

    private fun checkBuildGradleChanged(files: List<File>): Boolean {
        var isBuildGradleChanged = false
        files.forEach { file ->
            val isGradleFile = file.name.endsWith(".gradle") || file.name.endsWith(".gradle.kts")
            if (!isGradleFile) {
                return@forEach
            }

            val projectRootDir = getProjectRootDir()
            if (projectRootDir != null && file.isChild(projectRootDir)) {
                logger.info("Detect gradle file changed: $file")
                isBuildGradleChanged = true
                return@forEach
            }
            getModules().forEach inner@{ module ->
                val moduleRootDir = module.moduleRootDir
                if (file.isChild(moduleRootDir)) {
                    logger.info("Detect gradle file changed: $file")
                    isBuildGradleChanged = true
                    return@forEach
                }
            }
        }
        return isBuildGradleChanged
    }

    private fun checkBuildPropertiesChanged(files: List<File>): Pair<Boolean, String> {
        var changedFileName = ""
        files.forEach { file ->
            val isPropertiesFile = (file.name == "local.properties") || (file.name == "gradle.properties")
            if (!isPropertiesFile) {
                return@forEach
            }

            val projectRootDir = getProjectRootDir()
            if (projectRootDir != null && file.parentFile.absolutePath == projectRootDir.absolutePath) {
                logger.info("Detect properties file changed: $file")
                changedFileName = file.name
                return@forEach
            }
        }
        return changedFileName.isNotEmpty() to changedFileName
    }


    private fun checkAndroidManifestChanged(files: List<File>): Boolean {
        var isAndroidManifestChanged = false
        files.forEach { file ->
            val isAndroidManifest = file.name == "AndroidManifest.xml"
            if (!isAndroidManifest) {
                return@forEach
            }
            val isInJuggDir = file.isChild(juggRootDir)
            if (isInJuggDir) {
                return@forEach
            }

            getModules().forEach inner@{ module ->
                val moduleBuildDir = module.buildPathInfo.buildDir
                if (file.isChild(moduleBuildDir)) {
                    // AndroidManifest.xml in build dir is generated by gradle, ignore
                    return@forEach
                }
            }

            val projectRootDir = getProjectRootDir()
            if (projectRootDir != null && file.isChild(projectRootDir)) {
                logger.info("Detect AndroidManifest.xml changed: $file")
                isAndroidManifestChanged = true
                return@forEach
            }
            getModules().forEach inner@{ module ->
                val moduleRootDir = module.moduleRootDir
                if (file.isChild(moduleRootDir)) {
                    logger.info("Detect AndroidManifest.xml changed: $file")
                    isAndroidManifestChanged = true
                    return@forEach
                }
            }
        }
        return isAndroidManifestChanged
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
