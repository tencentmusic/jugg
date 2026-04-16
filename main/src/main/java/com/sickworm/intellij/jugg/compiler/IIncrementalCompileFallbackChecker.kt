package com.sickworm.intellij.jugg.compiler

/**
 * IIncrementalCompileFallbackChecker checks whether incremental compile must fall back to Gradle
 * compile without actually performing the compile operation.
 *
 * Returns the fallback reason string when fallback is required, or null when incremental compile
 * can proceed normally.
 */
fun interface IIncrementalCompileFallbackChecker {
    fun checkFallback(): String?
}
