package com.sickworm.intellij.jugg.compiler.overlay

import com.intellij.openapi.Disposable
import com.sickworm.intellij.jugg.compiler.Result
import com.sickworm.intellij.jugg.compiler.*
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

    override val supportedTypes = listOf(CompileFile.Type.Resource)

    override val isNeedPrintProgress: Boolean = true

    private val resourceCompiler = ResourceCompiler(context, this)

    private val arscCompiler = ArscCompiler(context, this)

    override fun doCompile(task: CompileTask): CompileResult {
        // compile to .flat
        val resourceTask = CompileTask(
            task.files,
            context.tempCompileDir,
            task,
        )
        val resourceResult = resourceCompiler.compile(resourceTask)
        if (!resourceResult.isAllSuccess || resourceResult.outputs.isEmpty()) {
            return CompileResult(
                task,
                resourceResult.details,
                resourceResult.outputs,
            )
        }

        // build .arsc
        val arscTask = CompileTask(
            resourceResult.outputs.map {
                CompileFile(CompileFile.Type.Flat, it.file, it.baseDir, context.tempModule)
            },
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
            resourceResult.details,
            finalOutputs
        )
    }

    private fun filterResources(resource: List<CompileOutput>, sourceFiles: List<CompileFile>): List<CompileOutput> {
        val resourceNameToPathMap = resource.groupBy { it.relativeFile.name }

        val finalOverlays = resource.toMutableList()
        resourceNameToPathMap.forEach rootLoop@{ (resourceName, outputs) ->
            if (outputs.size == 1) {
                return@rootLoop
            }
            outputs.forEach { output ->
                if (!output.relativeFile.path.startsWith(APK_RESOURCE_ROOT_DIR + File.separator)) {
                    return@forEach
                }
                val relativePath = output.relativeFile.path.substringAfter(File.separator)

                val sourceFile = sourceFiles.find {
                    val sourceResourceConfigPath = it.relativeFile.path
                    sourceResourceConfigPath == relativePath
                }
                val isCreateByAapt2 = sourceFile == null
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