package com.sickworm.intellij.jugg.compiler.custom

import com.intellij.openapi.Disposable
import com.sickworm.intellij.jugg.compiler.ICompileContext
import com.sickworm.intellij.jugg.compiler.ICompiler

/**
 * ICompilerCreator constructs one [ICompiler] instance for a specific compile context.
 */
interface ICompilerCreator {
    fun create(context: ICompileContext, parent: Disposable): ICompiler
}
