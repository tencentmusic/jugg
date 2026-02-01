package com.sickworm.intellij.jugg.compiler.source

import com.intellij.openapi.Disposable
import com.sickworm.intellij.jugg.compiler.*
import com.sickworm.intellij.jugg.project.data.ModuleInfo

class JavaCompiler(
    context: ICompileContext,
    parent: Disposable,
): BaseCompiler(context, parent) {

    override val supportedTypes = listOf(CompileFile.Type.Java)

    override val isNeedOutputDirEmpty = true

    override val isNeedPrintProgress: Boolean = true

    override val beforeCompileOrderRange: IntRange = CompileOrder.beforeSource
    override val afterCompileOrderRange: IntRange = CompileOrder.afterSource

    override fun doModuleCompile(task: CompileTask, module: ModuleInfo): CompileResult {
        val options = JavaCompilerInvoker.Options(
            isEnableApt = false,
            aptPaths = (module.annotationProcessorDependencies + module.kaptDependencies).map { it.file },
            aptOptions = module.javaAnnotationProcessorOptions ?: emptyMap(),
            aptSourcePaths = module.sourceDirs,
        )
        return JavaCompilerInvoker.currentInstance.compile(context, module, task, logger, options)
    }
}
