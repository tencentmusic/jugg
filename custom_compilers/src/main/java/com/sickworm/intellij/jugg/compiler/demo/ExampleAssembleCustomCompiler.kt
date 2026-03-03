@file:Suppress("unused")

package com.sickworm.intellij.jugg.compiler.demo

import com.google.auto.service.AutoService
import com.intellij.openapi.Disposable
import com.sickworm.intellij.jugg.compiler.*
import com.sickworm.intellij.jugg.compiler.custom.ICompilerCreator
import com.sickworm.intellij.jugg.gradle.compile.CmdExecutor
import com.sickworm.intellij.jugg.gradle.compile.SimpleSshCommand
import com.sickworm.intellij.jugg.project.data.ModuleInfo

class ExampleAssembleCustomCompiler(context: ICompileContext, parent: Disposable) : BaseCompiler(context, parent) {

    @AutoService(ICompilerCreator::class)
    class Creator : ICompilerCreator {
        override fun create(context: ICompileContext, parent: Disposable): ICompiler {
            return ExampleAssembleCustomCompiler(context, parent)
        }
    }

    override fun doModuleCompile(task: CompileTask, module: ModuleInfo): CompileResult {
        if (context.projectDir.name != "android_demo_project") {
            return CompileResult(task, emptyList(), emptyList())
        }

        val sourceFiles = task.files.filter { it.type == CompileFile.Type.Java || it.type == CompileFile.Type.Kotlin }
        if (sourceFiles.isEmpty()) {
            return CompileResult(task, emptyList(), emptyList())
        }

        logger.info("Detect source file change, start generateDebugSources...")
        val cmd = SimpleSshCommand("cd ${context.projectDir} && ./gradlew :app:generateDebugSources", logger, outputFilter = { _, isError ->
            isError
        })
        val result = CmdExecutor(logger).invoke(cmd, context.cmdCompileEnv)
        if (result == 0) {
            logger.info("Generate debug source success.")
        } else {
            logger.warn("Generate debug source failed. See log for more details.")
        }
        return CompileResult(task, sourceFiles.map { Result.failure(CompileError(it, listOf(-1L to "assemble failed"))) }, emptyList())
    }

    override fun dispose() {
    }

}