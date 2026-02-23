package com.sickworm.intellij.jugg.compiler.source.apt

import com.intellij.openapi.Disposable
import com.sickworm.intellij.jugg.compiler.BaseCompiler
import com.sickworm.intellij.jugg.compiler.CompileFile
import com.sickworm.intellij.jugg.compiler.CompileResult
import com.sickworm.intellij.jugg.compiler.CompileTask
import com.sickworm.intellij.jugg.compiler.ICompileContext
import com.sickworm.intellij.jugg.compiler.listFilesRecursively
import com.sickworm.intellij.jugg.compiler.toCompileOutput
import com.sickworm.intellij.jugg.compiler.source.apt.processors.KuiklyPageJuggAptProcessor
import com.sickworm.intellij.jugg.project.data.ModuleInfo
import java.io.File
import java.util.LinkedHashMap

/**
 * JuggAptCompiler rewrites generated Java/Kotlin sources before language compilers run.
 *
 * It follows BaseCompiler lifecycle to keep module-splitting behavior consistent with other compilers,
 * then delegates actual rewrite rules to registered [IJuggAptProcessor] instances.
 */
class JuggAptCompiler(
    context: ICompileContext,
    parent: Disposable,
    processors: List<IJuggAptProcessor> = listOf(KuiklyPageJuggAptProcessor()),
) : BaseCompiler(context, parent) {

    override val supportedTypes: List<CompileFile.Type> = listOf(
        CompileFile.Type.Java,
        CompileFile.Type.Kotlin,
        CompileFile.Type.Class,
    )

    private val processors: List<IJuggAptProcessor> = processors

    /**
     * Executes all custom processors in deterministic order.
     *
     * Processor errors are fail-open: warn and continue, so verified source compile flow is not blocked.
     */
    override fun doModuleCompile(task: CompileTask, module: ModuleInfo): CompileResult {
        if (task.files.none { it.type == CompileFile.Type.Java || it.type == CompileFile.Type.Kotlin }) {
            return CompileResult(task, emptyList(), emptyList())
        }
        if (processors.isEmpty()) {
            return CompileResult(task, emptyList(), emptyList())
        }

        val generatedAptFiles = discoverGeneratedAptFiles(module, task.files)
        if (generatedAptFiles.isEmpty()) {
            logger.debug("No generated apt files found for module ${module.name}, skip.")
            return CompileResult(task, emptyList(), emptyList())
        }

        val generatedByPath = LinkedHashMap<String, CompileFile>()
        generatedAptFiles.forEach { generatedByPath[it.file.absolutePath] = it }
        val rewrittenByPath = LinkedHashMap<String, CompileFile>()

        processors.forEach { processor ->
            try {
                val rewrittenFiles = processor.process(
                    context = context,
                    module = module,
                    allCompileFiles = task.files,
                    generatedAptFiles = generatedByPath.values.toList(),
                )
                for (rewritten in rewrittenFiles) {
                    if (rewritten.type != CompileFile.Type.Java && rewritten.type != CompileFile.Type.Kotlin) {
                        logger.warn("Processor ${processor.id} returned unsupported file type: ${rewritten.type}, file=${rewritten.file}")
                        continue
                    }
                    if (!rewritten.file.exists()) {
                        logger.warn("Processor ${processor.id} returned missing file: ${rewritten.file}")
                        continue
                    }
                    generatedByPath[rewritten.file.absolutePath] = rewritten
                    rewrittenByPath[rewritten.file.absolutePath] = rewritten
                }
            } catch (throwable: Throwable) {
                logger.warn("Jugg apt processor ${processor.id} failed: ${throwable.message}")
            }
        }

        val outputs = rewrittenByPath.values.mapNotNull { it.toCompileOutput() }
        return CompileResult(task, emptyList(), outputs)
    }

    private fun discoverGeneratedAptFiles(module: ModuleInfo, taskFiles: List<CompileFile>): List<CompileFile> {
        val generatedByPath = LinkedHashMap<String, CompileFile>()

        // Existing generated files that already entered this compile round.
        taskFiles.filter { file ->
            (file.type == CompileFile.Type.Java || file.type == CompileFile.Type.Kotlin) &&
                    file.file.path.replace("\\", "/").contains("/generated/")
        }.forEach { generatedByPath[it.file.absolutePath] = it }

        val scanRoots = linkedSetOf<File>()
        scanRoots.add(module.buildPathInfo.generatedSourcePath)
        scanRoots.add(context.tempCompileDir.resolve("generated"))
        scanRoots.add(context.tempCompileDir.resolve("ksp"))
        scanRoots.add(context.tempCompileDir.resolve("kapt"))

        for (root in scanRoots) {
            if (!root.exists()) {
                continue
            }
            for (file in root.listFilesRecursively()) {
                if (!file.isFile) {
                    continue
                }
                val type = when (file.extension.lowercase()) {
                    "kt" -> CompileFile.Type.Kotlin
                    "java" -> CompileFile.Type.Java
                    else -> null
                } ?: continue
                generatedByPath[file.absolutePath] = CompileFile(
                    type = type,
                    file = file,
                    baseDir = root,
                    module = module,
                )
            }
        }

        return generatedByPath.values.toList()
    }
}
