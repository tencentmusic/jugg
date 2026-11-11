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

/** Stops the source round when a fail-closed annotation generator cannot produce valid sources. */
class JuggAptFatalException(message: String, cause: Throwable) : RuntimeException(message, cause)

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
     * Each processor decides whether generation and generated-source errors can degrade.
     */
    override fun doModuleCompile(task: CompileTask, module: ModuleInfo): CompileResult {
        if (task.files.none { it.type == CompileFile.Type.Java || it.type == CompileFile.Type.Kotlin }) {
            return CompileResult(task, emptyList(), emptyList())
        }
        val activeProcessors = processors + context.customCompilers.filterIsInstance<IJuggAptProcessor>()
        if (activeProcessors.isEmpty()) {
            return CompileResult(task, emptyList(), emptyList())
        }

        val roundStart = System.nanoTime()
        val processorTimes = linkedMapOf<String, Double>()
        val rewrittenByPath = LinkedHashMap<String, CompileFile>()

        val analysis = AptSourceAnalysis(task.files)
        try {
            activeProcessors.forEach { processor ->
                val processorStart = System.nanoTime()
                try {
                    val rewrittenFiles = processor.process(context, module, task.files, analysis)
                    for (rewritten in rewrittenFiles) {
                        if (rewritten.type != CompileFile.Type.Java && rewritten.type != CompileFile.Type.Kotlin) {
                            if (processor.failClosed) error("Processor ${processor.id} returned unsupported file type: ${rewritten.type}")
                            logger.warn("Processor ${processor.id} returned unsupported file type: ${rewritten.type}, file=${rewritten.file}")
                            continue
                        }
                        if (!rewritten.file.exists()) {
                            if (processor.failClosed) error("Processor ${processor.id} returned missing file: ${rewritten.file}")
                            logger.warn("Processor ${processor.id} returned missing file: ${rewritten.file}")
                            continue
                        }
                        rewrittenByPath[rewritten.file.absolutePath] = rewritten
                        analysis.invalidate(rewritten.file)
                    }
                } catch (throwable: Throwable) {
                    if (processor.failClosed) {
                        throw JuggAptFatalException("Jugg apt processor ${processor.id} failed", throwable)
                    }
                    logger.warn("Jugg apt processor ${processor.id} failed: ${throwable.message}")
                } finally {
                    val key = "${processor.id}:${processor.javaClass.simpleName}"
                    processorTimes[key] = (processorTimes[key] ?: 0.0) + (System.nanoTime() - processorStart) / 1_000_000.0
                }
            }
        } finally {
            logger.debug("APT summary: module=${module.name}, inputs=${task.files.size}, " +
                    "languages=${task.files.groupingBy { it.type }.eachCount()}, totalMs=${(System.nanoTime() - roundStart) / 1_000_000.0}, " +
                    "processorMs=$processorTimes, ${analysis.timingSummary()}")
        }

        val outputs = rewrittenByPath.values.mapNotNull { it.toCompileOutput() }
        return CompileResult(task, emptyList(), outputs)
    }

    /** Uses the generating instances to preserve fail-closed ownership through language compilation. */
    fun isFailClosedGeneratedFile(file: CompileFile): Boolean {
        val activeProcessors = processors + context.customCompilers.filterIsInstance<IJuggAptProcessor>()
        return activeProcessors.any { it.failClosed && it.ownsGeneratedFile(file) }
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
