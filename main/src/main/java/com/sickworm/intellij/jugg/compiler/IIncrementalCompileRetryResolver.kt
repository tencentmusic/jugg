package com.sickworm.intellij.jugg.compiler

/**
 * Strategy interface for determining whether an incremental compile failure can be resolved and retried.
 * Implementations may fix missing dependencies, refresh git-tracked file changes, etc.
 */
interface IIncrementalCompileRetryResolver {
    /**
     * Attempt to resolve the root cause of the compile failure.
     * @return true if the issue was resolved and a retry should be attempted
     */
    fun resolve(compileResult: CompileResult): Boolean
}