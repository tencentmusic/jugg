package com.sickworm.intellij.aidp.compiler.overlay

import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.aidp.AidpInternalException
import com.sickworm.intellij.aidp.compiler.Result
import com.sickworm.intellij.aidp.compiler.*
import java.io.File
import java.util.zip.ZipFile

class ResourceOverlayCompiler(
    private val flatDir: File,
    stableIdsFile: File,
    manifest: File,
    androidJar: File,
    androidBuildTools: File,
    private val logger: Logger,
): ICompiler {

    override val supportedTypes = listOf(CompileFile.Type.Resource)

    private val resourceCompiler = ResourceCompiler(androidBuildTools, logger)

    private val arscCompiler = ArscCompiler(stableIdsFile, manifest, androidJar, androidBuildTools, logger)

    override fun compile(task: CompileTask): CompileResult {
        checkCanCompile(task)

        if (task.files.any { it.file.parent.endsWith("values") }) {
            throw AidpInternalException.resValuesNotSupported()
        }

        // compile to .flat
        val resourceTask = CompileTask(
            task.files,
            flatDir
        )
        val resourceResult = resourceCompiler.compile(resourceTask)
        if (!resourceResult.isAllSuccess) {
            return CompileResult(task, resourceResult.details, emptyList())
        }

        // build .arsc
        val arscTask = CompileTask(
            listOf(CompileFile(CompileFile.Type.FlatDir, flatDir, flatDir)),
            task.outputDir
        )
        val arscResult = arscCompiler.compile(arscTask)
        if (!resourceResult.isAllSuccess) {
            return CompileResult(
                task,
                task.files.map {
                    Result.failure(CompileError(it, listOf(0L to "aapt2 linked failed")))
                },
                emptyList()
            )
        }
        val rFileOutput = arscResult.outputs.find { it.type == CompileOutput.Type.Java }!!
        val resApkFileOutput = arscResult.outputs.find { it.type == CompileOutput.Type.Overlay }!!

        // copy overlays to outputDir
        val overlays = getOverlays(resApkFileOutput.file, task.outputDir)
        val overlayOutputs = overlays.map {
            CompileOutput(CompileOutput.Type.Overlay, it, task.outputDir)
        }
        resApkFileOutput.file.delete()

        return CompileResult(
            task,
            resourceResult.details,
            overlayOutputs + rFileOutput
        )
    }

    private fun getOverlays(
        apkFile: File,
        outputDir: File
    ): List<File> {
        try {
            ZipFile(apkFile).use { zipFile ->
                return zipFile.entries().toList().map { entry ->
                    val outputFile = File(outputDir, entry.name)
                    outputFile.parentFile!!.mkdirs()
                    zipFile.getInputStream(entry).use { ins ->
                        outputFile.outputStream().use { ous ->
                            ins.copyTo(ous)
                        }
                    }
                    outputFile
                }
            }
        } catch (e: Exception) {
            logger.warn("getOverlays failed", e)
            return emptyList()
        }
    }
}