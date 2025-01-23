@file:Suppress("unused")

package com.sickworm.intellij.jugg.compiler.demo

import com.google.auto.service.AutoService
import com.intellij.openapi.Disposable
import com.sickworm.intellij.jugg.compiler.*
import com.sickworm.intellij.jugg.compiler.ICompilerCreator

class ExampleDelayCustomCompiler(private val context: ICompileContext) : ICompiler {

    @AutoService(ICompilerCreator::class)
    companion object Creator : ICompilerCreator {
        override fun create(context: ICompileContext, parent: Disposable): ICompiler {
            return ExampleDelayCustomCompiler(context)
        }
    }

    override val supportedTypes: List<CompileFile.Type> = CompileFile.Type.entries

    override fun compile(task: CompileTask): CompileResult {
        if (context.projectDir.name != "MyApplicationIntellij") {
            return CompileResult(task, emptyList(), emptyList())
        }
        context.logger.info("[ExampleCustomCompiler] I'm in!")
        Thread.sleep(1000)
        context.logger.info("[ExampleCustomCompiler] I'm done!")
        return CompileResult(task, emptyList(), emptyList())
    }

    override fun dispose() {
        context.logger.debug("[ExampleCustomCompiler] I'm disposed!")
    }

}