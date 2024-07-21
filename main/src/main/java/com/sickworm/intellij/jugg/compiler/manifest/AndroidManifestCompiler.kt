package com.sickworm.intellij.jugg.compiler.manifest

import com.intellij.openapi.Disposable
import com.sickworm.intellij.jugg.compiler.Result
import com.sickworm.intellij.jugg.compiler.*
import com.sickworm.intellij.jugg.project.data.ModuleInfo
import com.sickworm.intellij.jugg.gradle.compile.crc32
import java.io.File

/**
 * Compile asset file to deployable files.
 * For now just copy the file to output directory.
 *
 * input: AndroidManifest.xml file (xml format)
 * output: merged AndroidManifest.xml file (xml format)
 */
class AndroidManifestCompiler(
    context: ICompileContext,
    parent: Disposable,
): BaseCompiler(context, parent) {

    override val supportedTypes = listOf(CompileFile.Type.AndroidManifest)

    override fun doCompile(task: CompileTask): CompileResult {
        val applicationModule = context.applicationModule
            ?: return createErrorCompileResult(task, "application module not found")
        val finalMergedManifest = applicationModule.buildPathInfo.mergedManifest
        if (!finalMergedManifest.exists()) {
            val reason = "APK merged manifest(${finalMergedManifest}) not exists, fallback to gradle once may fix this"
            return createErrorCompileResult(task, reason)
        }

        val outputManifestFile = File(task.outputDir, "AndroidManifest.xml")
        outputManifestFile.mkdirs()
        outputManifestFile.delete()

        try {
            val changedManifestFileList = task.files.mapNotNull {
                val module = it.module

                var manifestPlaceHolders = module.manifestPlaceHolders
                val isApplicationManifest = module.moduleRootDir == context.applicationModule?.moduleRootDir
                if (isApplicationManifest) {
                    val packageName = context.packageName
                    if (packageName == null) {
                        logger.warn("applicationId not found, failed to compile AndroidManifest.xml.")
                        return createErrorCompileResult(task, "applicationId not found")
                    }
                    // applicationId is embedded placeholder for application module
                    manifestPlaceHolders = (manifestPlaceHolders ?: emptyMap()) + mapOf("applicationId" to packageName)
                }

                if (module.moduleRootDir.path == context.tempModule.moduleRootDir.path) {
                    // AndroidManifest in libraries
                    val relativeManifestFile = it.oldManifest
                    if (relativeManifestFile != null) {
                        if (it.file.crc32 == relativeManifestFile.crc32) {
                            logger.debug("library AndroidManifest.xml in not changed, skip." +
                                    "(${it.file.absolutePath} == ${relativeManifestFile.absolutePath}")
                            return@mapNotNull null
                        }
                    }

                    return@mapNotNull ChangedManifestFile(it.file, relativeManifestFile, manifestPlaceHolders)
                } else {
                    // AndroidManifest in gradle module
                    val relativeManifestFile = findMergedManifestFile(module)
                    if (relativeManifestFile == null) {
                        logger.warn("AndroidManifest.xml compile failed, Merged manifest file in ${module.name} not found.")
                        logger.warn("Fallback to gradle once may fix this.")
                        return createErrorCompileResult(task, "Merged AndroidManifest.xml not found in module ${module.name}")
                    }
                    return@mapNotNull ChangedManifestFile(it.file, relativeManifestFile, manifestPlaceHolders)
                }

            }

            if (changedManifestFileList.isEmpty()) {
                logger.debug("All AndroidManifest.xml in libraries are not changed, skip merge.")
                return CompileResult(task, task.files.map { Result.success(it) }, emptyList())
            }

            val isNeedUpdate = AndroidManifestMerger(logger).merge(finalMergedManifest, changedManifestFileList, outputManifestFile)
            if (!isNeedUpdate) {
                logger.debug("All AndroidManifest.xml in libraries are not changed after diff, skip merge.")
                return CompileResult(task, task.files.map { Result.success(it) }, emptyList())
            }
        } catch (e: Throwable) {
            logger.debug("Compile AndroidManifest.xml failed", e)
            val reason = "Compile AndroidManifest.xml failed, got exception: $e"
            logger.warn(reason)
            return createErrorCompileResult(task, reason)
        }

        if (!outputManifestFile.exists()) {
            val reason = "Compile AndroidManifest.xml failed, file generate failed"
            return createErrorCompileResult(task, reason)
        }

        val compileOutput = CompileOutput(
            CompileOutput.Type.Res,
            outputManifestFile,
            outputManifestFile.parentFile,
        )
        return CompileResult(task, task.files.map { Result.success(it) }, listOf(compileOutput))
    }

    private fun createErrorCompileResult(task: CompileTask, reason: String): CompileResult {
        logger.warn("Compile AndroidManifest.xml failed. $reason.")
        val details: List<Result<CompileFile, CompileError>> = task.files.map {
            Result.failure(CompileError(it, listOf(-1L to reason)))
        }
        return CompileResult(task, details, emptyList())
    }

    override fun doModuleCompile(task: CompileTask, module: ModuleInfo): CompileResult {
        // no need to implement
        return CompileResult(task, emptyList(), emptyList())
    }

    private fun findMergedManifestFile(module: ModuleInfo): File? {
        val manifestFile = module.buildPathInfo.mergedManifest
        if (manifestFile.exists()) {
            return manifestFile
        }
        return null
    }
}