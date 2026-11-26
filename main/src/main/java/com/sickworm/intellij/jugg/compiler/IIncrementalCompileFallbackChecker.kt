package com.sickworm.intellij.jugg.compiler

import com.sickworm.intellij.jugg.deploy.JuggDeployState

/**
 * IIncrementalCompileFallbackChecker checks whether incremental compile must fall back to Gradle
 * compile without actually performing the compile operation.
 *
 * Returns the fallback reason string when fallback is required, or null when incremental compile
 * can proceed normally.
 */
fun interface IIncrementalCompileFallbackChecker {
    fun checkFallback(): String?

    /** Checks fallback against an existing deploy-state snapshot without requiring a state refresh. */
    fun checkFallback(deployState: JuggDeployState): String? = checkFallback()
}
