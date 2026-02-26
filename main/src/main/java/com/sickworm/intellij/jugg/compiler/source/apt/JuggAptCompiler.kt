package com.sickworm.intellij.jugg.compiler.source.apt

import com.intellij.openapi.Disposable
import com.sickworm.intellij.jugg.compiler.BaseCompiler
import com.sickworm.intellij.jugg.compiler.CompileFile
import com.sickworm.intellij.jugg.compiler.CompileResult
import com.sickworm.intellij.jugg.compiler.CompileTask
import com.sickworm.intellij.jugg.compiler.ICompileContext
import com.sickworm.intellij.jugg.compiler.toCompileOutput
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
    private val processors: List<IJuggAptProcessor> = ProcessorRegistration.get(),
) : BaseCompiler(context, parent) {

    override val supportedTypes: List<CompileFile.Type> = listOf(
        CompileFile.Type.Java,
        CompileFile.Type.Kotlin,
        CompileFile.Type.Class,
    )

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

        val rewrittenByPath = LinkedHashMap<String, CompileFile>()

        processors.forEach { processor ->
            try {
                val rewrittenFiles = processor.process(
                    context = context,
                    module = module,
                    allCompileFiles = task.files,
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
                    rewrittenByPath[rewritten.file.absolutePath] = rewritten
                }
            } catch (throwable: Throwable) {
                logger.warn("Jugg apt processor ${processor.id} failed: ${throwable.message}")
            }
        }

        val outputs = rewrittenByPath.values.mapNotNull { it.toCompileOutput() }
        return CompileResult(task, emptyList(), outputs)
    }

    private fun backupGeneratedSourceDir(sourceDir: File): File {
        return try {
            context.backupGradleDir(sourceDir, overrideOnExists = false)
        } catch (throwable: Throwable) {
            logger.warn(
                "Backup generated source dir failed for $sourceDir, fallback to original dir: ${throwable.message}",
                throwable,
            )
            sourceDir
        }
    }
}
