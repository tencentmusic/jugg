package com.sickworm.intellij.jugg.mock

import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.jugg.compiler.ParsedApk
import com.sickworm.intellij.jugg.compiler.ICompileContext
import com.sickworm.intellij.jugg.compiler.ModuleInfo
import com.sickworm.intellij.jugg.compiler.OnContextUpdate
import java.io.File

class SimpleCompileContext(
    override val logger: Logger,
    override val tempCompileDir: File,
    override val androidHome: File,
    override val androidBuildTools: File,
    override val androidJar: File,
    override val modules: Map<String, ModuleInfo>,
    override val parsedApks: List<ParsedApk>,
    override val variant: String,
    override val minApi: Int
) : ICompileContext {

    override fun listenUpdate(listener: OnContextUpdate) {
    }
}