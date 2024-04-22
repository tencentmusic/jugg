package com.sickworm.intellij.jugg.compiler.overlay

import com.intellij.openapi.Disposable
import com.sickworm.intellij.jugg.compiler.Result
import com.sickworm.intellij.jugg.compiler.*
import com.sickworm.intellij.jugg.compiler.manifest.AndroidManifestCompiler
import java.io.File

/**
 * Compile res file to deployable files.
 *
 * e.g.
 * input:
 * activity_main.xml
 *
 * output:
 * activity_main.xml (compiled)
 * resources.arsc
 * AndroidManifest.xml
 * R.java
 */
class ResourceOverlayCompiler(
    context: ICompileContext,
    parent: Disposable,
): BaseCompiler(context, parent) {

    override val supportedTypes = listOf(CompileFile.Type.Resource, CompileFile.Type.AndroidManifest)

    override val isNeedPrintProgress: Boolean = true

    private val resourceCompiler = ResourceCompiler(context, this)

    private val androidManifestCompiler = AndroidManifestCompiler(context, this)

    private val arscCompiler = ArscCompiler(context, this)

    override fun doCompile(task: CompileTask): CompileResult {
        val androidManifestTask = CompileTask(
            task.files.filter { it.type == CompileFile.Type.AndroidManifest },
            File(context.tempCompileDir, "merged_manifest"),
            task,
        )
        val resourceTask = CompileTask(
            task.files.filter { it.type == CompileFile.Type.Resource },
            File(context.tempCompileDir, "flat"),
            task,
        )

        // merge AndroidManifest.xml
        var androidManifestResult = CompileResult(androidManifestTask, emptyList(), emptyList())
        if (androidManifestTask.files.isNotEmpty()) {
            androidManifestResult = androidManifestCompiler.compile(androidManifestTask)
            if (!androidManifestResult.isAllSuccess || androidManifestResult.outputs.isEmpty()) {
                val resourceDetails: List<Result<CompileFile, CompileError>> = resourceTask.files.map {
                    Result.failure(CompileError(it, listOf(-1L to "Failed to compile AndroidManifest.xml")))
                }
                return CompileResult(
                    task,
                    androidManifestResult.details + resourceDetails,
                    androidManifestResult.outputs,
                )
            }
        }

        // compile to .flat
        var resourceResult = CompileResult(resourceTask, emptyList(), emptyList())
        if (resourceTask.files.isNotEmpty()) {
            resourceResult = resourceCompiler.compile(resourceTask)
            if (!resourceResult.isAllSuccess || resourceResult.outputs.isEmpty()) {
                return CompileResult(
                    task,
                    androidManifestResult.details + resourceResult.details,
                    androidManifestResult.outputs + resourceResult.outputs,
                )
            }
        }

        // build .flat .arsc and AndroidManifest.xml
        val compileFiles = resourceResult.outputs.map {
            CompileFile(CompileFile.Type.Flat, it.file, it.baseDir, context.tempModule)
        }.toMutableList()
        val manifestFile = androidManifestResult.outputs.firstOrNull()?.file
        if (manifestFile != null) {
            compileFiles.add(CompileFile(CompileFile.Type.AndroidManifest,
                manifestFile, manifestFile.parentFile, context.tempModule))
        }

        val arscTask = CompileTask(
            compileFiles,
            task.outputDir,
            task,
        )
        val arscResult = arscCompiler.compile(arscTask)
        if (!arscResult.isAllSuccess) {
            return CompileResult(
                task,
                task.files.map {
                    Result.failure(CompileError(it, listOf(0L to "aapt2 link failed")))
                },
                emptyList()
            )
        }

        val finalOutputs = filterResources(arscResult.outputs, task.files)

        return CompileResult(
            task,
            androidManifestResult.details + resourceResult.details,
            finalOutputs
        )
    }

    private fun filterResources(resource: List<CompileOutput>, sourceFiles: List<CompileFile>): List<CompileOutput> {
        val resourceNameToPathMap = resource.groupBy { it.relativeFile.name }

        val filePathSet: Set<String> = sourceFiles.flatMap { compileFile ->
            if (compileFile.file.isDirectory) {
                compileFile.file.listFilesRecursively().map { it.relativeTo(it.parentFile).path }
            } else {
                listOf(compileFile.relativeFile.path)
            }
        }.toSet()

        val finalOverlays = resource.toMutableList()
        resourceNameToPathMap.forEach rootLoop@{ (resourceName, outputs) ->
            if (resourceName == "Manifest.java") {
                outputs.forEach {
                    // ignore Manifest.java for I didn't see it in Android Studio too
                    if (it.type == CompileOutput.Type.Java) {
                        finalOverlays.remove(it)
                        return@rootLoop
                    }
                }
            }
            if (resourceName == "AndroidManifest.xml") {
                val output = outputs.first()
                if (output.relativeFile.path == "AndroidManifest.xml") {
                    val isNeedOutputManifest = sourceFiles.any { it.type == CompileFile.Type.AndroidManifest }
                    if (!isNeedOutputManifest) {
                        // don't output AndroidManifest.xml if no changes, output it will trigger APK repackage
                        finalOverlays.remove(output)
                        return@rootLoop
                    }
                }
            } else if (resourceName == "resources.arsc") {
                val output = outputs.first()
                if (output.relativeFile.path == "resources.arsc") {
                    val isOnlyManifest = sourceFiles.all { it.type == CompileFile.Type.AndroidManifest }
                    if (isOnlyManifest) {
                        // don't output resources.arsc if no changes
                        finalOverlays.remove(output)
                        return@rootLoop
                    }
                }
            }

            if (outputs.size == 1) {
                return@rootLoop
            }
            outputs.forEach { output ->
                if (!output.relativeFile.path.startsWith(APK_RESOURCE_ROOT_DIR + File.separator)) {
                    return@forEach
                }
                val relativePath = output.relativeFile.path.substringAfter(File.separator)

                val isCreateByAapt2 = !filePathSet.contains(relativePath)
                if (!isCreateByAapt2) {
                    return@forEach
                }

                // the resource is additional created by aapt2, try to find the origin xml file
                val guessSourceXmlFiles = sourceFiles.filter { compileFile ->
                    compileFile.file.name == resourceName
                }
                logger.debug("${output.relativeFile.path} is additional created by aapt2, " +
                        "guess sources: ${guessSourceXmlFiles.map { it.relativeFile.path }}")

                // check whether the resource has override xml file
                val overrideSourceXmlFiles = guessSourceXmlFiles.mapNotNull { compileFile ->
                    val guessOverrideSourceXmlFile = File(compileFile.baseDir, relativePath)
                    if (guessOverrideSourceXmlFile.exists()) {
                        return@mapNotNull guessOverrideSourceXmlFile
                    } else {
                        return@mapNotNull null
                    }
                }.toSet()
                if (overrideSourceXmlFiles.isEmpty()) {
                    logger.debug("${output.relativeFile.path} has no override xml file")
                } else {
                    logger.debug("${output.relativeFile.path} has override xml file: $overrideSourceXmlFiles, ignore creation")
                    finalOverlays.remove(output)
                }
            }
        }
        return finalOverlays
    }


    override fun doModuleCompile(task: CompileTask, module: ModuleInfo): CompileResult {
        // no need to implement
        return CompileResult(task, emptyList(), emptyList())
    }

    override fun warmUp() {
        arscCompiler.warmUp()
    }

    companion object {
        private const val APK_RESOURCE_ROOT_DIR = "res"
    }
}