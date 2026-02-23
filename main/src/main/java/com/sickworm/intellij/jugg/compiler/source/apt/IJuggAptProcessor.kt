package com.sickworm.intellij.jugg.compiler.source.apt

import com.sickworm.intellij.jugg.compiler.CompileFile
import com.sickworm.intellij.jugg.compiler.ICompileContext
import com.sickworm.intellij.jugg.project.data.ModuleInfo

/**
 * IJuggAptProcessor defines one custom generated-source rewriter in Jugg APT pipeline.
 *
 * Processors receive compile context + module + current round files, then return rewritten generated sources
 * that should be recompiled in the same source-compile round.
 */
interface IJuggAptProcessor {

    /**
     * Stable processor id for logging and troubleshooting.
     */
    val id: String get() = this::class.java.simpleName

    /**
     * Rewrites generated APT sources for the current module.
     *
     * @param context compile context for path/module/environment lookup
     * @param module current module being compiled
     * @param allCompileFiles all compile files in current module round
     * @param generatedAptFiles generated Java/Kotlin sources discovered for this module
     * @return rewritten generated sources to be compiled in current round
     */
    fun process(
        context: ICompileContext,
        module: ModuleInfo,
        allCompileFiles: List<CompileFile>,
        generatedAptFiles: List<CompileFile>,
    ): List<CompileFile>
}

