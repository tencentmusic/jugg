package com.sickworm.intellij.jugg.compiler.context

import com.intellij.openapi.diagnostic.Logger
import java.io.File

/** Resolves host compile environment when a compile or Gradle task actually needs it. */
interface ICompileEnvironmentSource {
    fun getAndroidHome(logger: Logger): File?
    fun buildCompileEnv(logger: Logger): List<String>
}

/** Provides a fixed compile environment for standalone runtime and tests. */
class CompileEnvironmentSource(
    private val androidHome: File?,
    private val compileEnv: List<String>,
) : ICompileEnvironmentSource {
    override fun getAndroidHome(logger: Logger): File? = androidHome

    override fun buildCompileEnv(logger: Logger): List<String> = compileEnv
}
