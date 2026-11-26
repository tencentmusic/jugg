package com.sickworm.intellij.jugg.compiler.source.apt

import com.sickworm.intellij.jugg.compiler.CompileFile
import com.sickworm.intellij.jugg.compiler.ICompileContext
import com.sickworm.intellij.jugg.project.info.ModuleInfo

/**
 * IJuggAptProcessor defines one generated-source producer in the Jugg APT pipeline.
 *
 * Processors share the current round's source analysis, then return generated sources
 * that should be recompiled in the same source-compile round.
 */
interface IJuggAptProcessor {

    /** A failed generation or generated-source compile must fail this source round. */
    val failClosed: Boolean get() = false

    /** Identifies this processor's sources when a language compiler reports a direct error. */
    fun ownsGeneratedFile(file: CompileFile): Boolean = false

    /**
     * Stable processor id for logging and troubleshooting.
     */
    val id: String get() = this::class.java.simpleName

    /**
     * Produces generated sources for the current module.
     *
     * @param context compile context for path/module/environment lookup
     * @param module current module being compiled
     * @param allCompileFiles all compile files in current module round
     * @param analysis shared source snapshots and lazy syntax facts for this module round
     * @return rewritten generated sources to be compiled in current round
     */
    fun process(
        context: ICompileContext,
        module: ModuleInfo,
        allCompileFiles: List<CompileFile>,
        analysis: AptSourceAnalysis,
    ): List<CompileFile>
}
