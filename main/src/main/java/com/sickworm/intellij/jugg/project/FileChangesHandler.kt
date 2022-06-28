package com.sickworm.intellij.jugg.project

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.sickworm.intellij.jugg.compiler.CompileFile
import com.sickworm.intellij.jugg.compiler.ICompileContext
import com.sickworm.intellij.jugg.compiler.relativePath
import com.sickworm.intellij.jugg.logger.JuggLogger
import java.io.File

/**
 * Manage file changes in project
 */
class FileChangesHandler(
    private val project: Project,
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

        if (compileContext == null) {
            logger.warn("compileContext not set to FileChangesManager, this should not happened")
            return null
        }

        val modules = compileContext?.modules?.values?: emptyList()
        modules.forEach { module ->
            val baseSourceDir = module.sourceDirs.find {
                file.path.startsWith(it.path)
            }
            if (baseSourceDir != null) {
                logger.info("source file changed: ${file.name}")
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
                logger.info("resource file changed: ${file.name}")
                return ChangedFile(CompileFile.Type.Resource, file, baseResourceDir, module)
            }

            val baseAssetDir = module.assetsDirs.find { file.path.startsWith(it.path) }
            if (baseAssetDir != null) {
                logger.info("asset file changed: ${file.name}")
                return ChangedFile(CompileFile.Type.Asset, file, baseAssetDir, module)
            }
        }

        return null
    }

    override fun checkBuildGradleChanged(files: List<File>): Boolean {
        var isBuildGradleChanged = false
        files.forEach {
            val isGradleFile = it.name.endsWith(".gradle") || it.name.endsWith(".gradle.kts")
            if (isGradleFile) {
                logger.info("detect gradle file changed: $it")
                isBuildGradleChanged = true
            }
        }
        return isBuildGradleChanged
    }
}
