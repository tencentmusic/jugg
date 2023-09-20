package com.sickworm.intellij.jugg.mock

import com.android.tools.idea.run.ApkInfo
import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.jugg.compiler.*
import java.io.File

class SimpleCompileContext(
    override val logger: Logger,
    override val tempCompileDir: File,
    override val androidHome: File,
    override val androidBuildTools: File,
    override val androidJar: File,
    override val modules: Map<String, ModuleInfo>,
    override val apkInfos: List<ApkInfo>,
    override val minApi: Int,
    override val projectDir: File,
) : ICompileContext {

    override fun getModuleDependencies(moduleInfo: ModuleInfo, task: CompileTask): List<String> {
        return emptyList()
    }

    override fun listenUpdate(listener: OnContextUpdate) {
    }
}