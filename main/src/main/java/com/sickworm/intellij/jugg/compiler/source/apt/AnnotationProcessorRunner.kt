package com.sickworm.intellij.jugg.compiler.source.apt

import com.sickworm.intellij.jugg.compiler.*
import com.sickworm.intellij.jugg.compiler.source.JavaCompilerInvoker
import com.sickworm.intellij.jugg.compiler.source.kotlin.KotlinCompilerInvoker
import com.sickworm.intellij.jugg.project.data.ModuleInfo
import java.io.File

/** Uses native KAPT for mixed-language aggregation and javac for Java-only modules. */
class AnnotationProcessorRunner {
    fun generate(context: ICompileContext, module: ModuleInfo, sources: List<File>,
        processorPath: List<File>, processorNames: List<String>, options: Map<String, String>, work: File, isolateKapt: Boolean = false): File {
        work.deleteRecursively()
        work.mkdirs()
        val input = sources.distinctBy { it.canonicalPath }
        if (input.any { it.extension == "kt" }) {
            return generateWithKapt(context, module, input, processorPath, processorNames, options, work, isolateKapt)
        }
        val generated = File(work, "sources").apply { mkdirs() }
        if (input.isEmpty()) return generated
        val files = input.map { CompileFile(CompileFile.Type.Java, it, it.parentFile, module) }
        val task = CompileTask(files, File(work, "classes").apply { mkdirs() }, CompileStatusHolder.DEFAULT)
        val dependencies = context.getModuleDependencies(module, task) + File(work, "kotlin_classes").apply { mkdirs() }.path + processorPath.map { it.path }
        val result = JavaCompilerInvoker().compile(context, module, task, context.logger, JavaCompilerInvoker.Options(
            isEnableApt = true, isAptOnly = true, isCanAutoRetry = false,
            aptPaths = dependencies.map(::File), aptOptions = options, dependencies = dependencies,
            processorNames = processorNames, generatedSourceDir = generated,
        ))
        check(result.isAllSuccess) { "Annotation generation failed for ${module.name}; see compiler diagnostics above" }
        return generated
    }

    /** Keeps Kotlin stub semantics inside KAPT, including imports that have no Java class. */
    private fun generateWithKapt(
        context: ICompileContext,
        module: ModuleInfo,
        sources: List<File>,
        processorPath: List<File>,
        processorNames: List<String>,
        options: Map<String, String>,
        work: File,
        isolateKapt: Boolean,
    ): File {
        val kaptContext = context.subContext("arouter_kapt")
        val files = sources.map { source ->
            val root = module.sourceDirs.filter { source.toPath().startsWith(it.toPath()) }
                .maxByOrNull { it.path.length } ?: module.moduleRootDir
            val type = if (source.extension == "kt") CompileFile.Type.Kotlin else CompileFile.Type.Java
            CompileFile(type, source, root, module)
        }
        val task = CompileTask(files, File(work, "kotlin_classes"), CompileStatusHolder.DEFAULT)
        val result = KotlinCompilerInvoker().compile(
            kaptContext, module, task, context.logger,
            KotlinCompilerInvoker.Options(
                isEnableKapt = true,
                executionMode = if (isolateKapt) KotlinCompilerInvoker.ExecutionMode.ISOLATED_PROCESS
                    else KotlinCompilerInvoker.ExecutionMode.IN_PROCESS,
                kaptMode = "stubsAndApt",
                kaptDependencies = processorPath,
                kaptProcessors = processorNames,
                kaptOptions = options,
                // Exact Java inputs are already in the task; exclude previous generated source roots.
                javaSourceDirs = emptyList(),
                isNeedCompileCompose = module.isUseCompose == true,
                kotlinPlugins = module.kotlinPlugins.orEmpty(),
                kotlinExtensions = module.kotlinExtensions.orEmpty(),
                kotlinPluginOptions = module.kotlinPluginOptions,
            ),
        )
        check(result.isAllSuccess) { "KAPT annotation generation failed for ${module.name}; see compiler diagnostics above" }
        return File(kaptContext.tempCompileDir, "kapt/sources")
    }

}
