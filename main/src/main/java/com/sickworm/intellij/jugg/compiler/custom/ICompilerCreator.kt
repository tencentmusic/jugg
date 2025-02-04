package com.sickworm.intellij.jugg.compiler.custom

import com.intellij.openapi.Disposable
import com.sickworm.intellij.jugg.compiler.ICompileContext
import com.sickworm.intellij.jugg.compiler.ICompiler

interface ICompilerCreator {
    fun create(context: ICompileContext, parent: Disposable): ICompiler
}