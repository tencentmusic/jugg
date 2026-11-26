package com.sickworm.intellij.jugg.compiler.source

import com.intellij.openapi.Disposable
import com.sickworm.intellij.jugg.compiler.*
import com.sickworm.intellij.jugg.project.info.ModuleInfo

/**
 * JavaCompiler compiles Java sources for a module through JavaCompilerInvoker and participates in source-stage hook ordering.
 */
class JavaCompiler(
    context: ICompileContext,
    parent: Disposable,
): BaseCompiler(context, parent) {

    override val supportedTypes = listOf(CompileFile.Type.Java)

    override val isNeedOutputDirEmpty = true

    override val isNeedPrintProgress: Boolean = true

    override val beforeCompileOrderRange: IntRange = CompileOrder.beforeSource
    override val afterCompileOrderRange: IntRange = CompileOrder.afterSource

    private val invoker = JavaCompilerInvoker()

    override fun doModuleCompile(task: CompileTask, module: ModuleInfo): CompileResult {
        val options = JavaCompilerInvoker.Options(
            isEnableApt = false,
            aptPaths = (module.annotationProcessorDependencies + module.kaptDependencies).map { it.file },
            aptOptions = module.javaAnnotationProcessorOptions ?: emptyMap(),
            aptSourcePaths = module.sourceDirs,
        )
        return invoker.compile(context, module, task, logger, options)
    }
}
