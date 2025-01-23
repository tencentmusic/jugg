package com.sickworm.intellij.jugg.compiler

import com.intellij.openapi.Disposable

interface ICompilerCreator {
    fun create(context: ICompileContext, parent: Disposable): ICompiler
}